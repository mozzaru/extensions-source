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
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MGKomik : HttpSource() {

    override val name = "MG Komik"
    override val baseUrl = "https://web.mgkomik.cc"
    override val lang = "id"
    override val supportsLatest = true

    override val id: Long = 5845004992097969882

    override val client = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(12, 3)
        .addInterceptor(::uaInterceptor)
        .build()

    private val dateFormat = SimpleDateFormat("dd MMM yy", Locale.US)
    private val relativeRegex = Regex("""(\d+)\s*(jam|hari|minggu|bulan)\s*lalu""")

    // ========== HEADERS ==========

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9")
        .add("Referer", "$baseUrl/")
        .add("Sec-CH-UA", "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"141\", \"Google Chrome\";v=\"141\"")
        .add("Sec-CH-UA-Mobile", "?1")
        .add("Sec-CH-UA-Platform", "\"Android\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .add("X-Requested-With", "com.android.chrome")

    private fun uaInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val newRequest = request.newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36")

        if (url.contains("admin-ajax.php") || url.contains("wp-json")) {
            newRequest.header("X-Requested-With", "XMLHttpRequest")
        }

        return chain.proceed(newRequest.build())
    }

    private fun pageHeaders(referer: String) = headersBuilder()
        .set("Referer", referer)
        .build()

    // ========== POPULAR ==========

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/komik/?order_by=trending&page=$page", headers)

    override fun popularMangaParse(response: Response) = listingParse(response)

    // ========== LATEST ==========

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/komik/?order_by=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = listingParse(response)

    // ========== SEARCH ==========

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/?q=${query.trim()}&page=$page"
        } else {
            buildFilterUrl(filters, page)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = listingParse(response)

    // ========== LISTING PARSER ==========

    private fun listingParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("div.manga-card, a.manga-card").mapNotNull { parseCard(it) }
        val hasNext = doc.selectFirst(".pagination a.page-link:containsOwn(Next)") != null
        return MangasPage(mangas, hasNext)
    }

    private fun parseCard(el: Element): SManga? {
        val url: String
        val title: String
        val cover: String

        if (el.tagName() == "a" && el.hasClass("manga-card")) {
            url = el.attr("abs:href")
            title = el.selectFirst("div.manga-title")?.text()?.trim() ?: return null
            cover = el.selectFirst("img.manga-cover")?.attr("abs:src").orEmpty()
        } else {
            val linkEl = el.selectFirst("a.manga-title-link")
                ?: el.selectFirst("a.manga-title")
                ?: return null
            title = (linkEl.selectFirst("div.manga-title")?.text() ?: linkEl.text())
                .trim().takeIf { it.isNotBlank() } ?: return null
            url = linkEl.attr("abs:href")
            cover = el.selectFirst("img.manga-cover")?.attr("abs:src").orEmpty()
        }

        return SManga.create().apply {
            this.title = title
            this.url = getUrl(url)
            thumbnail_url = cover
        }
    }

    // ========== MANGA DETAIL ==========

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${getUrl(manga.url)}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${getUrl(chapter.url)}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val titleEl = doc.selectFirst("#mangaTitle")
        val cover = doc.selectFirst("img.manga-cover-large")?.attr("abs:src").orEmpty()
        val statusText = doc.selectFirst(".meta-item.status-badge")?.text().orEmpty()
        val status = when {
            statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        val genres = doc.select("a.genre-tag").joinToString(", ") { it.text().trim() }
        val desc = doc.selectFirst(".manga-description p")?.text().orEmpty()
        val alt = titleEl?.attr("data-alt").orEmpty()
        val fullDesc = if (alt.isNotBlank()) "$desc\n\nAlt: $alt" else desc
        return SManga.create().apply {
            title = titleEl?.text()?.trim().orEmpty()
            thumbnail_url = cover
            description = fullDesc.trim()
            this.status = status
            genre = genres
        }
    }

    // ========== CHAPTER LIST ==========

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup()
        .select("#chapterList .chapter-list-item")
        .map { el ->
            val link = el.selectFirst("a.chapter-link")!!
            val num = link.selectFirst(".chapter-number")?.text().orEmpty()
            val date = link.selectFirst(".chapter-date")?.text().orEmpty()
            SChapter.create().apply {
                name = num
                url = getUrl(link.attr("abs:href"))
                chapter_number = parseChapterNum(num)
                date_upload = parseDate(date)
            }
        }

    // ========== PAGE LIST ==========

    override fun pageListRequest(chapter: SChapter): Request {
        val url = getChapterUrl(chapter)
        val mangaUrl = url.substringBefore("/chapter-") + "/"
        return GET(url, pageHeaders(mangaUrl))
    }

    override fun pageListParse(response: Response): List<Page> = response.asJsoup()
        .select(
            ".reading-content img, .chapter-content img, " +
                ".reader-area img, img.wp-manga-chapter-img",
        )
        .mapIndexed { i, img ->
            val url = img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
            Page(i, "", cleanImageUrl(url))
        }

    private fun cleanImageUrl(url: String): String = url.replace(Regex("-\\d+x\\d+(?=\\.(jpg|jpeg|png|webp))"), "")
        .replace(Regex("-scaled(?=\\.(jpg|jpeg|png|webp))"), "")

    override fun imageRequest(page: Page) = GET(page.imageUrl!!, pageHeaders("$baseUrl/"))

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ========== FILTERS ==========

    override fun getFilterList() = FilterList(
        OrderByFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    private fun buildFilterUrl(filters: FilterList, page: Int): String {
        var orderBy = "latest"
        var status = ""
        var filterVal = ""
        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> orderBy = filter.selected()
                is StatusFilter -> status = filter.selected()
                is TypeFilter -> if (filter.state != 0) filterVal = filter.selected()
                is GenreFilter -> if (filter.state != 0) filterVal = filter.selected()
                else -> {}
            }
        }
        val params = buildList {
            if (filterVal.isNotBlank()) add("filter=$filterVal")
            if (status == "completed") add("completed=1")
            if (status == "on-going") add("status=on-going")
            add("order_by=$orderBy")
            add("page=$page")
        }
        return "$baseUrl/komik/?${params.joinToString("&")}"
    }

    class OrderByFilter :
        Filter.Select<String>(
            "Urutkan",
            arrayOf("Latest", "Trending"),
        ) {
        fun selected() = values[state].lowercase()
    }

    class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("Semua", "Ongoing", "Completed"),
        ) {
        fun selected() = when (state) {
            1 -> "on-going"
            2 -> "completed"
            else -> ""
        }
    }

    class TypeFilter :
        Filter.Select<String>(
            "Type",
            arrayOf("Semua", "Manga", "Manhwa", "Manhua"),
        ) {
        fun selected() = values[state].lowercase()
    }

    class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "Semua",
                "Action", "Adaptation", "Adult", "Adventure", "Age Gap", "Animals",
                "Apocalypse", "Based on a Novel", "Comedy", "Cooking", "Crime",
                "Crossdressing", "Cultivation", "Demons", "Drama", "Dungeon", "Ecchi",
                "Fantasy", "Fighting", "Full Color", "Game", "Gender Bender", "Gore",
                "Harem", "Historical", "Historical Romance", "Horror", "Hunter",
                "Isekai", "Josei", "Kids", "Magic", "Martial Arts", "Mature",
                "Mecha", "Medical", "Military", "Monsters", "Music", "Mystery",
                "Office Workers", "OP MC", "Overpowered", "Parody", "Philosophical",
                "Politics", "Post Apocalyptic", "Psychological", "Regression",
                "Reincarnation", "Returner", "Revenge", "Reverse Harem", "Romance",
                "Royalty", "School", "School Life", "Sci-Fi", "Seinen", "Shoujo",
                "Shoujo Ai", "Shounen", "Showbiz", "Slice of Life", "Smart MC",
                "Sports", "Super Power", "Superhero", "Supernatural", "Survival",
                "System", "Thriller", "Time Travel", "Tower", "Tragedy",
                "Transmigration", "Vampire", "Video Games", "Villain", "Villainess",
                "Violence", "War", "Webtoon", "Wuxia", "Yuri",
            ),
        ) {
        fun selected() = values[state].lowercase().replace(" ", "-")
    }

    // ========== HELPERS ==========

    private fun getUrl(url: String): String = url.replace(Regex("https?://(id|web)\\.mgkomik\\.cc"), "")
        .replace(Regex("//(id|web)\\.mgkomik\\.cc"), "")
        .replace("/manga/", "/komik/")
        .let { if (it.startsWith("/")) it else "/$it" }

    private fun parseChapterNum(name: String): Float = Regex("""(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val m = relativeRegex.find(raw.lowercase())
        if (m != null) {
            val amount = m.groupValues[1].toIntOrNull() ?: return 0L
            return Calendar.getInstance().apply {
                when (m.groupValues[2]) {
                    "jam" -> add(Calendar.HOUR_OF_DAY, -amount)
                    "hari" -> add(Calendar.DAY_OF_YEAR, -amount)
                    "minggu" -> add(Calendar.WEEK_OF_YEAR, -amount)
                    "bulan" -> add(Calendar.MONTH, -amount)
                }
            }.timeInMillis
        }
        return runCatching { dateFormat.parse(raw)?.time }.getOrNull() ?: 0L
    }
}
