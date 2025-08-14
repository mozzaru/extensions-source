package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {

    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"
    override val id = 5845004992097969882

    // ===== Random X-Requested-With =====
    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { charset.random() }.joinToString("")
    }
    private val randomHeader = generateRandomString(Random.nextInt(13, 21))

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Sec-Fetch-User", "?1")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomHeader)
    }

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(9, 2)
        .build()

    // ===== Filter selector supaya “Baca di Web” tidak ikut =====
    override fun popularMangaSelector() =
        "div.page-item-detail:has(a[href*='/$mangaSubString/'])"

    override fun latestUpdatesSelector() =
        "div.page-item-detail:has(a[href*='/$mangaSubString/'])"

    // ===== Null-safe parsing =====
    override fun popularMangaFromElement(element: Element): SManga {
        val a = element.selectFirst("a[href*='/$mangaSubString/']") ?: return SManga.create()
        return SManga.create().apply {
            setUrlWithoutDomain(a.attr("abs:href"))
            title = a.attr("title").ifBlank { a.text() }
            thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
        }
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val a = element.selectFirst("a[href*='/$mangaSubString/']") ?: return SManga.create()
        return SManga.create().apply {
            setUrlWithoutDomain(a.attr("abs:href"))
            title = a.attr("title").ifBlank { a.text() }
            thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
        }
    }

    // ===== Filters & Genres =====
    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }
        val filters = super.getFilterList().list.toMutableList()
        filters += if (genresList.isNotEmpty()) {
            listOf(
                Filter.Separator(),
                GenreContentFilter(
                    title = intl["genre_filter_title"],
                    options = genresList.map { it.name to it.id },
                ),
            )
        } else {
            listOf(
                Filter.Separator(),
                Filter.Header(intl["genre_missing_warning"]),
            )
        }
        return FilterList(filters)
    }

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) :
        UriPartFilter(title, options.toTypedArray())

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a").map { a ->
            Genre(a.text(), a.absUrl("href"))
        }
        return genres
    }
}
