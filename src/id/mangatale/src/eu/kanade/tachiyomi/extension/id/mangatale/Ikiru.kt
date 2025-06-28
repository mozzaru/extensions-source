package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Ikiru : HttpSource() {
    override val name = "Ikiru"
    override val baseUrl = "https://id.ikiru.wtf"
    override val lang = "id"
    override val id = 5884651267742186139L
    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

    // Popular manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/?order=popular&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.body.string().let { org.jsoup.Jsoup.parse(it) }
        val mangaList = document.select("div.manga-item, div.item, .post-item").map { element: Element ->
            SManga.create().apply {
                title = element.select("h3, .title, .entry-title").text().trim()
                setUrlWithoutDomain(element.select("a").attr("href"))
                thumbnail_url = element.select("img").attr("abs:src")
                    .takeIf { it.isNotEmpty() } ?: element.select("img").attr("abs:data-src")
            }
        }

        val hasNextPage = document.select("a.next, .next-page, .pagination .next").isNotEmpty()

        return MangasPage(mangaList, hasNextPage)
    }

    // Latest updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest-update/?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // Search manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/".toHttpUrl().newBuilder()
        
        if (query.isNotEmpty()) {
            url.addQueryParameter("s", query)
        }
        
        url.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("genre", filter.toUriPart())
                    }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status", filter.toUriPart())
                    }
                }
                is TypeFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("type", filter.toUriPart())
                    }
                }
                is SortFilter -> {
                    url.addQueryParameter("order", filter.toUriPart())
                }
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.body.string().let { org.jsoup.Jsoup.parse(it) }
        
        return SManga.create().apply {
            title = document.select("h1.entry-title, .manga-title h1, h1").first()?.text()?.trim() ?: ""
            
            thumbnail_url = document.select(".manga-cover img, .thumb img, .post-thumb img")
                .attr("abs:src")
                .takeIf { it.isNotEmpty() } ?: document.select(".manga-cover img, .thumb img")
                .attr("abs:data-src")

            description = document.select(".summary, .synopsis, .entry-content p").first()?.text()?.trim()

            author = document.select(".author, .manga-author").text().trim()
                .takeIf { it.isNotEmpty() }

            artist = author

            status = parseStatus(document.select(".status, .manga-status").text().trim())

            genre = document.select(".genres a, .genre-list a, .tag-list a")
                .joinToString { element: Element -> element.text().trim() }
        }
    }

    private fun parseStatus(status: String): Int {
        return when (status.lowercase()) {
            "ongoing", "berlangsung" -> SManga.ONGOING
            "completed", "selesai", "tamat" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "dibatalkan" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // Chapter list
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.body.string().let { org.jsoup.Jsoup.parse(it) }
        val chapters = mutableListOf<SChapter>()

        document.select(".chapter-list li, .chapters li, .episode-list li").forEach { element: Element ->
            val chapter = SChapter.create()
            
            val linkElement = element.select("a").first()
            chapter.setUrlWithoutDomain(linkElement?.attr("href") ?: "")
            chapter.name = linkElement?.text()?.trim() ?: element.text().trim()
            
            val dateText = element.select(".date, .chapter-date").text().trim()
            chapter.date_upload = parseChapterDate(dateText)
            
            chapters.add(chapter)
        }

        return chapters.reversed() // Usually chapters are in reverse order
    }

    private fun parseChapterDate(dateStr: String): Long {
        return try {
            when {
                dateStr.contains("hari") -> {
                    val days = dateStr.replace("\\D".toRegex(), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_MONTH, -days)
                    }.timeInMillis
                }
                dateStr.contains("jam") -> {
                    val hours = dateStr.replace("\\D".toRegex(), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.HOUR_OF_DAY, -hours)
                    }.timeInMillis
                }
                dateStr.contains("minggu") -> {
                    val weeks = dateStr.replace("\\D".toRegex(), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.WEEK_OF_YEAR, -weeks)
                    }.timeInMillis
                }
                else -> {
                    val formats = arrayOf(
                        SimpleDateFormat("dd/MM/yyyy", Locale.US),
                        SimpleDateFormat("yyyy-MM-dd", Locale.US),
                        SimpleDateFormat("dd-MM-yyyy", Locale.US)
                    )
                    
                    for (format in formats) {
                        try {
                            return format.parse(dateStr)?.time ?: 0L
                        } catch (e: Exception) {
                            continue
                        }
                    }
                    0L
                }
            }
        } catch (e: Exception) {
            0L
        }
    }

    // Page list
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.body.string().let { org.jsoup.Jsoup.parse(it) }
        val pages = mutableListOf<Page>()

        // Try different selectors for manga reader images
        val imageElements = document.select(
            ".read-content img, " +
            ".manga-reader img, " +
            ".reader-content img, " +
            "#readerarea img, " +
            ".entry-content img"
        )

        imageElements.forEachIndexed { index: Int, element: Element ->
            val imageUrl = element.attr("abs:src").takeIf { it.isNotEmpty() }
                ?: element.attr("abs:data-src").takeIf { it.isNotEmpty() }
                ?: element.attr("abs:data-lazy-src").takeIf { it.isNotEmpty() }

            if (imageUrl.isNotEmpty() && isValidImageUrl(imageUrl)) {
                pages.add(Page(index, "", imageUrl))
            }
        }

        // If no images found, try alternative method
        if (pages.isEmpty()) {
            val scriptContent = document.select("script").html()
            val imageRegex = Regex("""["'](https?://[^"']*\.(jpg|jpeg|png|gif|webp))["']""", RegexOption.IGNORE_CASE)
            val matches = imageRegex.findAll(scriptContent)
            
            matches.forEachIndexed { index: Int, match: MatchResult ->
                val imageUrl = match.groupValues[1]
                if (isValidImageUrl(imageUrl)) {
                    pages.add(Page(index, "", imageUrl))
                }
            }
        }

        return pages
    }

    private fun isValidImageUrl(url: String): Boolean {
        return url.contains(Regex("\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE)) &&
               url.startsWith("http") &&
               !url.contains("logo") &&
               !url.contains("avatar")
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // Filters
    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    private class SortFilter : Filter.Select<String>(
        "Urutkan berdasarkan",
        arrayOf("Default", "Terpopuler", "Terbaru", "A-Z", "Rating", "Update Terbaru")
    ) {
        fun toUriPart() = arrayOf("", "popular", "latest", "title", "rating", "update")[state]
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("Semua", "Ongoing", "Completed", "Hiatus")
    ) {
        fun toUriPart() = arrayOf("", "ongoing", "completed", "hiatus")[state]
    }

    private class TypeFilter : Filter.Select<String>(
        "Tipe",
        arrayOf("Semua", "Manga", "Manhwa", "Manhua")
    ) {
        fun toUriPart() = arrayOf("", "manga", "manhwa", "manhua")[state]
    }

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        arrayOf(
            "Semua", "Action", "Adventure", "Comedy", "Drama", "Fantasy", 
            "Horror", "Romance", "School Life", "Sci-fi", "Seinen", 
            "Shoujo", "Shounen", "Slice of Life", "Sports", "Supernatural", "Thriller"
        )
    ) {
        fun toUriPart() = arrayOf(
            "", "action", "adventure", "comedy", "drama", "fantasy",
            "horror", "romance", "school-life", "sci-fi", "seinen",
            "shoujo", "shounen", "slice-of-life", "sports", "supernatural", "thriller"
        )[state]
    }

    companion object {
        const val PREFIX_SLUG_SEARCH = "slug:"
    }
}