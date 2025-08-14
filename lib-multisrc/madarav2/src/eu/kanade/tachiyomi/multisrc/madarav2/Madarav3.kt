package eu.kanade.tachiyomi.multisrc.madarav2

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
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

abstract class Madarav3(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val dateFormat: String
) : HttpSource() {

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

    // Popular manga
    override fun popularMangaRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(listUrl.trimEnd('/'))
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
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

    // Latest manga
    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("page")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            urlBuilder.addPathSegment(listUrl.trimEnd('/'))
            urlBuilder.addQueryParameter("s", query)
        } else {
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        if (filter.state > 0) {
                            val genreUrl = "$tagPrefix${filter.values[filter.state]}"
                            urlBuilder.addPathSegment(genreUrl)
                        }
                    }
                }
            }
        }

        urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request = GET(manga.url, headers)

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
        return when (document.select("div.post-status div.summary-content").text().lowercase()) {
            "ongoing", "berlanjut" -> SManga.ONGOING
            "completed", "tamat" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request = GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        val chapters = mutableListOf<SChapter>()

        // Dari HTML utama
        document.select("li.wp-manga-chapter").forEach {
            chapters.add(chapterFromElement(it))
        }

        // Fallback AJAX jika kosong
        if (chapters.isEmpty()) {
            val mangaId = document.selectFirst("input#manga_id")?.attr("value")
            if (!mangaId.isNullOrBlank()) {
                val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?action=manga_get_chapters&manga=$mangaId"
                val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
                val ajaxDoc = ajaxResponse.asDocument()
                ajaxDoc.select("li.wp-manga-chapter").forEach {
                    chapters.add(chapterFromElement(it))
                }
            }
        }

        return chapters
    }

    protected open fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        chapter.name = element.selectFirst("a")!!.text()
        chapter.date_upload = parseChapterDate(element.selectFirst("span.chapter-release-date")?.text())
        return chapter
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        return document.select("div.page-break img").mapIndexed { i, img ->
            Page(i, "", img.absUrl("src"))
        }
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // Helpers
    protected fun Response.asDocument(): Document = Jsoup.parse(body.string(), request.url.toString())

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val lc = dateStr.lowercase(sourceLocale)
            when {
                lc.contains("ago") || lc.contains("lalu") -> parseRelativeDate(lc)
                lc.contains("today") || lc.contains("hari ini") -> Date().time
                lc.contains("yesterday") || lc.contains("kemarin") -> Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -1)
                }.timeInMillis
                else -> SimpleDateFormat(dateFormat, sourceLocale).parse(dateStr)?.time ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseRelativeDate(date: String): Long {
        val parts = date.split(" ")
        if (parts.size < 2) return 0L
        val number = parts[0].toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        when (parts[1].first()) {
            'd', 'h' -> cal.add(Calendar.DATE, -number)
            'w' -> cal.add(Calendar.DATE, -number * 7)
            'm' -> cal.add(Calendar.MONTH, -number)
            'y' -> cal.add(Calendar.YEAR, -number)
        }
        return cal.timeInMillis
    }

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { charset.random() }.joinToString("")
    }

    // Filters
    open class GenreFilter(name: String, val values: Array<String>) :
        Filter.Select<String>(name, values)

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
            val resp = client.newCall(GET(url, headers)).execute()
            val doc = resp.asDocument()

            // Coba ambil dari menu genre
            val genreElements = doc.select("ul.genres li a, div.genres-content a, div.filter-item a")
            if (genreElements.isNotEmpty()) {
                return genreElements.map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .toTypedArray()
            }

            // Fallback kosong
            emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
    }
}
