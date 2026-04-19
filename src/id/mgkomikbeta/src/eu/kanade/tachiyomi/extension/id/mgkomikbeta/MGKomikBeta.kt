package eu.kanade.tachiyomi.extension.id.mgkomikbeta

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import keiyoushi.utils.tryParse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomikBeta : ParsedHttpSource() {

    override val name = "MG Komik Beta"
    override val baseUrl = "https://web.mgkomik.cc"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder() = super.headersBuilder().apply {
        set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        )
        set("Referer", baseUrl)
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    // ========== POPULAR ==========

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/komik/?order_by=views&page=$page", headers)

    override fun popularMangaSelector() = ".manga-card"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val a = element.selectFirst(".card-info a.manga-title")!!
        setUrlWithoutDomain(a.attr("abs:href"))
        title = a.text().trim()
        thumbnail_url = element.selectFirst("img.manga-cover")?.attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = ".pagination .next, a[href*='page=']:contains(Next)"

    // ========== LATEST ==========

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/komik/?order_by=latest&page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ========== SEARCH ==========

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search/?q=${query.trim()}&page=$page", headers)
        }

        val url = StringBuilder("$baseUrl/komik/?page=$page")
        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> url.append("&order_by=${filter.selected}")
                is GenreFilter -> if (filter.selected.isNotBlank()) url.append("&filter=${filter.selected}")
                is StatusFilter -> if (filter.state) url.append("&completed=1")
                else -> {}
            }
        }
        return GET(url.toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ========== FILTERS ==========

    override fun getFilterList() = FilterList(
        Filter.Header("Filter tidak berlaku saat pencarian teks"),
        OrderFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    class OrderFilter :
        Filter.Select<String>(
            "Urutkan",
            arrayOf("Terbaru", "Terpopuler"),
        ) {
        val selected get() = if (state == 0) "latest" else "views"
    }

    class StatusFilter : Filter.CheckBox("Hanya Completed")

    class GenreFilter :
        Filter.Select<String>(
            "Genre / Type",
            arrayOf(
                "Semua", "Manga", "Manhua", "Manhwa",
                "Action", "Adaptation", "Adult", "Adventure", "Age Gap",
                "Animals", "Another Chance", "Apocalypse", "Based On A Novel",
                "Comedy", "Cooking", "Drama", "Dungeons", "Ecchi", "Fantasy",
                "Game", "Gender Bender", "Harem", "Historical", "Horror",
                "Isekai", "Josei", "Magic", "Martial Arts", "Mature",
                "Mecha", "Military", "Monster Girls", "Mystery", "Noir",
                "Office Workers", "Overpowered MC", "Psychological", "Reincarnation",
                "Romance", "School Life", "Sci Fi", "Seinen", "Shoujo",
                "Shounen", "Slice Of Life", "Sports", "Super Powers",
                "Supernatural", "Survival", "System", "Time Travel",
                "Tragedy", "Vampires", "Video Games", "Villainess", "Webtoons",
                "Zombies",
            ),
        ) {
        private val slugValues = arrayOf(
            "", "manga", "manhua", "manhwa",
            "action", "adaptation", "adult", "adventure", "age-gap",
            "animals", "another-chance", "apocalypse", "based-on-a-novel",
            "comedy", "cooking", "drama", "dungeons", "ecchi", "fantasy",
            "game", "gender-bender", "harem", "historical", "horror",
            "isekai", "josei", "magic", "martial-arts", "mature",
            "mecha", "military", "monster-girls", "mystery", "noir",
            "office-workers", "overpowered-mc", "psychological", "reincarnation",
            "romance", "school-life", "sci-fi", "seinen", "shoujo",
            "shounen", "slice-of-life", "sports", "super-powers",
            "supernatural", "survival", "system", "time-travel",
            "tragedy", "vampires", "video-games", "villainess", "webtoons",
            "zombies",
        )

        val selected get() = slugValues[state]
    }

    // ========== MANGA DETAILS ==========

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1#mangaTitle")?.ownText()?.trim() ?: ""

        val altTitle = document.selectFirst("h1#mangaTitle")?.attr("data-alt")?.trim()
        val desc = document.selectFirst(".manga-description p")?.text()?.trim() ?: ""

        description = if (!altTitle.isNullOrBlank()) {
            "$desc\n\nAlternative Title: $altTitle"
        } else {
            desc
        }

        author = document.select(".manga-meta .meta-item")
            .firstOrNull { it.text().startsWith("Author:") }
            ?.text()?.removePrefix("Author:")?.trim()

        genre = document.select(".genre-list .genre-tag")
            .joinToString { it.text().trim() }

        status = when (document.selectFirst(".status-badge")?.text()?.lowercase()?.trim()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        thumbnail_url = document.selectFirst("img.manga-cover-large")?.attr("abs:src")
    }

    // ========== CHAPTER LIST ==========

    override fun chapterListSelector() = "ul#chapterList .chapter-list-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val a = element.selectFirst("a.chapter-link")!!
        setUrlWithoutDomain(a.attr("abs:href"))
        name = element.selectFirst(".chapter-number")?.text()?.trim() ?: ""
        date_upload = parseDate(element.selectFirst(".chapter-date")?.text()?.trim() ?: "")
    }

    // ========== DATE PARSER ==========

    private val dateFormatterShort by lazy { SimpleDateFormat("dd MMM yy", Locale.ENGLISH) }

    private val dateFormatterLong by lazy { SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH) }

    private fun parseDate(date: String): Long {
        val lower = date.lowercase().trim()
        if (lower.isBlank()) return 0L

        // Relative: Indonesian ("lalu", "hari ini") and English ("ago", "today")
        if (lower.contains("lalu") || lower.contains("ago") ||
            lower == "hari ini" || lower == "today"
        ) {
            val value = Regex("""(\d+)""").find(lower)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 1L
            val now = System.currentTimeMillis()
            return when {
                lower.contains("detik") || lower.contains("second") -> now - value * 1_000L
                lower.contains("menit") || lower.contains("minute") -> now - value * 60_000L
                lower.contains("jam") || lower.contains("hour") -> now - value * 3_600_000L
                lower.contains("hari") || lower.contains("day") -> now - value * 86_400_000L
                lower.contains("minggu") || lower.contains("week") -> now - value * 7 * 86_400_000L
                lower.contains("bulan") || lower.contains("month") -> now - value * 30L * 86_400_000L
                lower.contains("tahun") || lower.contains("year") -> now - value * 365L * 86_400_000L
                else -> now
            }
        }

        // Absolute: try "dd MMM yy" first, then "dd MMM yyyy"
        return dateFormatterShort.tryParse(date)
            .takeIf { it != 0L }
            ?: dateFormatterLong.tryParse(date)
    }

    // ========== PAGE LIST ==========

    override fun pageListParse(document: Document): List<Page> = document.select("#readingContent img[data-page]")
        .mapIndexed { idx, img ->
            Page(idx, document.location(), img.attr("abs:src"))
        }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder().apply {
            set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            set("Referer", page.url)
            set("Origin", baseUrl)
        }.build()
        return GET(page.imageUrl!!, imgHeaders)
    }
}
