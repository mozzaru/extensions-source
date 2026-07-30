package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.util.Log
import eu.kanade.tachiyomi.extension.all.manhuarm.Dialog
import eu.kanade.tachiyomi.extension.all.manhuarm.Language
import eu.kanade.tachiyomi.extension.all.manhuarm.Manhuarm.Companion.PAGE_REGEX
import eu.kanade.tachiyomi.extension.all.manhuarm.cleanTranslationFailure
import eu.kanade.tachiyomi.multisrc.machinetranslations.translator.TranslatorEngine
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TranslationInterceptor(
    val language: Language,
    private val translator: TranslatorEngine,
) : Interceptor {

    private val json: Json by injectLazy()

    // An LRU cache for translated text. Many pages have repeated phrases
    // ("Hey", "Ugh", character names) and translating each one separately
    // wastes API quota. We use the source text as the key and the translated
    // text as the value.
    private val cache = LinkedHashMap<String, String>(64, 0.75f, false)
    private val cacheLock = Any()

    // Concurrency cap. Bing/Google both rate-limit, so spawning a coroutine
    // per dialog would hit the limit and either fail or just queue. With a
    // small cap we get good parallelism without triggering the limiter.
    private val rateLimiter = Semaphore(permits = 6)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (PAGE_REGEX.containsMatchIn(url).not() || language.target == language.origin) {
            return chain.proceed(request)
        }

        val dialogues = try {
            request.url.fragment?.parseAs<List<Dialog>>()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse fragment as Dialog list: ${e.message}")
            null
        } ?: return chain.proceed(request)

        Log.d(TAG, "Translating ${dialogues.size} dialogs ${language.origin} -> ${language.target}")
        val startedAt = System.currentTimeMillis()
        var uniqueTextCount = 0
        val cacheHitCount = AtomicInteger(0)

        val translated = try {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    // First pass: collect unique source texts to translate.
                    val sourceToTranslated = ConcurrentHashMap<String, String>()
                    val toTranslate = LinkedHashSet<String>()
                    dialogues.forEach { d ->
                        val src = d.text.cleanTranslationFailure()
                        if (src.isNotBlank()) toTranslate.add(src)
                    }
                    uniqueTextCount = toTranslate.size

                    // Translate unique texts in parallel, capped at 4 concurrent.
                    coroutineScope {
                        toTranslate.map { src ->
                            async(Dispatchers.IO) {
                                rateLimiter.withPermit {
                                    val cached = cacheLookup(src)
                                    if (cached != null) {
                                        cacheHitCount.incrementAndGet()
                                        sourceToTranslated[src] = cached
                                        return@async
                                    }
                                    val translatedText = translateWithFallback(src).cleanTranslationFailure()
                                    cacheStore(src, translatedText)
                                    sourceToTranslated[src] = translatedText
                                }
                            }
                        }.awaitAll()
                    }

                    // Second pass: map translations back to each dialog.
                    dialogues.map { d ->
                        val src = d.text.cleanTranslationFailure()
                        if (src.isBlank()) {
                            d.replaceText("")
                        } else {
                            val translatedText = sourceToTranslated[src] ?: src
                            d.replaceText(translatedText)
                        }
                    }
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
        val unchangedCount = translated.count { t ->
            val src = t.sourceText.cleanTranslationFailure()
            val translated = t.getTextBy(language).cleanTranslationFailure()
            src.isNotBlank() && translated == src
        }
        val changedCount = translated.count { t ->
            val src = t.sourceText.cleanTranslationFailure()
            val translated = t.getTextBy(language).cleanTranslationFailure()
            src.isNotBlank() && translated.isNotBlank() && translated != src
        }
        val cacheSize = synchronized(cacheLock) { cache.size }
        Log.d(
            TAG,
            "Translation: ${elapsed}ms for ${translated.size} dialogs " +
                "($uniqueTextCount unique), " +
                "changed=$changedCount unchanged=$unchangedCount empty=$emptyCount " +
                "cacheHits=${cacheHitCount.get()} apiCalls=${uniqueTextCount - cacheHitCount.get()} " +
                "cache=$cacheSize",
        )

        val newRequest = request.newBuilder()
            .url("${url.substringBeforeLast("#")}#${json.encodeToString(translated)}")
            .build()

        return chain.proceed(newRequest)
    }

    /**
     * Thread-safe cache lookup.
     */
    private fun cacheLookup(key: String): String? = synchronized(cacheLock) {
        cache[key]
    }

    /**
     * Thread-safe cache store with size limit.
     */
    private fun cacheStore(key: String, value: String) {
        synchronized(cacheLock) {
            if (cache.containsKey(key)) {
                cache[key] = value
                return
            }
            while (cache.size >= MAX_CACHE_ENTRIES) {
                val evicted = cache.keys.iterator().next()
                cache.remove(evicted)
            }
            cache[key] = value
        }
    }

    /**
     * Tries the configured translator; on failure or empty result, returns
     * the original source text.
     */
    private fun translateWithFallback(source: String): String = try {
        val result = translator.translate(language.origin, language.target, source)
        if (result.isBlank()) {
            Log.w(TAG, "Translator returned blank for: ${source.take(40)}")
            source
        } else {
            result
        }
    } catch (e: Exception) {
        Log.w(TAG, "Translator threw for '${source.take(40)}': ${e.message}")
        source
    }

    private companion object {
        const val TAG = "Manhuarm.Translate"
        const val MAX_CACHE_ENTRIES = 1000
    }
}
