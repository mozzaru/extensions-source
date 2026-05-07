package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik :
    Madara(
        "MG Komik",
        "https://id.mgkomik.cc",
        "id",
        SimpleDateFormat("dd MMM yyyy", Locale("id")),
    ) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    // =============================== Requests ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString/${if (page > 1) "page/$page/" else ""}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "views")
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return GET(url, headers.newBuilder().set("Referer", "$baseUrl/").build())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString/${if (page > 1) "page/$page/" else ""}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "latest")
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return GET(url, headers.newBuilder().set("Referer", "$baseUrl/").build())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (query.isNotBlank()) {
        val request = super.searchMangaRequest(page, query, filters)
        val url = request.url.newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        request.newBuilder()
            .url(url)
            .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
            .build()
    } else {
        val url = "$baseUrl/$mangaSubString/${if (page > 1) "page/$page/" else ""}".toHttpUrl().newBuilder()
        url.addQueryParameter("t", System.currentTimeMillis().toString())

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("m_orderby", filter.toUriPart())
                    }
                }
                else -> {}
            }
        }
        GET(url.build(), headers.newBuilder().set("Referer", "$baseUrl/").build())
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val request = super.mangaDetailsRequest(manga)
        val url = request.url.newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return request.newBuilder()
            .url(url)
            .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
            .build()
    }

    override fun chapterListRequest(manga: SManga): Request {
        val request = super.chapterListRequest(manga)
        val url = request.url.newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return request.newBuilder()
            .url(url)
            .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
            .build()
    }

    override fun genresRequest(): Request {
        val request = super.genresRequest()
        val url = request.url.newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return request.newBuilder()
            .url(url)
            .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
            .build()
    }

    // =============================== Headers ===============================

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", USER_AGENT)
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "id-ID,id;q=0.9")
        set("DNT", "1")
        set("Sec-CH-UA", "\"Chromium\";v=\"$CH_VERSION\", \"Not.A/Brand\";v=\"8\"")
        set("Sec-CH-UA-Mobile", "?1")
        set("Sec-CH-UA-Platform", "\"Android\"")
        set("Sec-GPC", "1")
        set("Upgrade-Insecure-Requests", "1")
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val headers = request.headers.newBuilder()

            val path = url.encodedPath
            if (path.contains("admin-ajax.php") || path.contains("wp-json")) {
                headers.set("X-Requested-With", "XMLHttpRequest")
                headers.set("Sec-Fetch-Dest", "empty")
                headers.set("Sec-Fetch-Mode", "cors")
                headers.set("Sec-Fetch-Site", "same-origin")
                headers.set("Origin", baseUrl)
            } else if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") || path.contains("photon")) {
                headers.removeAll("X-Requested-With")
                headers.set("Sec-Fetch-Dest", "image")
                headers.set("Sec-Fetch-Mode", "no-cors")
                headers.set("Sec-Fetch-Site", "cross-site")
            } else {
                // Documents - Identify as pure browser navigation to avoid detection
                headers.removeAll("X-Requested-With")
                headers.set("Sec-Fetch-Dest", "document")
                headers.set("Sec-Fetch-Mode", "navigate")
                headers.set("Sec-Fetch-Site", if (request.header("Referer") != null) "same-origin" else "none")
                headers.set("Sec-Fetch-User", "?1")
                headers.set("Priority", "u=0, i")
                headers.set("Cache-Control", "no-cache")
            }

            chain.proceed(request.newBuilder().headers(headers.build()).build())
        }
        .rateLimit(2)
        .build()

    // =============================== Selectors ===============================

    override fun popularMangaSelector() = "div.page-item-detail:not(:has(a[href*='bilibilicomics.com'])), .manga__item, .post-item"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleElement = element.selectFirst(".manga-title a, .post-title a, h3 a, h2 a, a:has(h3), a:has(h2)")
        title = titleElement?.text()?.trim() ?: element.select("img").attr("alt").trim()
        setUrlWithoutDomain(titleElement?.attr("abs:href").orEmpty())
        thumbnail_url = imageFromElement(element.selectFirst("img")!!)
    }

    override val mangaDetailsSelectorTitle = ".manga-title, h1#mangaTitle, div.post-title h3, div.post-title h1, #manga-title > h1"
    override val mangaDetailsSelectorAuthor = ".meta-item:contains(Author:) .meta-value, div.author-content > a, div.manga-authors > a"
    override val mangaDetailsSelectorStatus = ".status-badge, div.summary-content, div.summary-heading:contains(Status) + div"
    override val mangaDetailsSelectorDescription = ".manga-description p, div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt"
    override val mangaDetailsSelectorThumbnail = ".manga-cover-large, div.summary_image img"
    override val mangaDetailsSelectorGenre = ".genre-tag, div.genres-content a"

    override fun chapterListSelector() = "li.chapter-list-item, li.wp-manga-chapter"

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

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) :
        UriPartFilter(
            title,
            options.toTypedArray(),
        )

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a").map { a ->
            Genre(a.text(), a.absUrl("href"))
        }
        return genres
    }

    companion object {
        private const val CH_VERSION = "141"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH_VERSION.0.0.0 Mobile Safari/537.36"
    }
}
