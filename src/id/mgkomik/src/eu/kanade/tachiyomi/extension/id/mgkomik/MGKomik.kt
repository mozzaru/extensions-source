package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Request
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
        add("X-Requested-With", randomString((1..20).random()))
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

    // Selector untuk manga di halaman popular - coba berbagai kemungkinan
    override fun popularMangaSelector() = "div.page-item-detail, .item-summary, .post-title, .item-thumb, article.post"

    override fun popularMangaNextPageSelector() = ".wp-pagenavi span.current + a, .nav-previous a, .pagination a.next"

    // Fix untuk popular manga element parsing
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            // Coba berbagai selector yang mungkin ada untuk link
            val linkElement = selectFirst("h3 a, h4 a, .post-title a, div.item-thumb a, a[href*=komik], a[href*=manga]")
                ?: selectFirst("a") // fallback ke a tag pertama
            
            if (linkElement != null) {
                val href = linkElement.attr("abs:href")
                if (href.isNotEmpty()) {
                    manga.setUrlWithoutDomain(href)
                }
                
                // Coba ambil title dari berbagai sumber
                manga.title = linkElement.attr("title").ifEmpty { 
                    linkElement.text().ifEmpty {
                        selectFirst("h3, h4, .post-title")?.text() ?: "Unknown Title"
                    }
                }
            } else {
                // Jika tidak ada link, cari title saja
                manga.title = selectFirst("h3, h4, .post-title")?.text() ?: "Unknown Title"
                manga.url = ""
            }

            // Cari thumbnail
            selectFirst("img")?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
        }

        return manga
    }

    // ================================== Latest =======================================

    // Selector untuk manga di halaman latest
    override fun latestUpdatesSelector() = "div.page-item-detail, .item-summary, .post-title, .item-thumb, article.post"

    override fun latestUpdatesNextPageSelector() = ".wp-pagenavi span.current + a, .nav-previous a, .pagination a.next"

    override fun latestUpdatesRequest(page: Int): Request =
        if (useLoadMoreRequest()) {
            loadMoreRequest(page, popular = false)
        } else {
            GET("$baseUrl/$mangaSubString/${searchPage(page)}", headers)
        }

    // Fix untuk latest updates jika menggunakan struktur yang berbeda
    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element) // Menggunakan parser yang sama
    }

    // ================================== Search =======================================

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        filters.forEach { filter ->
            when (filter) {
                is GenreContentFilter -> {
                    val url = filter.toUriPart()
                    if (url.isNotBlank()) {
                        return GET(url, headers)
                    }
                }
                else -> {}
            }
        }
        return super.searchRequest(page, query, filters)
    }

    override fun searchMangaSelector() = "${super.searchMangaSelector()}, .page-listing-item .page-item-detail"

    override fun searchMangaNextPageSelector() = "a.page.larger"

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
