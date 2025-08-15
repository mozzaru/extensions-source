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
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            selectFirst("div.item-thumb a")!!.let {
                manga.setUrlWithoutDomain(it.attr("abs:href"))
                manga.title = it.attr("title")
            }

            selectFirst("img")?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
        }

        return manga
    }

    // ================================ Latest Updates ================================
    
    // Fix for latest updates parsing - use specific selector for this site
    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            // Find the link with title attribute
            selectFirst(".item-thumb a")?.let { linkElement ->
                manga.setUrlWithoutDomain(linkElement.attr("abs:href"))
                manga.title = linkElement.attr("title")
            } ?: run {
                // Fallback: try to get from title in .post-title
                selectFirst(".post-title a")?.let { titleElement ->
                    manga.setUrlWithoutDomain(titleElement.attr("abs:href"))
                    manga.title = titleElement.text()
                }
            }

            // Get thumbnail
            selectFirst("img")?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
        }

        return manga
    }

    // Override latest updates request to ensure correct URL
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/?page=$page", headers)
    
    // Override latest updates parsing to use correct selector
    override fun latestUpdatesParse(response: okhttp3.Response): MangasPage {
        val document = response.asJsoup()
        
        val mangas = document.select(".page-listing-item .page-item-detail").mapNotNull { element ->
            try {
                latestUpdatesFromElement(element)
            } catch (e: Exception) {
                null
            }
        }
        
        val hasNextPage = document.select(".wp-pagenavi .page.larger").isNotEmpty() ||
                         document.select(".wp-pagenavi a[aria-label='Last Page']").isNotEmpty()
        
        return MangasPage(mangas, hasNextPage)
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

    // =============================== Search ================================

    // Override search manga from element to use same logic as latest updates
    override fun searchMangaFromElement(element: Element): SManga {
        return latestUpdatesFromElement(element) // Use same logic as latest updates
    }
    
    // Override search parsing to use correct selector if needed
    override fun searchMangaParse(response: okhttp3.Response): MangasPage {
        val document = response.asJsoup()
        
        // Try different selectors based on the search result structure
        val searchSelector = when {
            document.select(".c-tabs-item .page-listing-item .page-item-detail").isNotEmpty() -> 
                ".c-tabs-item .page-listing-item .page-item-detail"
            document.select(".page-listing-item .page-item-detail").isNotEmpty() -> 
                ".page-listing-item .page-item-detail"
            else -> ".page-item-detail" // fallback
        }
        
        val mangas = document.select(searchSelector).mapNotNull { element ->
            try {
                searchMangaFromElement(element)
            } catch (e: Exception) {
                null
            }
        }
        
        val hasNextPage = document.select(".wp-pagenavi .page.larger").isNotEmpty() ||
                         document.select(".wp-pagenavi a[aria-label='Last Page']").isNotEmpty()
        
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Utilities ==============================

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charPool.random() }.joinToString("")
    }
}
