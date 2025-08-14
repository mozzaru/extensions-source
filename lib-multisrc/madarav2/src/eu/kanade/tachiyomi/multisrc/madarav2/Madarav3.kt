package eu.kanade.tachiyomi.multisrc.madarav2

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Madarav3 final for madarav2 (extends ParsedHttpSource).
 *
 * - No wildcard imports
 * - Fixed lambda/return & Regex handling
 * - GenreFilter no longer overrides final 'values'
 * - Explicit org.json.JSONArray usage
 * - Consistent headersBuilder().build()
 */
abstract class Madarav3(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val dateFormat: String
) : ParsedHttpSource() {

    open val tagPrefix: String = "genres/"
    open val listUrl: String = ""
    open val stylePage: String = ""
    open val sourceLocale: Locale = Locale.ENGLISH

    private val randomLength = Random.nextInt(13, 21)
    private val randomString = generateRandomString(randomLength)

    override val supportsLatest: Boolean = true

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-User", "?1")
            .add("Upgrade-Insecure-Requests", "1")
            .add("X-Requested-With", randomString)
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val builder = baseUrl.toHttpUrl().newBuilder()
        if (listUrl.isNotBlank()) {
            val trimmed = listUrl.trim('/').split('/').filter { it.isNotBlank() }
            for (seg in trimmed) builder.addPathSegment(seg)
        }
        if (page > 1) builder.addQueryParameter("page", page.toString())
        return GET(builder.build().toString(), headersBuilder().build())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select("div.page-item-detail, div.page-listing-item").map { mangaFromElement(it) }
        val hasNext = document.select("a.next, a.pagination-next").isNotEmpty()
        return MangasPage(mangas, hasNext)
    }

    protected open fun mangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val anchor = element.selectFirst("a")
        manga.setUrlWithoutDomain(anchor?.attr("href").orEmpty())
        manga.title = element.selectFirst("h3, h4, h5")?.text().orEmpty()
        manga.thumbnail_url = element.selectFirst("img")?.absUrl("src")
            ?: element.selectFirst("img")?.absUrl("data-src")
        return manga
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val builder = baseUrl.toHttpUrl().newBuilder()
        builder.addPathSegment("page")
        if (page > 1) builder.addQueryParameter("page", page.toString())
        return GET(builder.build().toString(), headersBuilder().build())
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            if (listUrl.isNotBlank()) {
                val trimmed = listUrl.trim('/').split('/').filter { it.isNotBlank() }
                for (seg in trimmed) urlBuilder.addPathSegment(seg)
            }
            urlBuilder.addQueryParameter("s", query)
        } else {
            for (filter in filters) {
                when (filter) {
                    is GenreFilter -> {
                        if (filter.state > 0) {
                            val genreValue = filter.genreValues.getOrNull(filter.state) ?: ""
                            if (genreValue.isNotBlank()) {
                                val genreUrl = "$tagPrefix$genreValue"
                                val trimmed = genreUrl.trim('/').split('/').filter { it.isNotBlank() }
                                for (seg in trimmed) urlBuilder.addPathSegment(seg)
                            }
                        }
                    }
                    is Filter.Header -> { /* ignore */ }
                    is Filter.Separator -> { /* ignore */ }
                    is Filter.CheckBox -> { /* ignore */ }
                    is Filter.Text -> { /* ignore */ }
                    is Filter.Group<*> -> { /* ignore */ }
                    is Filter.Sort -> { /* ignore */ }
                    is Filter.Select<*> -> { /* ignore */ }
                    else -> { /* ignore unknown filter types */ }
                }
            }
        }

        if (page > 1) urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build().toString(), headersBuilder().build())
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Details
    override fun mangaDetailsRequest(manga: SManga): Request = GET(manga.url, headersBuilder().build())

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        val manga = SManga.create()
        manga.title = document.selectFirst("h1")?.text().orEmpty()
        manga.author = document.select("div.author-content a").joinToString { it.text() }
        manga.artist = document.select("div.artist-content a").joinToString { it.text() }
        manga.genre = document.select("div.genres-content a").joinToString { it.text() }
        manga.status = parseStatus(document)
        manga.description = document.select("div.description-summary p").joinToString("\n") { it.text() }
        manga.thumbnail_url = document.selectFirst("div.summary_image img")?.absUrl("src")
        return manga
    }

    private fun parseStatus(document: Document): Int {
        val text = document.select("div.post-status div.summary-content").text().lowercase(Locale.ROOT)
        return when {
            text.contains("ongoing") || text.contains("berlanjut") -> SManga.ONGOING
            text.contains("completed") || text.contains("tamat") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request = GET(manga.url, headersBuilder().build())

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        val chapters = mutableListOf<SChapter>()

        // HTML chapter list
        document.select("li.wp-manga-chapter, .chapter, .wp-manga-chapter").forEach {
            chapters.add(chapterFromElement(it))
        }

        // AJAX fallback if empty
        if (chapters.isEmpty()) {
            val mangaId = document.selectFirst("#manga-chapters-holder")?.attr("data-id")
                ?: document.selectFirst("input#manga_id")?.attr("value")
                ?: document.selectFirst("[data-id]")?.attr("data-id")
                ?: ""

            if (mangaId.isNotBlank()) {
                val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
                val payload = "$ajaxUrl?action=manga_get_chapters&manga=$mangaId"
                client.newCall(GET(payload, headersBuilder().build())).execute().use { ajaxResp ->
                    val ajaxDoc = ajaxResp.asDocument()
                    ajaxDoc.select("li.wp-manga-chapter, .chapter, .wp-manga-chapter").forEach {
                        chapters.add(chapterFromElement(it))
                    }
                }
            } else {
                val ajaxUrl2 = response.request.url.toString().trimEnd('/') + "/ajax/chapters/"
                client.newCall(GET(ajaxUrl2, headersBuilder().build())).execute().use { ajaxResp2 ->
                    if (ajaxResp2.isSuccessful) {
                        val ajaxDoc2 = ajaxResp2.asDocument()
                        ajaxDoc2.select("li.wp-manga-chapter, .chapter, .wp-manga-chapter").forEach {
                            chapters.add(chapterFromElement(it))
                        }
                    }
                }
            }
        }

        return chapters.sortedBy { it.date_upload }
    }

    protected open fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val a = element.selectFirst("a") ?: element
        chapter.setUrlWithoutDomain(a.attr("href"))
        chapter.name = a.text().ifBlank { a.attr("title") }
        chapter.date_upload = parseChapterDate(element.selectFirst("span.chapter-release-date")?.text())
        chapter.chapter_number = parseChapterNumber(chapter.name)
        return chapter
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headersBuilder().build())

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()

        val scriptImgs = extractImagesFromScripts(document)
        if (scriptImgs.isNotEmpty()) return scriptImgs.mapIndexed { i, url -> Page(i, "", url) }

        val container = document.selectFirst("div.reading-content, div.main-col-inner") ?: document
        val imgs = container.select("img")
            .mapNotNull { img ->
                val raw = img.attr("data-src").ifBlank { img.attr("src") }
                raw.takeIf { it.isNotBlank() }?.let { it.toAbsoluteUrl(baseUrl) }
            }
        if (imgs.isNotEmpty()) return imgs.mapIndexed { i, url -> Page(i, "", url) }

        val prot = tryDecryptProtector(document)
        if (prot.isNotEmpty()) return prot.mapIndexed { i, url -> Page(i, "", url) }

        throw Exception("No pages found")
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headersBuilder().build())

    // Utilities
    protected fun Response.asDocument(): Document = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    protected fun parseChapterNumber(name: String?): Float {
        if (name.isNullOrBlank()) return 0f
        val rx = Regex("""([0-9]+(?:[.,][0-9]+)?)""")
        val m = rx.find(name) ?: return 0f
        return m.groupValues[1].replace(",", ".").toFloatOrNull() ?: 0f
    }

    protected fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val lc = dateStr.trim().lowercase(sourceLocale)
        return try {
            when {
                lc.contains("today") || lc.contains("hari ini") -> Date().time
                lc.contains("yesterday") || lc.contains("kemarin") -> Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -1)
                }.timeInMillis
                lc.contains("ago") || lc.contains("lalu") -> parseRelativeDate(lc)
                else -> SimpleDateFormat(dateFormat, sourceLocale).parse(dateStr)?.time ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    protected fun parseRelativeDate(date: String): Long {
        val parts = date.split(" ")
        if (parts.size < 2) return 0L
        val number = parts[0].toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        when (parts[1].firstOrNull() ?: 'd') {
            'd', 'D' -> cal.add(Calendar.DATE, -number)
            'h', 'H' -> cal.add(Calendar.HOUR_OF_DAY, -number)
            'w', 'W' -> cal.add(Calendar.DATE, -number * 7)
            'm', 'M' -> cal.add(Calendar.MONTH, -number)
            'y', 'Y' -> cal.add(Calendar.YEAR, -number)
            else -> cal.add(Calendar.DATE, -number)
        }
        return cal.timeInMillis
    }

    private fun extractImagesFromScripts(doc: Document): List<String> {
        val scripts = doc.select("script").mapNotNull { it.data() }
        if (scripts.isEmpty()) return emptyList()

        val arrRegex = Regex("""var\s+images\s*=\s*(\[(?:.|\n|\r)*?])""", RegexOption.IGNORE_CASE)
        for (s in scripts) {
            val m = arrRegex.find(s) ?: continue
            runCatching {
                val arr = m.groupValues[1]
                val jsonArr = org.json.JSONArray(arr)
                val out = mutableListOf<String>()
                for (i in 0 until jsonArr.length()) {
                    val u = jsonArr.optString(i)
                    if (u.isNotBlank()) out.add(u.toAbsoluteUrl(baseUrl))
                }
                if (out.isNotEmpty()) return out
            }
        }

        val chapRegex = Regex("""chapter_data\s*=\s*['"](.+?)['"]""", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
        for (s in scripts) {
            val m = chapRegex.find(s) ?: continue
            runCatching {
                val raw = m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"").replace("\\'", "'")
                val jsonArr = org.json.JSONArray(raw)
                val out = mutableListOf<String>()
                for (i in 0 until jsonArr.length()) {
                    val u = jsonArr.optString(i)
                    if (u.isNotBlank()) out.add(u.toAbsoluteUrl(baseUrl))
                }
                if (out.isNotEmpty()) return out
            }
        }

        val atobRx = Regex("""atob\(['"]([A-Za-z0-9+/=]+)['"]\)""")
        for (s in scripts) {
            val m = atobRx.find(s) ?: continue
            runCatching {
                val decoded = String(java.util.Base64.getDecoder().decode(m.groupValues[1]))
                val jsonArr = org.json.JSONArray(decoded)
                val out = mutableListOf<String>()
                for (i in 0 until jsonArr.length()) {
                    val u = jsonArr.optString(i)
                    if (u.isNotBlank()) out.add(u.toAbsoluteUrl(baseUrl))
                }
                if (out.isNotEmpty()) return out
            }
        }

        return emptyList()
    }

    private fun tryDecryptProtector(doc: Document): List<String> {
        val out = mutableListOf<String>()

        val dataScripts = doc.select("script[src^=\"data:text/javascript;base64,\"]")
        for (s in dataScripts) {
            val src = s.attr("src")
            val b64 = src.substringAfter("data:text/javascript;base64,", "")
            if (b64.isNotBlank()) {
                runCatching {
                    val decoded = String(java.util.Base64.getDecoder().decode(b64))
                    val arrRx = Regex("""\[(?:\s*"(?:\\.|[^"])*"\s*(?:,\s*)?)+]""", RegexOption.DOT_MATCHES_ALL)
                    val arr = arrRx.find(decoded)?.value
                    if (!arr.isNullOrBlank()) {
                        val jsonArr = org.json.JSONArray(arr)
                        for (i in 0 until jsonArr.length()) {
                            val u = jsonArr.optString(i)
                            if (u.isNotBlank()) out.add(u.toAbsoluteUrl(baseUrl))
                        }
                    }
                }
            }
        }

        val scriptData = doc.select("script").mapNotNull { it.data() }
        for (s in scriptData) {
            val m = Regex("""chapter_data\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""").find(s)
            val enc = m?.groupValues?.getOrNull(1)
            if (!enc.isNullOrBlank()) {
                runCatching {
                    val decoded = String(java.util.Base64.getDecoder().decode(enc))
                    val jsonArr = org.json.JSONArray(decoded)
                    for (i in 0 until jsonArr.length()) {
                        val u = jsonArr.optString(i)
                        if (u.isNotBlank()) out.add(u.toAbsoluteUrl(baseUrl))
                    }
                }
            }
        }

        return out
    }

    private fun String.toAbsoluteUrl(base: String): String {
        return when {
            startsWith("http://") || startsWith("https://") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> base.trimEnd('/') + this
            else -> "$base/$this"
        }
    }

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { charset.random() }.joinToString("")
    }

    // Filters
    open class GenreFilter(name: String, val genreValues: Array<String>) : Filter.Select<String>(name, genreValues)

    override fun getFilterList(): FilterList {
        val genres = fetchGenres()
        return FilterList(
            Filter.Header("Filter genre opsional"),
            GenreFilter("Genre", arrayOf("All") + genres)
        )
    }

    private fun fetchGenres(): Array<String> {
        return try {
            val url = "$baseUrl/$listUrl"
            client.newCall(GET(url, headersBuilder().build())).execute().use { resp ->
                val doc = resp.asDocument()
                val el = doc.select("ul.genres li a, div.genres-content a, div.filter-item a")
                if (el.isNotEmpty()) {
                    return el.map { it.text().trim() }.filter { it.isNotBlank() }.distinct().toTypedArray()
                }
            }
            emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
    }
}
