package eu.kanade.tachiyomi.extension.id.mgkomik

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MGKOMIK : ParsedHttpSource(), ConfigurableSource {

    override val name: String = "MG KOMIK"
    override val baseUrl: String = "https://web.mgkomik.cc"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    override val client = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("dd MMM yy", Locale.US)
    private val relativeTimeRegex: Regex = Regex("""(\d+)\s*(jam|hari|minggu|bulan)\s*lalu""")

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            add("Accept-Language", "id-ID,id;q=0.9")
        }
    }

    private fun headersWithReferer(refererUrl: String): Headers {
        return headersBuilder()
            .add("Referer", refererUrl)
            .build()
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/komik/?order_by=trending&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = parseCardList(document)
        val hasNextPage = hasNextPage(document)
        return MangasPage(mangas, hasNextPage)
    }

    // ========== LATEST UPDATES ==========
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/komik/?order_by=latest&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = parseCardList(document)
        val hasNextPage = hasNextPage(document)
        return MangasPage(mangas, hasNextPage)
    }

    // ========== SEARCH ==========
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/?q=$query&page=$page"
        } else {
            buildFilterUrl(filters, page)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = parseCardList(document)
        return MangasPage(mangas, false)
    }

    // ========== MANGA DETAIL ==========
    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = manga.url.let {
            if (it.startsWith("http")) it else baseUrl + it
        }
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return parseMangaDetail(document)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = manga.url.let {
            if (it.startsWith("http")) it else baseUrl + it
        }
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("#chapterList .chapter-list-item").map { element ->
            val link = element.selectFirst(".chapter-link")
            val numberText = link?.selectFirst(".chapter-number")?.text()?.trim().orEmpty()
            val dateText = link?.selectFirst(".chapter-date")?.text()?.trim().orEmpty()
            val chapterUrl = link?.attr("abs:href").orEmpty()

            SChapter.create().apply {
                name = numberText
                url = chapterUrl
                chapter_number = extractChapterNumber(numberText)
                date_upload = parseChapterDate(dateText)
            }
        }
    }

    // ========== PAGE LIST ==========
    override fun pageListRequest(chapter: SChapter): Request {
        val url = chapter.url.let {
            if (it.startsWith("http")) it else baseUrl + it
        }
        val mangaUrl = url.substringBefore("/chapter-") + "/"
        return GET(url, headersWithReferer(mangaUrl))
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select(
            "img.wp-manga-chapter-img, " +
            "img.size-full, " +
            ".chapter-content img, " +
            ".reader-area img, " +
            "img.ts-main-image"
        )

        return images.mapIndexed { index, img ->
            Page(index, "", img.attr("abs:src"))
        }
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl, headersWithReferer("$baseUrl/"))
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    // ========== FILTERS ==========
    override fun getFilterList(): FilterList {
        return FilterList(
            TypeFilter(),
            StatusFilter(),
            GenreFilter(),
            OrderByFilter()
        )
    }

    // ========== PARSING HELPERS ==========

    private fun parseCardList(document: Document): List<SManga> {
        return document.select(".manga-card").mapNotNull { element ->
            val titleElement = element.selectFirst(".manga-title") ?: return@mapNotNull null
            val coverElement = element.selectFirst(".manga-cover")
            val slug = element.attr("data-slug")
            if (slug.isBlank()) return@mapNotNull null

            SManga.create().apply {
                title = titleElement.text().trim()
                thumbnail_url = coverElement?.attr("abs:src").orEmpty()
                url = "/komik/$slug/"
            }
        }
    }

    private fun parseMangaDetail(document: Document): SManga {
        val titleElement = document.selectFirst("#mangaTitle")
        val coverElement = document.selectFirst(".manga-cover-large")
        val descriptionElement = document.selectFirst(".manga-description p")
        val statusElement = document.selectFirst(".status-badge")
        val metaItems = document.select(".meta-item")
        val genreElements = document.select(".genre-tag")

        var author = ""
        var artist = ""

        for (item in metaItems) {
            val text = item.text().trim()
            when {
                text.contains("Author:", ignoreCase = true) ||
                text.contains("Pengarang:", ignoreCase = true) -> {
                    author = text.replace(Regex("(?i)(Author|Pengarang):"), "").trim()
                }
                text.contains("Artist:", ignoreCase = true) -> {
                    artist = text.replace(Regex("(?i)Artist:"), "").trim()
                }
            }
        }

        val statusText = statusElement?.text()?.trim().orEmpty()
        val status = when {
            statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val genres = genreElements.map { it.text().trim() }.joinToString(", ")

        val altTitle = titleElement?.attr("data-alt").orEmpty()
        val description = buildString {
            if (altTitle.isNotBlank()) {
                appendLine("Alt: $altTitle")
                appendLine()
            }
            append(descriptionElement?.text()?.trim().orEmpty())
        }

        return SManga.create().apply {
            title = titleElement?.text()?.trim().orEmpty()
            thumbnail_url = coverElement?.attr("abs:src").orEmpty()
            this.description = description.trim()
            this.status = status
            this.author = author
            this.artist = artist
            genre = genres
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.select(".pagination a.page-link")
            .any { it.ownText().contains("Next", ignoreCase = true) }
    }

    // ========== DATE PARSING ==========

    private fun parseChapterDate(dateString: String): Long {
        if (dateString.isBlank()) return 0L

        val relativeMatch = relativeTimeRegex.find(dateString.lowercase(Locale.ROOT))
        if (relativeMatch != null) {
            val amount = relativeMatch.groupValues[1].toIntOrNull() ?: return 0L
            val unit = relativeMatch.groupValues[2]
            val calendar = Calendar.getInstance()

            when (unit) {
                "jam" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
                "hari" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
                "minggu" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
                "bulan" -> calendar.add(Calendar.MONTH, -amount)
            }
            return calendar.timeInMillis
        }

        return try {
            dateFormat.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun extractChapterNumber(name: String): Float {
        val match = Regex("""(\d+(?:\.\d+)?)""").find(name)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }

    // ========== FILTER URL BUILDER ==========

    private fun buildFilterUrl(filters: FilterList, page: Int): String {
        val params = mutableListOf<String>()

        for (filter in filters) {
            when (filter) {
                is TypeFilter -> {
                    if (filter.state != 0) {
                        params.add("filter=${filter.values[filter.state].lowercase(Locale.ROOT)}")
                    }
                }
                is StatusFilter -> {
                    when (filter.state) {
                        1 -> params.add("status=on-going")
                        2 -> params.add("completed=1")
                    }
                }
                is GenreFilter -> {
                    if (filter.state != 0) {
                        val genre = filter.values[filter.state]
                            .lowercase(Locale.ROOT)
                            .replace(" ", "-")
                        params.add("filter=$genre")
                    }
                }
                is OrderByFilter -> {
                    params.add("order_by=${filter.values[filter.state].lowercase(Locale.ROOT)}")
                }
            }
        }

        params.add("page=$page")
        return "$baseUrl/komik/?${params.joinToString("&")}"
    }

    // ========== FILTER CLASSES ==========

    class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("Semua", "Manga", "Manhwa", "Manhua")
    )

    class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("Semua", "Ongoing", "Completed")
    )

    class GenreFilter : Filter.Select<String>(
        "Genre",
        arrayOf(
            "Semua",
            "Action",
            "Adventure",
            "Comedy",
            "Drama",
            "Fantasy",
            "Horror",
            "Martial Arts",
            "Mature",
            "Mystery",
            "Psychological",
            "Romance",
            "School Life",
            "Sci-Fi",
            "Seinen",
            "Shoujo",
            "Shounen",
            "Slice of Life",
            "Sports",
            "Supernatural",
            "Tragedy"
        )
    )

    class OrderByFilter : Filter.Select<String>(
        "Urutkan",
        arrayOf("Latest", "Trending")
    )
}
