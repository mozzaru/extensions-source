package eu.kanade.tachiyomi.extension.id.mgkomikbeta

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
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

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/komik/?filter=&order_by=views&page=$page", headers)

    override fun popularMangaSelector() = ".listupd .bs, .listupd .utao, .grid-item, .manga-card"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val a = element.selectFirst("a[href*='/komik/']")!!
        setUrlWithoutDomain(a.attr("abs:href"))
        title = element.selectFirst(".title, h2, h3, .tt")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = ".pagination .next, a:contains(Next), .next-page"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/komik/?filter=&order_by=latest&page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET("$baseUrl/search/?q=${query.trim()}&page=$page", headers)

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1#mangaTitle")?.ownText()?.trim() ?: ""

        val altTitle = document.selectFirst("h1#mangaTitle")?.attr("data-alt")?.trim()
        val desc = document.selectFirst(".manga-description p")?.text()?.trim() ?: ""

        description = if (!altTitle.isNullOrBlank()) {
            "Alternative Title: $altTitle\n\n$desc"
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

    override fun chapterListSelector() = "ul#chapterList .chapter-list-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val a = element.selectFirst("a.chapter-link")!!
        setUrlWithoutDomain(a.attr("abs:href"))
        name = element.selectFirst(".chapter-number")?.text()?.trim() ?: ""
        date_upload = parseDate(element.selectFirst(".chapter-date")?.text()?.trim() ?: "")
    }

    private fun parseDate(date: String): Long {
        val lower = date.lowercase().trim()
        val value = Regex("""(\d+)""").find(lower)?.groupValues?.get(1)?.toLongOrNull()
        if (value != null) {
            val now = System.currentTimeMillis()
            return when {
                lower.contains("detik") -> now - value * 1_000
                lower.contains("menit") -> now - value * 60 * 1_000
                lower.contains("jam") -> now - value * 3_600 * 1_000
                lower.contains("hari") -> now - value * 86_400 * 1_000
                lower.contains("minggu") -> now - value * 7 * 86_400 * 1_000
                lower.contains("bulan") -> now - value * 30L * 86_400 * 1_000
                lower.contains("tahun") -> now - value * 365L * 86_400 * 1_000
                else -> 0L
            }
        }
        return try {
            SimpleDateFormat("dd MMM yy", Locale("id")).parse(date)?.time ?: 0L
        } catch (_: Exception) {
            try {
                SimpleDateFormat("dd MMM yyyy", Locale("id")).parse(date)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

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
