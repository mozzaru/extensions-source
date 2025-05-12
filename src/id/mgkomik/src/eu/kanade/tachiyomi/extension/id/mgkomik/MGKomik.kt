package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : Madara(
    "MG Komik",
    "https://mgkomik.org",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    // Menambahkan Header untuk Bypass Cloudflare
    override fun headersBuilder() = super.headersBuilder().apply {
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomString((1..20).random()))
        add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        )
        add("Referer", baseUrl)
    }

    // Membuat client OkHttp dengan interceptor untuk bypass Cloudflare
    override val client = network.cloudflareClient.newBuilder()
        .cookieJar(
            object : CookieJar {
                private val cookies = mutableListOf<Cookie>()

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookies.filter { it.matches(url) }
                }

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    this.cookies.addAll(cookies)
                }
            },
        )
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            val response: Response = chain.proceed(
                request.newBuilder().headers(headers).build(),
            )

            response.headers("Set-Cookie").forEach { cookie ->
                // Kamu bisa mengelola cookies yang diterima dan menyimpannya jika diperlukan
            }

            response
        }
        .rateLimit(
            9,
            2,
        )
        .build()

    // ================================== Popular ======================================

    override fun popularMangaNextPageSelector() = ".wp-pagenavi span.current + a"

    // ================================== Latest =======================================

    override fun latestUpdatesRequest(page: Int): Request =
        if (useLoadMoreRequest()) {
            loadMoreRequest(page, popular = false)
        } else {
            GET("$baseUrl/$mangaSubString/${searchPage(page)}", headers)
        }

    // ================================== Search =======================================

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        filters.forEach { filter ->
            when (filter) {
                is GenreContentFilter -> {
                    val url = filter.toUriPart()
                    if (url.isBlank()) {
                        return@forEach
                    }
                    return GET(filter.toUriPart(), headers)
                }

                else -> {}
            }
        }
        return super.searchRequest(page, query, filters)
    }

    override fun searchMangaSelector() =
        "${super.searchMangaSelector()}, .page-listing-item .page-item-detail"

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

    private class GenreContentFilter(
        title: String,
        options: List<Pair<String, String>>,
    ) : UriPartFilter(
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