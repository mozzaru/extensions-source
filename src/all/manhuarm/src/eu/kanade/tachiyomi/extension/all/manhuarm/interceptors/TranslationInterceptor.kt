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

        val translated = runBlocking(Dispatchers.IO) {
            dialogues.map { dialog ->
                async {
                    // Skip translation if target language is already present
                    if (dialog.textByLanguage[language.target]?.isNotBlank() == true) {
                        return@async dialog
                    }

                    val (sourceLang, sourceText) = dialog.getBestSource(language.origin)
                    val translatedText = if (sourceText.isNotBlank()) {
                        translator.translate(sourceLang, language.target, sourceText)
                    } else {
                        ""
                    }
                    dialog.replaceText(translatedText)
                }
            }.awaitAll()
        }

        val newRequest = request.newBuilder()
            .url("${url.substringBeforeLast("#")}#${json.encodeToString(translated)}")
            .build()

        return chain.proceed(newRequest)
    }

    private fun Dialog.replaceText(value: String) = this.copy(
        textByLanguage = this.textByLanguage + ("text" to value),
    )

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
