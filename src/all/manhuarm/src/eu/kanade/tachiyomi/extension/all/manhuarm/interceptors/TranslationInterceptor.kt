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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    // An LRU cache for translated text. Many pages have repeated phrases
    // ("Hey", "Ugh", character names) and translating each one separately
    // wastes API quota. We use the source text as the key and the translated
    // text as the value.
    //
    // The cache is per-TranslationInterceptor instance, which means it's
    // recreated every time Mihon rebuilds the OkHttp client (which happens
    // whenever the user toggles a setting). That's fine: settings changes
    // often imply the user wants a fresh state, and the per-chapter benefit
    // is still significant.
    //
    // CACHE BOUND: we cap the size at MAX_CACHE_ENTRIES to prevent OOM. When
    // the cache is full and a new entry is inserted, the oldest entry is
    // evicted. With MAX_CACHE_ENTRIES=1000 and an average entry size of
    // ~700 bytes, the cache can hold up to ~700KB at most, which is well
    // within the heap budget of any Android app. The eviction policy is
    // simple (insertion order) - not true LRU but good enough for a small
    // fixed-size cache.
    private val cache = LinkedHashMap<String, String>(64, 0.75f, false)
    private val cacheLock = Any()

    // Concurrency cap. Bing/Google both rate-limit, so spawning a coroutine
    // per dialog would hit the limit and either fail or just queue. With a
    // small cap we get good parallelism without triggering the limiter.
    private val rateLimiter = Semaphore(permits = 4)

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

        val translated = try {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    // First pass: collect unique source texts to translate.
                    val sourceToTranslated = HashMap<String, String>()
                    val toTranslate = LinkedHashSet<String>()
                    dialogues.forEach { d ->
                        val src = d.text
                        if (src.isNotBlank()) toTranslate.add(src)
                    }

                    // Translate unique texts in parallel, capped at 4 concurrent.
                    coroutineScope {
                        toTranslate.map { src ->
                            async(Dispatchers.IO) {
                                rateLimiter.withPermit {
                                    val cached = cacheLookup(src)
                                    if (cached != null) {
                                        sourceToTranslated[src] = cached
                                        return@async
                                    }
                                    val translatedText = translateWithFallback(src)
                                    cacheStore(src, translatedText)
                                    sourceToTranslated[src] = translatedText
                                }
                            }
                        }.awaitAll()
                    }

                    // Second pass: map translations back to each dialog.
                    dialogues.map { d ->
                        val src = d.text
                        if (src.isBlank()) {
                            d
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
        val cacheSize = synchronized(cacheLock) { cache.size }
        if (emptyCount > 0) {
            Log.w(
                TAG,
                "Translation finished in ${elapsed}ms but $emptyCount/${translated.size} dialogs are blank (cache: $cacheSize)",
            )
        } else {
            Log.d(
                TAG,
                "Translation finished in ${elapsed}ms, ${translated.size} dialogs, cache: $cacheSize entries",
            )
        }

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
     * Thread-safe cache store with size limit. When the cache is full, the
     * oldest entry is evicted. This prevents OOM in the (unlikely) case of
     * a very long session with many unique dialogs.
     */
    private fun cacheStore(key: String, value: String) {
        synchronized(cacheLock) {
            // If key already exists, just update the value
            if (cache.containsKey(key)) {
                cache[key] = value
                return
            }
            // Evict oldest entries until we have room
            while (cache.size >= MAX_CACHE_ENTRIES) {
                val evicted = cache.keys.iterator().next()
                cache.remove(evicted)
                Log.d(TAG, "Cache full, evicted: ${evicted.take(30)}")
            }
            cache[key] = value
        }
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
            Log.w(TAG, "Translator returned blank for: ${source.take(40)}")
            source
        } else {
            result
        }
    } catch (e: Exception) {
        Log.w(TAG, "Translator threw for '${source.take(40)}': ${e.message}")
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

        /**
         * Maximum number of entries in the translation cache. The cache
         * uses LinkedHashMap with insertion-order iteration and evicts the
         * oldest entry when full. With 1000 entries and an average of
         * ~700 bytes per entry (English text + translation), the cache
         * can hold up to ~700KB which is well within the heap budget.
         *
         * If the user reads many distinct manga in one session, only the
         * last 1000 unique phrases are kept. Earlier ones will be re-
         * translated next time (small cost) but the app won't OOM.
         */
        const val MAX_CACHE_ENTRIES = 1000
    }
}
