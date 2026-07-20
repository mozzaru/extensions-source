package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.util.Log
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

        val dialogues = try {
            request.url.fragment?.parseAs<List<Dialog>>()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to parse fragment as Dialog list: ${e.message}",
            )
            null
        } ?: return chain.proceed(request)

        Log.d(
            TAG,
            "Translating ${dialogues.size} dialogs ${language.origin} -> ${language.target}",
        )
        val startedAt = System.currentTimeMillis()

        val translated = try {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    dialogues.map { dialog ->
                        async {
                            val source = dialog.text
                            if (source.isBlank()) {
                                dialog
                            } else {
                                val translatedText = translateWithFallback(source)
                                dialog.replaceText(translatedText)
                            }
                        }
                    }.awaitAll()
                }
            }.onFailure {
                Log.e(TAG, "Translation pipeline failed: ${it.message}", it)
            }.getOrElse { dialogues }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during translation: ${e.message}", e)
            dialogues
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val emptyCount = translated.count { it.getTextBy(language).isBlank() }
        if (emptyCount > 0) {
            Log.w(
                TAG,
                "Translation finished in ${elapsed}ms but $emptyCount/${translated.size} dialogs are blank",
            )
        } else {
            Log.d(
                TAG,
                "Translation finished in ${elapsed}ms, all ${translated.size} dialogs have text",
            )
        }

        val newRequest = request.newBuilder()
            .url("${url.substringBeforeLast("#")}#${json.encodeToString(translated)}")
            .build()

        return chain.proceed(newRequest)
    }

    /**
     * Tries the configured translator; on failure or empty result, returns
     * the original source text. This guarantees the user never sees an
     * empty bubble because of a translation failure - they may just see the
     * English text instead of the translated text.
     */
    private fun translateWithFallback(source: String): String = try {
        val result = translator.translate(language.origin, language.target, source)
        if (result.isBlank()) {
            Log.w(
                TAG,
                "Translator returned blank for: ${source.take(40)}",
            )
            source
        } else {
            result
        }
    } catch (e: Exception) {
        Log.w(
            TAG,
            "Translator threw for '${source.take(40)}': ${e.message}",
        )
        source
    }

    /**
     * Replace the rendered text while preserving the captured source text so
     * that [Dialog.text] keeps returning a non-empty string in case the
     * translation comes back blank.
     */
    private fun Dialog.replaceText(value: String) = this.copy(
        textByLanguage = buildMap {
            // Keep any existing native-translation keys the OCR response had.
            putAll(textByLanguage)
            put("text", value)
        },
    )

    private companion object {
        const val TAG = "Manhuarm.Translate"
    }
}
