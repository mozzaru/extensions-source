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
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : HttpSource() {

    override val name = "MG Komik"
    override val baseUrl = "https://web.mgkomik.cc"
    override val lang = "id"
    override val supportsLatest = true

    override val id = 5845004992097969882L

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val headers = request.headers.newBuilder().apply {
                when {
                    url.contains("admin-ajax.php") || url.contains("wp-json") -> {
                        set("X-Requested-With", "XMLHttpRequest")
                        set("Sec-Fetch-Dest", "empty")
                        set("Sec-Fetch-Mode", "cors")
                        set("Sec-Fetch-Site", "same-origin")
                    }
                    url.contains("/uploads/") || url.contains(".jpg") || url.contains(".png") || url.contains(".webp") -> {
                        removeAll("X-Requested-With")
                        set("Sec-Fetch-Dest", "image")
                        set("Sec-Fetch-Mode", "no-cors")
                        set("Sec-Fetch-Site", "same-site")
                    }
                    else -> {
                        removeAll("X-Requested-With")
                        set("Sec-Fetch-Dest", "document")
                        set("Sec-Fetch-Mode", "navigate")
                        set("Sec-Fetch-Site", "same-origin")
                        set("Sec-Fetch-User", "?1")
                    }
                }
            }.build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .build()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36")
        set("Referer", "$baseUrl/")
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Upgrade-Insecure-Requests", "1")
    }

    // ========== POPULAR ==========

    override fun popularMangaRequest(page: Int): Request {
        val t = System.currentTimeMillis()
        return GET("$baseUrl/komik/?order_by=views&page=$page&t=$t", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".manga-card").map { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = document.selectFirst(".pagination .next, a[href*='page=']:contains(Next)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val a = element.selectFirst(".card-info a.manga-title-link")
            ?: element.selectFirst(".card-info a.manga-title")
            ?: element.selectFirst("a[href*='/komik/']")!!
        setUrlWithoutDomain(a.attr("href").trim())
        title = element.selectFirst(".manga-title")?.text()?.trim()
            ?: a.text().trim()
        thumbnail_url = element.selectFirst("img.manga-cover")?.attr("abs:src")
    }

    override fun getMangaUrl(manga: SManga): String {
        val url = manga.url.replace("/manga/", "/komik/")
        val absoluteUrl = when {
            url.startsWith("http") -> url.replace(Regex("https?://(web\\.)?(mgkomik\\.(com|cc|id)|id\\.mgkomik\\.cc)"), baseUrl)
            url.startsWith("//") -> "https:$url"
            else -> baseUrl + (if (url.startsWith("/")) "" else "/") + url
        }
        return absoluteUrl.replace(Regex("(?<!:)/{2,}"), "/")
    }

    // ========== LATEST ==========

    override fun latestUpdatesRequest(page: Int): Request {
        val t = System.currentTimeMillis()
        return GET("$baseUrl/komik/?order_by=latest&page=$page&t=$t", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ========== SEARCH ==========

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val t = System.currentTimeMillis()
        if (query.isNotBlank()) {
            return GET("$baseUrl/search/?q=${query.trim()}&page=$page&t=$t", headers)
        }

        val urlBuilder = "$baseUrl/komik/?page=$page&t=$t".toHttpUrlOrNull()!!.newBuilder()
        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> urlBuilder.addQueryParameter("order_by", filter.selected)
                is GenreFilter -> if (filter.selected.isNotBlank()) urlBuilder.addQueryParameter("filter", filter.selected)
                is StatusFilter -> if (filter.state) urlBuilder.addQueryParameter("completed", "1")
                else -> {}
            }
        }
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ========== MANGA DETAILS ==========

    override fun mangaDetailsRequest(manga: SManga): Request {
        val t = System.currentTimeMillis()
        val url = getMangaUrl(manga).toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("t", t.toString())
            ?.build()?.toString() ?: getMangaUrl(manga)
        return GET(url, headersBuilder().add("Cache-Control", "no-cache").build())
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
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

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("ul#chapterList .chapter-list-item").map { element ->
            SChapter.create().apply {
                val a = element.selectFirst("a.chapter-link")!!
                setUrlWithoutDomain(a.attr("href").trim())
                name = element.selectFirst(".chapter-number")?.text()?.trim() ?: ""
                date_upload = parseDate(element.selectFirst(".chapter-date")?.text()?.trim() ?: "")
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val url = chapter.url.replace("/manga/", "/komik/")
        val absoluteUrl = when {
            url.startsWith("http") -> url.replace(Regex("https?://(web\\.)?(mgkomik\\.(com|cc|id)|id\\.mgkomik\\.cc)"), baseUrl)
            url.startsWith("//") -> "https:$url"
            else -> baseUrl + (if (url.startsWith("/")) "" else "/") + url
        }
        return absoluteUrl.replace(Regex("(?<!:)/{2,}"), "/")
    }

    // ========== PAGE LIST ==========

    override fun pageListRequest(chapter: SChapter): Request {
        val url = getChapterUrl(chapter)
        return GET(url, headersBuilder().set("Referer", url.substringBeforeLast("/") + "/").add("Cache-Control", "no-cache").build())
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("#readingContent img[data-page]")
            .mapIndexed { idx, img ->
                Page(idx, document.location(), img.attr("abs:src"))
            }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder().apply {
            set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            set("Referer", page.url)
        }.build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    // ========== DATE PARSER ==========

    private val dateFormatterNumeric by lazy { SimpleDateFormat("dd/MM/yy", Locale("id")) }

    private val dateFormatterShort by lazy { SimpleDateFormat("dd MMM yy", Locale("id")) }

    private val dateFormatterLong by lazy { SimpleDateFormat("dd MMM yyyy", Locale("id")) }

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

        // Absolute: try "dd/MM/yy", then "dd MMM yy", then "dd MMM yyyy"
        return dateFormatterNumeric.tryParse(date)
            .takeIf { it != 0L }
            ?: dateFormatterShort.tryParse(date)
                .takeIf { it != 0L }
            ?: dateFormatterLong.tryParse(date)
    }

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
}
