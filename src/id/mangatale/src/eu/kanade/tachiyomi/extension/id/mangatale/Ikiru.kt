package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class Ikiru : HttpSource() {

    override val name: String = "Ikiru"
    override val baseUrl: String = "https://01.ikiru.wtf"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true
    override val id = 1532456597012176985

    override val client: OkHttpClient = network.client

    private val defaultHeaders: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android) Tachiyomi")
        .add("Referer", baseUrl)
        .build()

    // -----------------------
    // Popular
    // -----------------------
    override fun popularMangaRequest(page: Int): Request {
        // Popular listing uses the standard manga archive pagination
        val url = "$baseUrl/manga/page/$page/"
        return GET(url, defaultHeaders)
    }

    override fun popularMangaSelector(): String =
        "div[class*=\"archive\"] a[href*=\"/manga/\"] , a:has(img[src])"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.selectFirst("a[href*=\"/manga/\"]") ?: element
        val href = link.attr("href").trim()
        manga.url = cleanUrl(href)
        manga.title = link.selectFirst("img")?.attr("alt")?.trim()
            ?: link.text().trim()
        manga.thumbnail_url = link.selectFirst("img")?.attr("src")?.trim()
        return manga
    }

    override fun popularMangaNextPageSelector(): String? = "a.next, .pagination a.next"

    // -----------------------
    // Latest
    // -----------------------
    override fun latestUpdatesRequest(page: Int): Request {
        // site provides /latest-update/ with pagination
        val url = "$baseUrl/latest-update/page/$page/"
        return GET(url, defaultHeaders)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    // -----------------------
    // Search
    // -----------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Simple site search fallback: /?s=query
        val encoded = java.net.URLEncoder.encode(query, "utf-8")
        val url = "$baseUrl/?s=$encoded&paged=$page"
        return GET(url, defaultHeaders)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    // -----------------------
    // Details
    // -----------------------
    override fun mangaDetailsRequest(manga: SManga): Request {
        // manga.url already stored as absolute or relative path
        val url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        return GET(url, defaultHeaders)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        // Title - prefer JSON-LD name
        val jsonLd = document.selectFirst("script[type=application/ld+json]")?.data()
        if (!jsonLd.isNullOrBlank()) {
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(jsonLd)?.groups?.get(1)?.value?.let {
                manga.title = it
            }
        }
        if (manga.title.isNullOrBlank()) {
            manga.title = document.selectFirst("h1[itemprop=name], h1")?.text()?.trim() ?: ""
        }

        // Thumbnail
        manga.thumbnail_url = document.selectFirst("img.wp-post-image, img[itemprop=image]")?.attr("src")?.trim()

        // Description
        manga.description = document.selectFirst("[itemprop=description], .entry-content, .post-content, .summary")?.text()?.trim()

        // Genre
        manga.genre = document.select("a[href*=\"/genre/\"]")
            .joinToString { it.text().trim() }

        // Type (e.g., Manhwa, Manga)
        manga.other = document.selectFirst("h4:contains(Type) + div, .meta:contains(Type) + div")?.text()?.trim()

        // Author - try JSON-LD then fallback
        if (!jsonLd.isNullOrBlank()) {
            Regex("\"author\"\\s*:\\s*(?:\\{\"@type\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]+)\"|\"([^\"]+)\")")
                .find(jsonLd)?.groups?.get(1)?.value?.let {
                    manga.author = it
                }
        }
        if (manga.author.isNullOrBlank()) {
            // look for textual label "Author"
            document.select("*:matchesOwn((?i)author)").firstOrNull()?.let { label ->
                label.parent()?.selectFirst("a, span, div")?.text()?.trim()?.let { manga.author = it }
            }
        }

        // Status detection
        val statusText = document.select("*:matchesOwn((?i)status|ongoing|completed)").firstOrNull()?.text()
        manga.status = when {
            statusText == null -> Manga.UNKNOWN
            statusText.contains("ongoing", true) -> Manga.ONGOING
            statusText.contains("completed", true) -> Manga.COMPLETED
            else -> Manga.UNKNOWN
        }

        return manga
    }

    // -----------------------
    // Chapters (including hidden)
    // -----------------------
    override fun chapterListRequest(manga: SManga): Request {
        // Start by fetching the manga details page; chapterListParse will then call ajax endpoints if needed
        val url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        return GET(url, defaultHeaders)
    }

    override fun chapterListParse(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        // 1) Parse any chapter links already present in the page
        document.select("a[href*=\"/chapter-\"]").forEach { el ->
            safeAddChapterFromElement(el, chapters)
        }

        // 2) If we suspect there are hidden chapters, try to find manga_id and call AJAX endpoints
        val mangaId = extractMangaIdFromDocument(document)
        if (mangaId != null) {
            try {
                // First try full chapter list
                val ajaxUrl = "$baseUrl/ajax-call?manga_id=$mangaId&page=1&action=chapter_list"
                val ajaxDoc = fetchAjaxHtml(ajaxUrl, baseUrl + document.location(), document.location())
                if (ajaxDoc != null) {
                    ajaxDoc.select("a[href*=\"/chapter-\"]").forEach { el ->
                        safeAddChapterFromElement(el, chapters)
                    }
                }

                // Also try header/footer selects to ensure latest/hidden are included (head & footer)
                val currentChapterId = extractChapterIdFromDocument(document)
                val locs = listOf("head", "footer")
                for (loc in locs) {
                    val selectsUrl = if (currentChapterId != null) {
                        "$baseUrl/ajax-call?manga_id=$mangaId&chapter_id=$currentChapterId&action=chapter_selects&loc=$loc"
                    } else {
                        "$baseUrl/ajax-call?manga_id=$mangaId&action=chapter_selects&loc=$loc"
                    }
                    val selDoc = fetchAjaxHtml(selectsUrl, baseUrl + document.location(), document.location())
                    if (selDoc != null) {
                        selDoc.select("a[href*=\"/chapter-\"]").forEach { el ->
                            safeAddChapterFromElement(el, chapters)
                        }
                        // If the response contains <input name='nonce' ...> we can capture/log it if needed
                        val nonce = selDoc.selectFirst("input[name=nonce]")?.attr("value")
                        // We don't persist nonce to DB in SChapter, but if needed you can store in chapter.scanlator or elsewhere
                        if (!nonce.isNullOrBlank()) {
                            // for debug: (no-op) - implement storage if required
                        }
                    }
                }
            } catch (e: Exception) {
                // network/parse error - ignore and return what we already parsed
            }
        }

        // Remove duplicates and sort by chapter number if possible
        val unique = chapters.distinctBy { it.url }.toMutableList()
        // Attempt to sort by numeric chapter number (best-effort)
        unique.sortWith { a, b ->
            val an = a.chapter_number ?: -1f
            val bn = b.chapter_number ?: -1f
            bn.compareTo(an) // newest first
        }
        return unique
    }

    private fun safeAddChapterFromElement(el: Element, list: MutableList<SChapter>) {
        val ch = SChapter.create()
        val href = el.attr("href").trim()
        ch.url = cleanUrl(href)
        ch.name = el.text().trim()
        // parse chapter number if present in link text or URL (best-effort)
        ch.chapter_number = parseChapterNumber(ch.name) ?: parseChapterNumberFromUrl(href)
        // date upload: check sibling text (not always present)
        val dateText = el.parent()?.selectFirst(".date, span.date, time")?.text()
        ch.date_upload = parseDateToTimestamp(dateText)
        list.add(ch)
    }

    private fun parseChapterNumber(text: String?): Float? {
        if (text.isNullOrBlank()) return null
        // common patterns: "Chapter 12", "Ch. 12", "12"
        Regex("(?i)chapter\\s*([0-9]+(?:\\.[0-9]+)?)").find(text)?.groups?.get(1)?.value?.let { return it.toFloat() }
        Regex("([0-9]+(?:\\.[0-9]+)?)").find(text)?.groups?.get(1)?.value?.let { return it.toFloat() }
        return null
    }

    private fun parseChapterNumberFromUrl(url: String): Float? {
        // URL pattern often contains "chapter-7.760897" => take the "7" part
        Regex("chapter-([0-9]+(?:\\.[0-9]+)?)").find(url)?.groups?.get(1)?.value?.let { return it.toFloat() }
        Regex("/chapter-([0-9]+)\\.").find(url)?.groups?.get(1)?.value?.let { return it.toFloat() }
        return null
    }

    private fun parseDateToTimestamp(dateText: String?): Long {
        if (dateText.isNullOrBlank()) return 0L
        try {
            // Try relative terms or ISO
            if (dateText.contains("hour", true) || dateText.contains("jam", true)) {
                // approximate: return now
                return System.currentTimeMillis()
            }
            // Try parse ISO-like dates
            val formats = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss", "dd MMM yyyy", "MMM d, yyyy")
            for (fmt in formats) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val d: Date = sdf.parse(dateText) ?: continue
                    return d.time
                } catch (_: Exception) { /* continue */ }
            }
        } catch (_: Exception) { }
        return 0L
    }

    private fun extractMangaIdFromDocument(document: Document): String? {
        // find any element with hx-get containing 'ajax-call?manga_id='
        val el = document.selectFirst("*[hx-get*=\"ajax-call?manga_id=\"]")
            ?: document.selectFirst("*[data-bookmark-wrapper], *[data-bookmark-wrapper*=head]")
        val attr = el?.attr("hx-get") ?: el?.attr("data-hx-get") ?: el?.attr("data-hx")
        if (!attr.isNullOrBlank()) {
            // parse query param
            val parsed = HttpUrl.parse(attr)
            if (parsed != null) {
                val v = parsed.queryParameter("manga_id")
                if (!v.isNullOrBlank()) return v
            } else {
                // fallback parse by regex
                Regex("manga_id=([0-9]+)").find(attr)?.groups?.get(1)?.value?.let { return it }
            }
        }

        // try meta tags or script with data containing manga id
        Regex("manga_id\\W*[:=]\\W*([0-9]+)").find(document.html())?.groups?.get(1)?.value?.let { return it }
        return null
    }

    private fun extractChapterIdFromDocument(document: Document): String? {
        // try find element having chapter_id in hx-get or hx-vals or in current URL
        val el = document.selectFirst("*[hx-get*=\"chapter_id=\"]") ?: document.selectFirst("*[hx-vals*=\"chapter_id\"]")
        val attr = el?.attr("hx-get") ?: el?.attr("hx-vals")
        if (!attr.isNullOrBlank()) {
            Regex("chapter_id=([0-9]+)").find(attr)?.groups?.get(1)?.value?.let { return it }
            Regex("\"chapter_id\"\\s*:\\s*\"?([0-9]+)\"?").find(attr)?.groups?.get(1)?.value?.let { return it }
        }
        // try parse from current URL (document.location()) e.g. /chapter-269.759427/
        Regex("chapter-[0-9]+\\.([0-9]+)").find(document.location())?.groups?.get(1)?.value?.let { return it }
        return null
    }

    private fun fetchAjaxHtml(ajaxUrl: String, referer: String, currentUrl: String?): Document? {
        val reqBuilder = Request.Builder()
            .url(ajaxUrl)
            .get()
            .headers(defaultHeaders)

        // add htmx headers used by the site
        reqBuilder.header("hx-request", "true")
        if (!currentUrl.isNullOrBlank()) {
            reqBuilder.header("hx-current-url", currentUrl)
        }
        reqBuilder.header("Referer", referer)
        val req = reqBuilder.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body()?.string() ?: return null
            // The response is a fragment of HTML; parse it into Document
            return Jsoup.parse(body)
        }
    }

    private fun cleanUrl(href: String): String {
        var s = href.trim()
        if (s.startsWith(baseUrl)) {
            s = s.removePrefix(baseUrl)
        }
        // make sure it starts with '/'
        if (!s.startsWith("/")) s = "/$s"
        return s
    }

    // -----------------------
    // Page list (images)
    // -----------------------
    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        return GET(url, defaultHeaders)
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        // images often inside <img src="..."> in chapter container. Use a broad selector.
        document.select("div.chapter-content img, img.wp-post-image, img[src*=\"/chapter-\"] , img[src]").forEachIndexed { i, el ->
            val imgUrl = el.attr("src").trim()
            if (imgUrl.isNotBlank()) {
                pages.add(Page(i, "", imgUrl))
            }
        }
        // If no images found, try to look for JS arrays or lazy-loaded data attributes
        if (pages.isEmpty()) {
            // check for data-src attributes
            document.select("[data-src]").forEachIndexed { i, el ->
                val imgUrl = el.attr("data-src").trim()
                if (imgUrl.isNotBlank()) pages.add(Page(i, "", imgUrl))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // -----------------------
    // Utilities
    // -----------------------
    override fun fetchPopularManga(page: Int): Observable<MangasPage> = super.fetchPopularManga(page)
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = super.fetchLatestUpdates(page)
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = super.fetchSearchManga(page, query, filters)

    // Minimal FilterList placeholder (extend as needed)
    override fun getFilterList(): FilterList = FilterList()

    // If you need to obtain nonce explicitly (exposed helper)
    fun fetchNonceForComments(referer: String, currentUrl: String?): String? {
        val url = "$baseUrl/ajax-call?type=comment_form&action=get_nonce"
        val doc = fetchAjaxHtml(url, referer, currentUrl)
        return doc?.selectFirst("input[name=nonce]")?.attr("value")
    }
}
