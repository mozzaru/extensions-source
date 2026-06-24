package eu.kanade.tachiyomi.extension.id.hwago

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Hwago : HttpSource() {

    override val name = "Hwago"

    override val baseUrl = "https://02.hwago.xyz"

    override val lang = "id"

    override val id = 5490253431755133280

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = browseRequest(page, sort = "popular")

    override fun popularMangaParse(response: Response): MangasPage = parseBrowse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = browseRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseBrowse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.toUriPart().orEmpty()
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.toUriPart().orEmpty()
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()

        return browseRequest(
            page = page,
            query = query,
            sort = sort,
            status = status,
            type = type,
            genre = genre,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = parseBrowse(response)

    private fun browseRequest(
        page: Int,
        query: String = "",
        sort: String = "",
        status: String = "",
        type: String = "",
        genre: String = "",
    ): Request {
        val url = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("q", query)
            if (sort.isNotBlank()) addQueryParameter("sort", sort)
            if (status.isNotBlank()) addQueryParameter("status", status)
            if (type.isNotBlank()) addQueryParameter("type", type)
            if (genre.isNotBlank()) addQueryParameter("genre", genre)
        }.build()

        return GET(url, headers)
    }

    private fun parseBrowse(response: Response): MangasPage {
        val document = response.asJsoup()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val mangas = document.select("a[href]")
            .asSequence()
            .map { it.attr("abs:href") }
            .filter(::isComicUrl)
            .distinct()
            .mapNotNull { mangaFromUrl(document, it) }
            .toList()

        val hasNextPage = mangas.isNotEmpty() && document.select("a[href]").any {
            it.attr("href").contains("page=${page + 1}")
        }

        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromUrl(document: Document, url: String): SManga? {
        val anchors = document.select("a[href]").filter { it.attr("abs:href") == url }
        val slug = url.substringAfter("/comic/").substringBefore("/")

        val title = anchors.asSequence()
            .mapNotNull(::titleFromElement)
            .firstOrNull()
            ?: return null

        val thumbnail = anchors.asSequence()
            .mapNotNull { it.selectFirst("img")?.let(::imageFromElement) }
            .firstOrNull()
            ?: document.selectFirst("img[src*='/comic/$slug/cover_'], img[data-src*='/comic/$slug/cover_']")
                ?.let(::imageFromElement)

        return SManga.create().apply {
            setUrlWithoutDomain(url)
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    private fun titleFromElement(element: Element): String? = sequenceOf(
        element.selectFirst("img")?.attr("alt"),
        element.attr("title"),
        element.ownText(),
        element.text(),
    )
        .map { it?.cleanText().orEmpty() }
        .firstOrNull { it.isNotBlank() && !it.equals("bookmark", true) }

    // =========================== Manga Details ============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url.toNewPath()}", headers)

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val slug = response.request.url.encodedPath.substringAfterLast("/")

        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.cleanText()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" | ")
                    ?.cleanText()
                ?: slug.replace('-', ' ')

            thumbnail_url = document.selectFirst("img[src*='/comic/$slug/cover_'], img[data-src*='/comic/$slug/cover_']")
                ?.let(::imageFromElement)
                ?: cleanImageUrl(document.selectFirst("meta[property=og:image]")?.attr("abs:content"))

            description = parseDescription(document)
            genre = document.select("a[href*='genre=']")
                .map { it.text().cleanText() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()
                .ifBlank { null }
            status = parseStatus(document.body().text())
        }
    }

    private fun parseDescription(document: Document): String? {
        val paragraphs = document.select("main p, article p, p")
            .map { it.text().cleanText() }
            .filter { it.length > 40 && !it.contains("Show more", true) }
            .distinct()

        return paragraphs.maxByOrNull { it.length }
            ?: document.selectFirst("meta[name=description], meta[property=og:description]")
                ?.attr("content")
                ?.cleanText()
                ?.takeIf { it.isNotBlank() }
    }

    private fun parseStatus(text: String?): Int {
        val value = text?.lowercase().orEmpty()
        return when {
            "completed" in value -> SManga.COMPLETED
            "ongoing" in value -> SManga.ONGOING
            "hiatus" in value -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val slug = response.request.url.encodedPath.substringAfterLast("/")

        return document.select("a[href]")
            .asSequence()
            .filter { element ->
                val href = element.attr("abs:href")
                href.startsWith("$baseUrl/read/$slug/") &&
                    !href.contains("honeypot", true) &&
                    !element.text().contains("Chapter Pertama", true) &&
                    !element.text().contains("Chapter Terbaru", true)
            }
            .distinctBy { it.attr("abs:href") }
            .map(::chapterFromElement)
            .toList()
    }

    private fun chapterFromElement(element: Element): SChapter {
        val text = element.text().cleanText()
        val date = RELATIVE_DATE_REGEX.find(text)?.value
            ?: ABSOLUTE_DATE_REGEX.find(text)?.value

        val name = date?.let { text.substringBefore(it).cleanText() }
            ?.takeIf { it.isNotBlank() }
            ?: text.takeIf { it.isNotBlank() }
            ?: "Chapter"

        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            this.name = name
            date_upload = parseChapterDate(date)
        }
    }

    private fun parseChapterDate(dateString: String?): Long {
        val originalDate = dateString?.cleanText().orEmpty()
        val date = originalDate.lowercase()
        if (date.isBlank()) return 0L

        RELATIVE_DATE_REGEX.find(date)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return 0L
            val unit = match.groupValues[2]
            return Calendar.getInstance().apply {
                when (unit) {
                    "detik" -> add(Calendar.SECOND, -amount)
                    "menit" -> add(Calendar.MINUTE, -amount)
                    "jam" -> add(Calendar.HOUR_OF_DAY, -amount)
                    "hari" -> add(Calendar.DATE, -amount)
                    "minggu" -> add(Calendar.WEEK_OF_YEAR, -amount)
                    "bulan" -> add(Calendar.MONTH, -amount)
                    "tahun" -> add(Calendar.YEAR, -amount)
                }
            }.timeInMillis
        }

        return DATE_FORMATS.firstNotNullOfOrNull { it.tryParse(originalDate) } ?: 0L
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val segments = response.request.url.encodedPathSegments
        val comicSlug = segments.getOrNull(1).orEmpty()
        val chapterSlug = segments.getOrNull(2).orEmpty()

        val pages = document.select("img")
            .asSequence()
            .mapNotNull(::imageFromElement)
            .filter { it.contains("/comic/$comicSlug/$chapterSlug/") }
            .distinct()
            .toList()

        return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun imageFromElement(element: Element): String? {
        val srcset = element.attr("abs:srcset")
            .ifBlank { element.attr("srcset") }
            .substringBefore(",")
            .substringBefore(" ")

        return sequenceOf(
            element.attr("abs:data-src"),
            element.attr("abs:data-lazy-src"),
            element.attr("abs:src"),
            srcset,
        )
            .mapNotNull(::cleanImageUrl)
            .firstOrNull()
    }

    private fun cleanImageUrl(url: String?): String? {
        var imageUrl = url?.trim()?.substringBefore(" ").orEmpty()
        if (imageUrl.isBlank() || imageUrl.contains("placeholder", true) || imageUrl.contains("honeypot", true)) {
            return null
        }

        if (imageUrl.startsWith("//")) {
            imageUrl = "https:$imageUrl"
        } else if (imageUrl.startsWith("/")) {
            imageUrl = "$baseUrl$imageUrl"
        }

        imageUrl = imageUrl.substringBefore("?")

        return imageUrl
            .replace(API_IMAGE_HOST_REGEX, CDN_URL)
            .replace("$baseUrl/api/image", CDN_URL)
    }

    private fun isComicUrl(url: String): Boolean = url.startsWith("$baseUrl/comic/") &&
        !url.contains("honeypot", true)

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = vals[state].second
    }

    private class SortFilter :
        UriPartFilter(
            "Urutkan",
            arrayOf(
                "Latest Update" to "",
                "Popular" to "popular",
                "Rating" to "rating",
                "A-Z" to "az",
            ),
        )

    private class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                "Semua" to "",
                "Ongoing" to "ongoing",
                "Completed" to "completed",
                "Hiatus" to "hiatus",
            ),
        )

    private class TypeFilter :
        UriPartFilter(
            "Tipe",
            arrayOf(
                "Semua" to "",
                "Manga" to "manga",
                "Manhwa" to "manhwa",
                "Manhua" to "manhua",
                "Comic" to "comic",
                "Webtoon" to "webtoon",
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                "Semua" to "",
                "Action" to "action",
                "Adult" to "adult",
                "Adventure" to "adventure",
                "Comedy" to "comedy",
                "Drama" to "drama",
                "Ecchi" to "ecchi",
                "Fantasy" to "fantasy",
                "Full Color" to "full-color",
                "Harem" to "harem",
                "Historical" to "historical",
                "Horror" to "horror",
                "Isekai" to "isekai",
                "Josei" to "josei",
                "Magic" to "magic",
                "Manhwa" to "manhwa",
                "Martial Arts" to "martial-arts",
                "Mature" to "mature",
                "Murim" to "murim",
                "Mystery" to "mystery",
                "Netorare/NTR" to "netorare-ntr",
                "Psychological" to "psychological",
                "Regression" to "regression",
                "Reincarnation" to "reincarnation",
                "Romance" to "romance",
                "School Life" to "school-life",
                "Seinen" to "seinen",
                "Shoujo" to "shoujo",
                "Shounen" to "shounen",
                "Slice of Life" to "slice-of-life",
                "Smut" to "smut",
                "Supernatural" to "supernatural",
                "Time Travel" to "time-travel",
                "Transmigration" to "transmigration",
                "Villainess" to "villainess",
                "Webtoons" to "webtoons",
                "Yaoi" to "yaoi",
                "Yuri" to "yuri",
            ),
        )

    private fun String.toNewPath(): String = replaceFirst("/komik/", "/comic/")
        .replaceFirst("/manga/", "/comic/")

    private fun String.cleanText(): String = replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val CDN_URL = "https://hwg.imgsvrgg.site/file"
        private val API_IMAGE_HOST_REGEX = Regex("https://\\d+\\.hwago\\.xyz/api/image")
        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s*(detik|menit|jam|hari|minggu|bulan|tahun)\s*lalu""", RegexOption.IGNORE_CASE)
        private val ABSOLUTE_DATE_REGEX = Regex("""[A-Za-z]{3,9}\s+\d{1,2},\s+\d{4}""")
        private val DATE_FORMATS = listOf(
            SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH),
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH),
            SimpleDateFormat("d MMMM yyyy", Locale("id")),
        )
    }
}
