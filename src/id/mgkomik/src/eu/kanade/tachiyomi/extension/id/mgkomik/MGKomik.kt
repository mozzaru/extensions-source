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

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomString((1..20).random())) // added for webview, and removed in interceptor for normal use
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(9, 2)
        .build()

    // ================================== Popular ======================================

    // overriding to change title selector and manga url selector
    // SAFER: avoid !! and add fallback selectors based on observed HTML
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            // try multiple selectors (observed: div.popular-img.widget-thumbnail, div.item-thumb, etc.)
            val link = selectFirst("div.item-thumb a")
                ?: selectFirst("div.popular-img a")
                ?: selectFirst(".widget-thumbnail a")
                ?: selectFirst(".related-reading-img a")
                ?: selectFirst("a")

            if (link == null) {
                // nothing found — return empty SManga to avoid NPE
                return manga
            }

            manga.setUrlWithoutDomain(link.attr("abs:href"))
            manga.title = link.attr("title").ifEmpty { link.text().trim() }

            // image can be directly under element or inside the link
            val img = selectFirst("img") ?: link.selectFirst("img")
            img?.let { manga.thumbnail_url = imageFromElement(it) }
        }

        return manga
    }

    // ================================ Latest / Terbaru =================================
    // Add tolerant parser for recent/latest widgets (matches uploaded outerHtml)
    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            val link = selectFirst("div.popular-img a")
                ?: selectFirst("div.item-thumb a")
                ?: selectFirst(".widget-thumbnail a")
                ?: selectFirst(".related-reading-img a")
                ?: selectFirst("a")

            if (link == null) return manga

            manga.setUrlWithoutDomain(link.attr("abs:href"))
            manga.title = link.attr("title").ifEmpty { link.text().trim() }

            val img = selectFirst("img") ?: link.selectFirst("img")
            img?.let { manga.thumbnail_url = imageFromElement(it) }
        }

        return manga
    }

    // ================================ Chapters ================================

    override val chapterUrlSuffix = ""

    // ================================ Filters ================================

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

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) : UriPartFilter(
        title,
        options.toTypedArray(),
    )

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a").map { a ->
            Genre(a.text(), a.absUrl("href"))
        }
        return genres
    }

    // =============================== Utilities ==============================

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charPool.random() }.joinToString("")
    }
}
