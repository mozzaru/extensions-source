package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.os.Build
import androidx.annotation.RequiresApi
import eu.kanade.tachiyomi.extension.all.manhuarm.Dialog
import eu.kanade.tachiyomi.extension.all.manhuarm.Language
import eu.kanade.tachiyomi.extension.all.manhuarm.Manhuarm.Companion.PAGE_REGEX
import eu.kanade.tachiyomi.multisrc.machinetranslations.translator.TranslatorEngine
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

@RequiresApi(Build.VERSION_CODES.O)
class TranslationInterceptor(
    val language: Language,
    private val translator: TranslatorEngine,
) : Interceptor {

    private val json: Json by injectLazy()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (PAGE_REGEX.containsMatchIn(url).not() || language.target == language.origin) {
            return chain.proceed(request)
        }

        val dialogues = request.url.fragment?.parseAs<List<Dialog>>()
            ?: return chain.proceed(request)

        val toTranslate = dialogues.filter { it.textByLanguage[language.target].isNullOrBlank() }
        val alreadyTranslated = dialogues.filter { !it.textByLanguage[language.target].isNullOrBlank() }

        val translated = if (toTranslate.isEmpty()) {
            dialogues
        } else {
            runBlocking(Dispatchers.IO) {
                toTranslate.chunked(BATCH_SIZE).map { chunk ->
                    async {
                        // Use a separator that is unlikely to be in the text and preserved by translators
                        val separator = " ||| "
                        val regexSeparator = Regex("""\s*\|\|\|\s*""")
                        val sourceLang = "auto"
                        val combinedText = chunk.joinToString(separator) { it.getBestSource(language.origin).second }

                        val translatedBatch = if (combinedText.isNotBlank()) {
                            translator.translate(sourceLang, language.target, combinedText)
                        } else {
                            ""
                        }

                        val translatedTexts = translatedBatch.split(regexSeparator)

                        chunk.mapIndexed { index, dialog ->
                            val text = translatedTexts.getOrNull(index)?.trim() ?: ""
                            if (text.isNotBlank() && text != chunk[index].getBestSource(language.origin).second) {
                                dialog.replaceText(text)
                            } else {
                                dialog
                            }
                        }
                    }
                }.awaitAll().flatten() + alreadyTranslated
            }
        }

        val newRequest = request.newBuilder()
            .url("${url.substringBeforeLast("#")}#${json.encodeToString(translated)}")
            .build()

        return chain.proceed(newRequest)
    }

    private fun Dialog.replaceText(value: String) = this.copy(
        textByLanguage = this.textByLanguage + ("text" to value),
    )

    companion object {
        private const val BATCH_SIZE = 10
    }

    private fun Dialog.getBestSource(defaultOrigin: String): Pair<String, String> {
        // Try the default origin first
        textByLanguage[defaultOrigin]?.takeIf { it.isNotBlank() }?.let { return defaultOrigin to it }

        // Then try English
        textByLanguage["en"]?.takeIf { it.isNotBlank() }?.let { return "en" to it }

        // Then try Chinese (zh)
        textByLanguage["zh"]?.takeIf { it.isNotBlank() }?.let { return "zh" to it }

        // Finally fallback to any available text
        val firstAvailable = textByLanguage.entries.firstOrNull { it.value.isNotBlank() }
        return (firstAvailable?.key ?: defaultOrigin) to (firstAvailable?.value ?: "")
    }
}
