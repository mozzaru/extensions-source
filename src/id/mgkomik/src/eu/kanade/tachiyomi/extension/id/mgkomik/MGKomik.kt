package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : HttpSource() {

    override val name = "MG Komik"

    override val baseUrl = "https://web.mgkomik.cc"

    override val lang = "id"

    override val supportsLatest = true

    override val id = 5845004992097969882L

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val newRequest = if (url.contains("admin-ajax.php") || url.contains("wp-json")) {
                request.newBuilder()
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }
        .rateLimit(9, 2)
        .build()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Referer", "$baseUrl/")
    }

    // ================================== Popular ======================================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/komik/".toHttpUrl().newBuilder().apply {
            addQueryParameter("order_by", "views")
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".manga__item, .post-item, div.page-item-detail:not(:has(a[href*='bilibilicomics.com']))").map { element ->
            SManga.create().apply {
                element.selectFirst(".manga-title a, .post-title a, .item-thumb a, .manga-title-large a")?.let {
                    setUrlWithoutDomain(it.attr("abs:href"))
                    title = it.text().ifBlank { it.attr("title") }
                }
                thumbnail_url = element.selectFirst(".manga-cover img, .item-thumb img, .manga-cover-large img, .post-title img")?.let {
                    it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
                }
            }
        }
        val hasNextPage = document.selectFirst(".pagination a:contains(Next), .pagination a.next, a.page-numbers.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ================================== Latest ======================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/komik/".toHttpUrl().newBuilder().apply {
            addQueryParameter("order_by", "latest")
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ================================== Search ======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/komik/".toHttpUrl().newBuilder().apply {
            addQueryParameter("filter", query)
            addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is OrderByFilter -> addQueryParameter("order_by", filter.toUriPart())
                    is StatusFilter -> addQueryParameter("status", filter.toUriPart())
                    is GenreFilter -> addQueryParameter("genre", filter.toUriPart())
                    else -> {}
                }
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".manga-title, h1#mangaTitle, .post-title h1, .manga-title-large h1")?.text() ?: ""
            thumbnail_url = document.selectFirst(".manga-cover-large img, .manga-cover img, .post-title img")?.let {
                it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
            }
            description = document.selectFirst(".manga-description p, .summary__content p, .manga-about, .description-content")?.text()
            author = document.selectFirst(".meta-item:contains(Author:) .meta-value, .author-content a, .manga-author")?.text()
            genre = document.select(".genre-tag, .genres-content a, .manga-genre a").joinToString { it.text() }
            status = parseStatus(document.selectFirst(".status-badge, .post-status .value, .manga-status")?.text())

            val altTitle = document.selectFirst(".manga-title")?.attr("data-alt")
                ?: document.selectFirst(".manga-altname, .manga-alternative")?.text()
            if (!altTitle.isNullOrBlank()) {
                description = (if (description.isNullOrBlank()) "" else description + "\n\n") + "Alternative Title: $altTitle"
            }
        }
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "ongoing", "berjalan" -> SManga.ONGOING
        "completed", "tamat", "selesai" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("li.chapter-list-item, .wp-manga-chapter").map { element ->
            SChapter.create().apply {
                val link = element.selectFirst("a")!!
                setUrlWithoutDomain(link.attr("abs:href"))
                name = link.text()
                val dateStr = element.selectFirst(".chapter-date, .chapter-release-date")?.text() ?: ""
                date_upload = parseChapterDate(dateStr)
            }
        }
    }

    private fun parseChapterDate(date: String): Long {
        return try {
            val formats = arrayOf("dd/MM/yyyy", "dd/MM/yy", "dd MMM yyyy", "dd MMM yy")
            for (format in formats) {
                try {
                    return SimpleDateFormat(format, Locale.US).parse(date)?.time ?: 0L
                } catch (e: Exception) {
                    // Try next format
                }
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("#readingContent img, .reading-content img").mapIndexed { index, element ->
            val url = element.attr("abs:data-src").ifBlank { element.attr("abs:src") }
            Page(index, imageUrl = cleanImageUrl(url))
        }
    }

    private fun cleanImageUrl(url: String): String {
        return url.replace(RESIZE_REGEX, "")
            .replace(SCALED_REGEX, "")
            .substringBefore("?resize")
            .substringBefore("?i=") // Photon
    }

    companion object {
        private val RESIZE_REGEX = "-\\d+x\\d+(?=\\.(jpg|jpeg|png|webp))".toRegex()
        private val SCALED_REGEX = "-scaled(?=\\.(jpg|jpeg|png|webp))".toRegex()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    override fun getMangaUrl(manga: SManga): String = when {
        manga.url.startsWith("http") -> manga.url
        manga.url.startsWith("//") -> "https:${manga.url}"
        else -> super.getMangaUrl(manga)
    }

    override fun getChapterUrl(chapter: SChapter): String = when {
        chapter.url.startsWith("http") -> chapter.url
        chapter.url.startsWith("//") -> "https:${chapter.url}"
        else -> super.getChapterUrl(chapter)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = FilterList(
        OrderByFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class OrderByFilter :
        UriPartFilter(
            "Order By",
            arrayOf(
                "Default" to "",
                "Latest" to "latest",
                "Views" to "views",
                "Trending" to "trending",
                "New" to "new",
                "Rating" to "rating",
                "Alphabetical" to "alphabetical",
            ),
        )

    private class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                "All" to "",
                "Ongoing" to "ongoing",
                "Completed" to "completed",
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                "All" to "",
                "Action" to "action",
                "Adventure" to "adventure",
                "Comedy" to "comedy",
                "Drama" to "drama",
                "Fantasy" to "fantasy",
                "Harem" to "harem",
                "Historical" to "historical",
                "Horror" to "horror",
                "Isekai" to "isekai",
                "Martial Arts" to "martial-arts",
                "Mecha" to "mecha",
                "Mystery" to "mystery",
                "Psychological" to "psychological",
                "Romance" to "romance",
                "School Life" to "school-life",
                "Sci-fi" to "sci-fi",
                "Seinen" to "seinen",
                "Shoujo" to "shoujo",
                "Shounen" to "shounen",
                "Slice of Life" to "slice-of-life",
                "Sports" to "sports",
                "Supernatural" to "supernatural",
                "Tragedy" to "tragedy",
            ),
        )
}
