package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class Ikiru : HttpSource() {

    override val name = "Ikiru"
    override val baseUrl = "https://01.ikiru.wtf"
    override val lang = "id"
    override val supportsLatest = true
    override val id = 1532456597012176985

    override val client: OkHttpClient = network.client

    private val headersBuilder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android) Tachiyomi")
        .add("Referer", baseUrl)
    private val defaultHeaders: Headers = headersBuilder.build()

    // ===========================
    // Popular
    // ===========================
    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/manga/page/$page/", defaultHeaders)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div[class*=\"archive\"] a[href*=\"/manga/\"], a:has(img[src])")
            .map { popularFromElement(it) }
        val hasNext = document.selectFirst("a.next, .pagination a.next") != null
        return MangasPage(mangas, hasNext)
    }

    private fun popularFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.selectFirst("a[href*=\"/manga/\"]") ?: element
        manga.url = cleanUrl(link.attr("href"))
        manga.title = link.selectFirst("img")?.attr("alt")?.trim()
            ?: link.text().trim()
        manga.thumbnail_url = link.selectFirst("img")?.attr("src")?.trim()
        return manga
    }

    // ===========================
    // Latest
    // ===========================
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/latest-update/page/$page/", defaultHeaders)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div[class*=\"archive\"] a[href*=\"/manga/\"], a:has(img[src])")
            .map { popularFromElement(it) }
        val hasNext = document.selectFirst("a.next, .pagination a.next") != null
        return MangasPage(mangas, hasNext)
    }

    // ===========================
    // Search
    // ===========================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$baseUrl/?s=${java.net.URLEncoder.encode(query, "utf-8")}&paged=$page", defaultHeaders)

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div[class*=\"archive\"] a[href*=\"/manga/\"], a:has(img[src])")
            .map { popularFromElement(it) }
        val hasNext = document.selectFirst("a.next, .pagination a.next") != null
        return MangasPage(mangas, hasNext)
    }

    // ===========================
    // Details
    // ===========================
    override fun mangaDetailsRequest(manga: SManga) =
        GET(if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url, defaultHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()

        val jsonLd = document.selectFirst("script[type=application/ld+json]")?.data()
        if (!jsonLd.isNullOrBlank()) {
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(jsonLd)?.groupValues?.get(1)?.let {
                manga.title = it
            }
        }
        if (manga.title.isNullOrBlank()) {
            manga.title = document.selectFirst("h1[itemprop=name], h1")?.text()?.trim() ?: ""
        }

        manga.thumbnail_url = document.selectFirst("img.wp-post-image, img[itemprop=image]")?.attr("src")?.trim()
        manga.description = document.selectFirst("[itemprop=description], .entry-content, .post-content, .summary")?.text()?.trim()
        manga.genre = document.select("a[href*=\"/genre/\"]").joinToString { it.text().trim() }

        if (!jsonLd.isNullOrBlank()) {
            Regex("\"author\"\\s*:\\s*\\{\"@type\".*?\"name\"\\s*:\\s*\"([^\"]+)\"").find(jsonLd)?.groupValues?.get(1)?.let {
                manga.author = it
            }
        }
        if (manga.author.isNullOrBlank()) {
            document.select("*:matchesOwn((?i)author)").firstOrNull()?.let { label ->
                manga.author = label.parent()?.selectFirst("a, span, div")?.text()?.trim()
            }
        }

        val statusText = document.select("*:matchesOwn((?i)status|ongoing|completed)").firstOrNull()?.text()
        manga.status = when {
            statusText == null -> SManga.UNKNOWN
            statusText.contains("ongoing", true) -> SManga.ONGOING
            statusText.contains("completed", true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        return manga
    }

    // ===========================
    // Chapters
    // ===========================
    override fun chapterListRequest(manga: SManga) =
        GET(if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url, defaultHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        document.select("a[href*=\"/chapter-\"]").forEach { el -> addChapter(el, chapters) }

        val mangaId = extractMangaId(document)
        if (mangaId != null) {
            val ajaxUrl = "$baseUrl/ajax-call?manga_id=$mangaId&page=1&action=chapter_list"
            fetchAjax(ajaxUrl, document.location())?.select("a[href*=\"/chapter-\"]")
                ?.forEach { addChapter(it, chapters) }

            val chapterId = extractChapterId(document)
            listOf("head", "footer").forEach { loc ->
                val selectsUrl = if (chapterId != null) {
                    "$baseUrl/ajax-call?manga_id=$mangaId&chapter_id=$chapterId&action=chapter_selects&loc=$loc"
                } else {
                    "$baseUrl/ajax-call?manga_id=$mangaId&action=chapter_selects&loc=$loc"
                }
                fetchAjax(selectsUrl, document.location())?.select("a[href*=\"/chapter-\"]")
                    ?.forEach { addChapter(it, chapters) }
            }
        }

        return chapters.distinctBy { it.url }.sortedByDescending { it.chapter_number ?: -1f }
    }

    private fun addChapter(el: Element, list: MutableList<SChapter>) {
        val ch = SChapter.create()
        ch.url = cleanUrl(el.attr("href"))
        ch.name = el.text().trim()
        ch.chapter_number = parseChapterNumber(ch.name) ?: parseChapterNumberFromUrl(el.attr("href"))
        ch.date_upload = el.parent()?.selectFirst(".date, span.date, time")?.text()?.let { parseDate(it) } ?: 0L
        list.add(ch)
    }

    private fun parseChapterNumber(text: String?): Float? {
        if (text.isNullOrBlank()) return null
        Regex("(?i)chapter\\s*([0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        Regex("([0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        return null
    }

    private fun parseChapterNumberFromUrl(url: String): Float? {
        Regex("chapter-([0-9]+(?:\\.[0-9]+)?)").find(url)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        return null
    }

    private fun parseDate(date: String): Long {
        val formats = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss", "dd MMM yyyy", "MMM d, yyyy")
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(date)?.time ?: 0L
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun extractMangaId(document: Document): String? {
        val attr = document.selectFirst("*[hx-get*=\"ajax-call?manga_id=\"]")?.attr("hx-get")
        return attr?.toHttpUrlOrNull()?.queryParameter("manga_id")
            ?: Regex("manga_id=([0-9]+)").find(attr ?: "")?.groupValues?.get(1)
    }

    private fun extractChapterId(document: Document): String? {
        val attr = document.selectFirst("*[hx-get*=\"chapter_id=\"]")?.attr("hx-get")
        return attr?.toHttpUrlOrNull()?.queryParameter("chapter_id")
            ?: Regex("chapter-[0-9]+\\.([0-9]+)").find(document.location())?.groupValues?.get(1)
    }

    private fun fetchAjax(url: String, currentUrl: String): Document? {
        val req = Request.Builder()
            .url(url)
            .headers(defaultHeaders)
            .addHeader("hx-request", "true")
            .addHeader("hx-current-url", currentUrl)
            .addHeader("Referer", currentUrl)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return Jsoup.parse(resp.body.string())
        }
    }

    private fun cleanUrl(href: String): String {
        var s = href.trim()
        if (s.startsWith(baseUrl)) s = s.removePrefix(baseUrl)
        if (!s.startsWith("/")) s = "/$s"
        return s
    }

    // ===========================
    // Pages
    // ===========================
    override fun pageListRequest(chapter: SChapter) =
        GET(if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url, defaultHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()
        document.select("div.chapter-content img, img[src*=\"/chapter-\"]").forEachIndexed { i, el ->
            el.attr("src").takeIf { it.isNotBlank() }?.let { pages.add(Page(i, "", it)) }
        }
        if (pages.isEmpty()) {
            document.select("[data-src]").forEachIndexed { i, el ->
                el.attr("data-src").takeIf { it.isNotBlank() }?.let { pages.add(Page(i, "", it)) }
            }
        }
        return pages
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    private fun Response.asJsoup(): Document = Jsoup.parse(this.body.string(), this.request.url.toString())
}
