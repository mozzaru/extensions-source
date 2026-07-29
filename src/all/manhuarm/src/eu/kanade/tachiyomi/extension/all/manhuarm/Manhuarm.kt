package eu.kanade.tachiyomi.extension.all.manhuarm

import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.manhuarm.interceptors.CloudflareWarmupInterceptor
import eu.kanade.tachiyomi.extension.all.manhuarm.interceptors.ComposedImageInterceptor
import eu.kanade.tachiyomi.extension.all.manhuarm.interceptors.OcrUrlInterceptor
import eu.kanade.tachiyomi.extension.all.manhuarm.translator.bing.BingTranslator
import eu.kanade.tachiyomi.extension.all.manhuarm.translator.google.GoogleTranslator
import eu.kanade.tachiyomi.multisrc.machinetranslations.translator.TranslatorEngine
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.lib.i18n.Intl
import keiyoushi.lib.i18n.Intl.Companion.createDefaultMessageFileName
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Date
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Manhuarm :
    Madara(),
    ConfigurableSource {

    private val language: Language by lazy {
        when (lang) {
            "ar" -> Language(lang, disableFontSettings = true)
            "fr", "id" -> Language(lang, supportNativeTranslation = true)
            "pt-BR" -> Language(lang, "pt", supportNativeTranslation = true)
            else -> Language(lang)
        }
    }

    override val useNewChapterEndpoint: Boolean = true

    // Site uses custom mrm-* classes instead of standard Madara selectors
    override val mangaDetailsSelectorTitle = "h1.mrm-hero__title"
    override val mangaDetailsSelectorThumbnail = "div.mrm-hero__cover img, div.summary_image img"
    override val mangaDetailsSelectorGenre = "div.mrm-genres__list a, div.genres-content a"

    private val preferences: SharedPreferences by getPreferencesLazy()

    /**
     * A flag that tracks whether the settings have been changed. It is used to indicate if
     * any configuration change has occurred. Once the value is accessed, it resets to `false`.
     * This is useful for tracking whether a preference has been modified, and ensures that
     * the change status is cleared after it has been accessed, to prevent multiple triggers.
     */
    private var isSettingsChanged: Boolean = false
        get() {
            val current = field
            field = false
            return current
        }

    private var fontSize: Int
        get() = preferences.getString(FONT_SIZE_PREF, DEFAULT_FONT_SIZE)!!.toInt()
        set(value) = preferences.edit().putString(FONT_SIZE_PREF, value.toString()).apply()

    private var dialogBoxScale: Float
        get() = preferences.getString(DIALOG_BOX_SCALE_PREF, language.dialogBoxScale.toString())!!.toFloat()
        set(value) = preferences.edit().putString(DIALOG_BOX_SCALE_PREF, value.toString()).apply()

    private var fontName: String
        get() = preferences.getString(FONT_NAME_PREF, language.fontName)!!
        set(value) = preferences.edit().putString(FONT_NAME_PREF, value).apply()

    private var disableWordBreak: Boolean
        get() = preferences.getBoolean(DISABLE_WORD_BREAK_PREF, language.disableWordBreak)
        set(value) = preferences.edit().putBoolean(DISABLE_WORD_BREAK_PREF, value).apply()

    private var disableTranslator: Boolean
        get() = preferences.getBoolean(DISABLE_TRANSLATOR_PREF, language.disableTranslator)
        set(value) = preferences.edit().putBoolean(DISABLE_TRANSLATOR_PREF, value).apply()

    private var translateSynopsis: Boolean
        get() = preferences.getBoolean(TRANSLATE_SYNOPSIS_PREF, language.translateSynopsis)
        set(value) = preferences.edit().putBoolean(TRANSLATE_SYNOPSIS_PREF, value).apply()

    private var customUserAgent: String
        get() = preferences.getString(CUSTOM_UA_PREF, "")!!
        set(value) = preferences.edit().putString(CUSTOM_UA_PREF, value).apply()

    private val i18n = Intl(
        language = language.lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "es", "fr", "id", "it", "pt-BR"),
        classLoader = this::class.java.classLoader!!,
        createMessageFileName = { createDefaultMessageFileName("${name.lowercase()}_$it") },
    )

    private val settings get() = language.copy(
        fontSize = this@Manhuarm.fontSize,
        fontName = this@Manhuarm.fontName,
        dialogBoxScale = this@Manhuarm.dialogBoxScale,
        disableWordBreak = this@Manhuarm.disableWordBreak,
        disableTranslator = this@Manhuarm.disableTranslator,
        translateSynopsis = this@Manhuarm.translateSynopsis,
        disableFontSettings = this@Manhuarm.fontName == DEVICE_FONT,
    )

    override val client: OkHttpClient get() = clientInstance!!

    private val translators = arrayOf(
        "Bing",
        "Google",
    )

    private val provider: String get() =
        preferences.getString(TRANSLATOR_PROVIDER_PREF, translators.first())!!

    private val warmupInterceptor = CloudflareWarmupInterceptor(baseUrl, headers)

    private val ocrUrlInterceptor by lazy { OcrUrlInterceptor(headers) }

    private var clientInstance: OkHttpClient? = null
        get() {
            synchronized(this) {
                if (field == null || isSettingsChanged) {
                    warmupInterceptor.reset()
                    field = clientBuilder()
                }
                return field
            }
        }

    private val clientUtils = network.client.newBuilder()
        .rateLimit(3, 2.seconds)
        .build()

    private lateinit var translator: TranslatorEngine

    private val translateCache = LinkedHashMap<String, String>(256, 0.75f, false)

    private fun clientBuilder(): OkHttpClient {
        translator = when (provider) {
            "Google" -> GoogleTranslator(clientUtils, headers)
            else -> BingTranslator(clientUtils, headers)
        }

        return network.client.newBuilder()
            .connectTimeout(1.minutes)
            .readTimeout(2.minutes)
            // Fix disk cache / decompression issues
            .apply {
                val index = networkInterceptors().indexOfFirst { it is BrotliInterceptor }
                if (index >= 0) interceptors().add(networkInterceptors().removeAt(index))
            }
            .addInterceptor(warmupInterceptor)
            .addInterceptor(ComposedImageInterceptor(settings))
            .rateLimit(3, 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder()
        val ua = customUserAgent.trim()
        if (ua.isNotEmpty()) {
            builder["User-Agent"] = ua
        }
        return builder
    }

    private val translationAvailability = Calendar.getInstance().apply {
        set(2025, Calendar.SEPTEMBER, 9, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // =========================== Popular ==========================================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/manga/?sort=trending"
        } else {
            "$baseUrl/manga/page/$page/?sort=trending"
        }
        return GET(url, headers)
    }

    override fun popularMangaSelector(): String = "li.mrm-r-item"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val linkEl = element.selectFirst("a.mrm-r-item__link")
        val titleEl = element.selectFirst(".mrm-r-item__title")
        val thumbEl = element.selectFirst(".mrm-r-item__art img")
        if (linkEl != null) {
            manga.setUrlWithoutDomain(linkEl.attr("href"))
        }
        manga.title = titleEl?.text() ?: ""
        manga.thumbnail_url = thumbEl?.extractCoverUrl()
        return manga
    }

    override fun popularMangaNextPageSelector(): String = "a.next.page-numbers"

    override fun popularMangaParse(response: Response): MangasPage {
        val t0 = System.currentTimeMillis()
        val result = super.popularMangaParse(response)
        Log.d(TAG, "Popular manga list: ${result.mangas.size} items in ${System.currentTimeMillis() - t0}ms")
        return result
    }

    // =========================== Latest ==========================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/manga/?sort=latest"
        } else {
            "$baseUrl/manga/page/$page/?sort=latest"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage {
        val t0 = System.currentTimeMillis()
        val result = super.latestUpdatesParse(response)
        Log.d(TAG, "Latest manga list: ${result.mangas.size} items in ${System.currentTimeMillis() - t0}ms")
        return result
    }

    // =========================== Search ==========================================

    override fun searchMangaSelector(): String = "li.mrm-r-item"

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = "a.next.page-numbers"

    override fun searchMangaParse(response: Response): MangasPage {
        val t0 = System.currentTimeMillis()
        val result = super.searchMangaParse(response)
        Log.d(TAG, "Search results: ${result.mangas.size} items in ${System.currentTimeMillis() - t0}ms")
        return result
    }

    // =========================== Details ==========================================

    /**
     * Extracts the cover image URL from an image element, checking multiple attributes
     * to handle lazy loading and different image formats.
     */
    private fun Element?.extractCoverUrl(): String? {
        if (this == null) return null

        // Try data-src first (lazy loading)
        absUrl("data-src").takeIf { it.isNotBlank() && !it.contains("data:image") }?.let { return it }

        // Try src attribute
        absUrl("src").takeIf { it.isNotBlank() && !it.contains("data:image") && !it.contains("placeholder") }?.let { return it }

        // Try srcset attribute (parse first URL)
        attr("srcset").takeIf { it.isNotBlank() }?.let { srcset ->
            srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.let { url ->
                if (url.startsWith("http")) {
                    return url
                } else {
                    absUrl(url).takeIf { it.isNotBlank() && !it.contains("data:image") }?.let { return it }
                }
            }
        }

        return null
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)

        if (translateSynopsis && language.target != language.origin && !manga.description.isNullOrBlank()) {
            manga.description = translator.translate(language.origin, language.target, manga.description!!)
        }

        // Site uses custom mrm-* classes instead of standard Madara selectors
        val coverEl = document.selectFirst(".mrm-hero__cover img, .summary_image img, .wp-post-image, .item-thumb img, img.wp-post-image")
        if (coverEl != null) {
            coverEl.extractCoverUrl()?.let {
                if (it.isNotBlank() && !it.contains("placeholder")) {
                    manga.thumbnail_url = it
                }
            }
        }

        return manga
    }

    // =========================== Chapters =======================================

    override fun chapterListParse(response: Response): List<SChapter> = super.chapterListParse(response).filter {
        language.target == language.origin || Date(it.date_upload).after(translationAvailability.time)
    }

    // =========================== Pages ==========================================

    override fun pageListParse(document: Document): List<Page> {
        val pages = try {
            super.pageListParse(document)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse base page list: ${e.message}", e)
            return emptyList()
        }

        if (language.target == language.origin) {
            return pages
        }

        val chapterUrl = try {
            document.location().toHttpUrl().newBuilder()
                .removeAllQueryParameters("style")
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build chapter URL: ${e.message}")
            return pages
        }

        val pipelineStarted = System.currentTimeMillis()
        Log.d(
            TAG,
            "pageListParse: ${pages.size} raw pages for $chapterUrl",
        )

        val ocrRequest = try {
            ocrUrlInterceptor.getOcrRequest(chapterUrl.toString())
        } catch (e: Exception) {
            Log.w(
                TAG,
                "OCR interceptor threw: ${e.message}",
            )
            null
        }
        if (ocrRequest == null) {
            Log.w(
                TAG,
                "OCR request not captured; bubbles will be empty. " +
                    "Returning ${pages.size} un-translated pages.",
            )
            return pages
        }

        val jsonHeaders = Headers.Builder().apply {
            add("Referer", chapterUrl.toString())
            add("Accept", "*/*")

            ocrRequest.interceptedHeaders.forEach { (name, value) ->
                set(name, value)
            }
        }.build()

        val dialog = try {
            val response = client.newCall(
                POST(
                    ocrRequest.url,
                    jsonHeaders,
                    ocrRequest.body.toRequestBody("application/json; charset=utf-8".toMediaType()),
                ),
            ).execute()

            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "OCR POST returned ${response.code}; skipping translations",
                )
                response.close()
                emptyList()
            } else {
                val parsed = response.parseAs<List<PageDto>>()
                Log.d(
                    TAG,
                    "OCR POST returned ${parsed.size} pages, " +
                        "total dialogs: ${parsed.sumOf { it.dialogues.size }}",
                )
                parsed
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "OCR POST threw: ${e.message}",
            )
            emptyList()
        }

        if (dialog.isEmpty()) {
            val pipelineElapsed = System.currentTimeMillis() - pipelineStarted
            Log.w(TAG, "OCR returned no dialogs for $chapterUrl (took ${pipelineElapsed}ms)")
            return pages
        }

        // Pre-translate all unique dialogs at pipeline level instead of per-page
        val shouldTranslate = !disableTranslator && language.target != language.origin
        val sourceToTranslated: Map<String, String>
        if (shouldTranslate) {
            val t0 = System.currentTimeMillis()
            val uniqueSources = LinkedHashSet<String>()
            dialog.forEach { pageDto ->
                pageDto.dialogues.forEach { d ->
                    val src = d.text.cleanTranslationFailure()
                    if (src.isNotBlank()) uniqueSources.add(src)
                }
            }

            val rateLimiter = Semaphore(4)
            val map = HashMap<String, String>(uniqueSources.size)
            if (uniqueSources.isNotEmpty()) {
                runBlocking(Dispatchers.IO) {
                    coroutineScope {
                        uniqueSources.map { src ->
                            async(Dispatchers.IO) {
                                rateLimiter.withPermit {
                                    val cached = synchronized(translateCache) { translateCache[src] }
                                    if (cached != null) {
                                        map[src] = cached
                                        return@async
                                    }
                                    val result = try {
                                        val r = translator.translate(language.origin, language.target, src)
                                        if (r.isBlank()) src else r
                                    } catch (e: Exception) {
                                        src
                                    }.cleanTranslationFailure()
                                    synchronized(translateCache) {
                                        translateCache[src] = result
                                        while (translateCache.size > 1000) translateCache.remove(translateCache.keys.iterator().next())
                                    }
                                    map[src] = result
                                }
                            }
                        }.awaitAll()
                    }
                }
                val changed = map.entries.count { it.key != it.value }
                val unchanged = map.entries.count { it.key == it.value }
                Log.d(
                    TAG,
                    "Pre-translate [$provider]: ${uniqueSources.size} unique, " +
                        "changed=$changed, unchanged=$unchanged, " +
                        "cache=${synchronized(translateCache) { translateCache.size }} " +
                        "in ${System.currentTimeMillis() - t0}ms",
                )
            } else {
                Log.d(TAG, "No unique texts to translate")
            }
            sourceToTranslated = map
        } else {
            sourceToTranslated = emptyMap()
        }

        val mappedPages = pages.mapIndexed { index, page ->
            val pageUrl = page.imageUrl ?: return@mapIndexed page

            val dto = dialog.firstOrNull { d ->
                d.imageUrl.isNotBlank() && (
                    pageUrl.contains(d.imageUrl, ignoreCase = true) ||
                        d.imageUrl.contains(pageUrl, ignoreCase = true) ||
                        pageUrl.substringAfterLast("/").substringBefore("?")
                            .equals(d.imageUrl.substringAfterLast("/").substringBefore("?"), ignoreCase = true)
                    )
            } ?: dialog.getOrNull(index)

            if (dto == null) {
                return@mapIndexed page
            }

            val activeDialogues = dto.dialogues.mapNotNull { d ->
                val src = d.text.cleanTranslationFailure()
                val translated = sourceToTranslated[src] ?: src
                val updated = if (src.isBlank()) {
                    d.replaceText("")
                } else {
                    d.copy(
                        textByLanguage = buildMap {
                            putAll(d.textByLanguage)
                            put(language.origin, translated)
                            put("text", translated)
                        },
                        sourceText = d.sourceText.cleanTranslationFailure(),
                    )
                }
                if (updated.getTextBy(language).isNotBlank()) updated else null
            }
            if (activeDialogues.isEmpty()) {
                return@mapIndexed page
            }

            val fragment = json.encodeToString<List<Dialog>>(activeDialogues)
            Page(page.index, imageUrl = "${pageUrl.substringBefore("#")}${fragment.toFragment()}")
        }

        val dialogsCount = mappedPages.sumOf { page ->
            val frag = page.imageUrl?.substringAfter("#", "")
            if (frag.isNullOrBlank()) 0 else 1
        }
        val pipelineElapsed = System.currentTimeMillis() - pipelineStarted
        Log.d(
            TAG,
            "pageListParse: mapped $dialogsCount/${pages.size} pages with dialogs, took ${pipelineElapsed}ms",
        )
        return mappedPages
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .set("Referer", "$baseUrl/")
            .set("Connection", "keep-alive")
            .set("Accept-Language", "pt-BR,en-US;q=0.9,en;q=0.8")
            .set("Accept-Encoding", "gzip, deflate, br, zstd")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "cross-site")
            .set("Sec-Fetch-Storage-Access", "none")
            .set("Priority", "u=5, i")
            .set("TE", "trailers")
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    // ================================ Utils ============================================

    // Prevent bad fragments
    fun String.toFragment(): String = "#${this.replace("#", "*")}"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Some libreoffice font sizes
        val sizes = arrayOf(
            "12", "13", "14",
            "15", "16", "18",
            "20", "21", "22",
            "24", "26", "28",
            "32", "36", "40",
            "42", "44", "48",
            "54", "60", "72",
            "80", "88", "96",
        )

        val scale = (0..10).map { 1f + it / 10f }.toTypedArray()

        val fonts = arrayOf(
            i18n["font_name_device_title"] to DEVICE_FONT,
            "Anime Ace" to "animeace2_regular",
            "Comic Neue" to "comic_neue_bold",
            "Coming Soon" to "coming_soon_regular",
        )

        ListPreference(screen.context).apply {
            key = FONT_SIZE_PREF
            title = i18n["font_size_title"]
            entries = sizes.map {
                "${it}pt" + if (it == DEFAULT_FONT_SIZE) " - ${i18n["default_font_size"]}" else ""
            }.toTypedArray()
            entryValues = sizes

            summary = buildString {
                appendLine(i18n["font_size_summary"])
                append("\t* %s")
            }

            setDefaultValue(fontSize.toString())

            setOnPreferenceChange { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entries[index] as String

                fontSize = selected.toInt()

                Toast.makeText(
                    screen.context,
                    i18n["font_size_message"].format(entry),
                    Toast.LENGTH_LONG,
                ).show()

                true // It's necessary to update the user interface
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = DIALOG_BOX_SCALE_PREF
            title = i18n["dialog_box_scale_title"]
            entries = scale.map {
                "${it}x" + if (it == 1f) " - ${i18n["dialog_box_scale_default"]}" else ""
            }.toTypedArray()
            entryValues = scale.map(Float::toString).toTypedArray()

            summary = buildString {
                appendLine(i18n["dialog_box_scale_summary"])
                append("\t* %s")
            }

            setDefaultValue(dialogBoxScale.toString())

            setOnPreferenceChange { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entries[index] as String

                dialogBoxScale = selected.toFloat()

                Toast.makeText(
                    screen.context,
                    i18n["dialog_box_scale_message"].format(entry),
                    Toast.LENGTH_LONG,
                ).show()

                true // It's necessary to update the user interface
            }
        }.also(screen::addPreference)

        if (!language.disableFontSettings) {
            ListPreference(screen.context).apply {
                key = FONT_NAME_PREF
                title = i18n["font_name_title"]
                entries = fonts.map {
                    it.first + if (it.second.isBlank()) " - ${i18n["default_font_name"]}" else ""
                }.toTypedArray()
                entryValues = fonts.map { it.second }.toTypedArray()
                summary = buildString {
                    appendLine(i18n["font_name_summary"])
                    append("\t* %s")
                }

                setDefaultValue(fontName)

                setOnPreferenceChange { _, newValue ->
                    val selected = newValue as String
                    val index = this.findIndexOfValue(selected)
                    val entry = entries[index] as String

                    fontName = selected

                    Toast.makeText(
                        screen.context,
                        i18n["font_name_message"].format(entry),
                        Toast.LENGTH_LONG,
                    ).show()

                    true // It's necessary to update the user interface
                }
            }.also(screen::addPreference)
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = DISABLE_WORD_BREAK_PREF
            title = "⚠ ${i18n["disable_word_break_title"]}"
            summary = i18n["disable_word_break_summary"]
            setDefaultValue(language.disableWordBreak)
            setOnPreferenceChange { _, newValue ->
                disableWordBreak = newValue as Boolean
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CUSTOM_UA_PREF
            title = i18n["custom_user_agent_title"]
            summary = i18n["custom_user_agent_message"]
            setDefaultValue(customUserAgent)
            setOnPreferenceChange { _, newValue ->
                customUserAgent = (newValue as String).trim()
                true
            }
        }.also(screen::addPreference)

        if (language.target == language.origin) {
            return
        }

        if (language.supportNativeTranslation) {
            SwitchPreferenceCompat(screen.context).apply {
                key = DISABLE_TRANSLATOR_PREF
                title = "⚠ ${i18n["disable_translator_title"]}"
                summary = i18n["disable_translator_summary"]
                setDefaultValue(language.disableTranslator)
                setOnPreferenceChange { _, newValue ->
                    disableTranslator = newValue as Boolean
                    true
                }
            }.also(screen::addPreference)
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = TRANSLATE_SYNOPSIS_PREF
            title = i18n["translate_synopsis_title"]
            summary = i18n["translate_synopsis_summary"]
            setDefaultValue(language.translateSynopsis)
            setOnPreferenceChange { _, newValue ->
                translateSynopsis = newValue as Boolean
                true
            }
        }.also(screen::addPreference)

        if (!disableTranslator || translateSynopsis) {
            ListPreference(screen.context).apply {
                key = TRANSLATOR_PROVIDER_PREF
                title = i18n["translate_dialog_box_title"]
                entries = translators
                entryValues = translators
                summary = buildString {
                    appendLine(i18n["translate_dialog_box_summary"])
                    append("\t* %s")
                }

                setDefaultValue(translators.first())

                setOnPreferenceChange { _, newValue ->
                    val selected = newValue as String
                    val index = this.findIndexOfValue(selected)
                    val entry = entries[index] as String

                    Toast.makeText(
                        screen.context,
                        "${i18n["translate_dialog_box_toast"]} '$entry'",
                        Toast.LENGTH_LONG,
                    ).show()

                    true
                }
            }.also(screen::addPreference)
        }
    }

    /**
     * Sets an `OnPreferenceChangeListener` for the preference, and before triggering the original listener,
     * marks that the configuration has changed by setting `isSettingsChanged` to `true`.
     * This behavior is useful for applying runtime configurations in the HTTP client,
     * ensuring that the preference change is registered before invoking the original listener.
     */
    private fun Preference.setOnPreferenceChange(onPreferenceChangeListener: Preference.OnPreferenceChangeListener) {
        setOnPreferenceChangeListener { preference, newValue ->
            isSettingsChanged = true
            onPreferenceChangeListener.onPreferenceChange(preference, newValue)
        }
    }

    companion object {
        val PAGE_REGEX = Regex(".*?\\.(webp|png|jpg|jpeg)#\\[.*?]", RegexOption.IGNORE_CASE)

        const val DEVICE_FONT = "device:"
        private const val FONT_SIZE_PREF = "fontSizePref"
        private const val FONT_NAME_PREF = "fontNamePref"
        private const val DIALOG_BOX_SCALE_PREF = "dialogBoxScalePref"
        private const val DISABLE_WORD_BREAK_PREF = "disableWordBreakPref"
        private const val DISABLE_TRANSLATOR_PREF = "disableTranslatorPref"
        private const val TRANSLATE_SYNOPSIS_PREF = "translateSynopsisPref"
        private const val TRANSLATOR_PROVIDER_PREF = "translatorProviderPref"
        private const val CUSTOM_UA_PREF = "customUserAgentPref"
        private const val DEFAULT_FONT_SIZE = "28"
        private const val TAG = "Manhuarm"
    }
}
