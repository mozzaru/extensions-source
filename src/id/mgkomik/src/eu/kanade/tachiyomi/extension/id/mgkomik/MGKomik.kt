package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {

    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
        add("User-Agent", CloudflareBypassHelper.USER_AGENT)
        add("Referer", "$baseUrl/")
        add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(9, 2)
        .build()

    // ==================== POPULER ====================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/komik/page/$page/?orderby=popular", headers)

    override fun popularMangaParse(response: okhttp3.Response): MangasPage {
        val document = cloudflareBypassedDocument(client, response.request.url.toString())
        val mangas = popularMangaSelector().let { selector ->
            document.select(selector).map { element -> popularMangaFromElement(element) }
        }
        val hasNextPage = document.select(popularMangaNextPageSelector()).isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun popularMangaNextPageSelector() = ".wp-pagenavi span.current + a"

    // ==================== TERBARU ====================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/${searchPage(page)}", headers)

    override fun latestUpdatesParse(response: okhttp3.Response): MangasPage {
        val document = cloudflareBypassedDocument(client, response.request.url.toString())
        val mangas = latestUpdatesSelector().let { selector ->
            document.select(selector).map { element -> latestUpdatesFromElement(element) }
        }
        val hasNextPage = document.select(latestUpdatesNextPageSelector()).isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesNextPageSelector() = ".wp-pagenavi span.current + a"

    // ==================== PENCARIAN ====================

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        filters.forEach { filter ->
            if (filter is GenreContentFilter) {
                val url = filter.toUriPart()
                if (url.isNotBlank()) return GET(url, headers)
            }
        }
        return super.searchRequest(page, query, filters)
    }

    override fun searchMangaParse(response: okhttp3.Response): MangasPage {
        val document = cloudflareBypassedDocument(client, response.request.url.toString())
        val mangas = searchMangaSelector().let { selector ->
            document.select(selector).map { element -> searchMangaFromElement(element) }
        }
        val hasNextPage = document.select(searchMangaNextPageSelector()).isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaSelector() = "${super.searchMangaSelector()}, .page-listing-item .page-item-detail"
    override fun searchMangaNextPageSelector() = "a.page.larger"

    // ==================== DETAIL MANGA ====================

    override val chapterUrlSuffix = ""

    override fun mangaDetailsParse(document: Document): SManga {
        val url = document.location()
        val bypassedDoc = cloudflareBypassedDocument(client, url)
        return super.mangaDetailsParse(bypassedDoc)
    }

    // ==================== GENRE / FILTER ====================

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters = super.getFilterList().list.toMutableList()
        filters += if (genresList.isNotEmpty()) {
            listOf(
                Filter.Separator(),
                GenreContentFilter(
                    title = intl["genre_filter_title"],
                    options = genresList.map { it.name to it.id },
                )
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
        title, options.toTypedArray()
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

    // ==================== GAMBAR ====================

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headersBuilder().apply {
            add("Referer", "$baseUrl/")
        }.build())
    }

    // ==================== CLOUDFLARE BYPASS ====================

    private fun cloudflareBypassedDocument(client: OkHttpClient, url: String): Document {
        val html = resolveCloudflare(client, url)
        return Jsoup.parse(html, url)
    }
}