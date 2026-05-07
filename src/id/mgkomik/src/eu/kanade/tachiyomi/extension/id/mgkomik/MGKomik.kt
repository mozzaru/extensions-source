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
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik :
    Madara(
        "MG Komik",
        "https://id.mgkomik.cc",
        "id",
        SimpleDateFormat("dd MMM yy", Locale.US),
    ) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val useNewChapterEndpoint = true

    override val mangaSubString = "komik"

    // =============================== Requests ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "views")
            .build()
        // HAR: first navigation has sec-fetch-site=none, no Referer, cache-control: max-age=0
        return GET(url, firstNavHeaders())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "latest")
            .build()
        return GET(url, firstNavHeaders())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (query.isNotBlank()) {
        super.searchMangaRequest(page, query, filters).addSameOriginNavHeaders()
    } else {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()

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
        GET(url.build(), firstNavHeaders())
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        super.mangaDetailsRequest(manga).addSameOriginNavHeaders()

    override fun chapterListRequest(manga: SManga): Request =
        super.chapterListRequest(manga).addSameOriginNavHeaders()

    override fun genresRequest(): Request =
        super.genresRequest().addSameOriginNavHeaders()

    /**
     * Headers for a fresh top-level navigation (no Referer, sec-fetch-site=none).
     * Matches HAR: GET /komik/?m_orderby=views
     */
    private fun firstNavHeaders() = headers.newBuilder()
        .removeAll("Referer")
        .set("Cache-Control", "max-age=0")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .build()

    /**
     * Headers for same-origin navigation (with Referer, sec-fetch-site=same-origin).
     */
    private fun Request.addSameOriginNavHeaders(): Request = newBuilder()
        .header("Referer", "$baseUrl/$mangaSubString/")
        .header("Cache-Control", "max-age=0")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-User", "?1")
        .build()

    // =============================== Headers ===============================

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", USER_AGENT)
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        set("Accept-Language", "id-ID,id;q=0.9")
        set("DNT", "1")
        set("Sec-CH-UA", "\"Chromium\";v=\"147\", \"Not.A/Brand\";v=\"8\"")
        set("Sec-CH-UA-Arch", "\"\"")
        set("Sec-CH-UA-Bitness", "\"\"")
        set("Sec-CH-UA-Full-Version", "\"147.0.7727.93\"")
        set("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"147.0.7727.93\", \"Not.A/Brand\";v=\"8.0.0.0\"")
        set("Sec-CH-UA-Mobile", "?1")
        set("Sec-CH-UA-Model", "\"RMX2103\"")
        set("Sec-CH-UA-Platform", "\"Android\"")
        set("Sec-CH-UA-Platform-Version", "\"11.0.0\"")
        set("Sec-GPC", "1")
        set("Upgrade-Insecure-Requests", "1")
        set("Priority", "u=0, i")
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val newHeaders = request.headers.newBuilder()

            val path = url.encodedPath
            when {
                path.contains("admin-ajax.php") || path.contains("wp-json") -> {
                    // AJAX requests
                    newHeaders.set("X-Requested-With", "XMLHttpRequest")
                    newHeaders.set("Sec-Fetch-Dest", "empty")
                    newHeaders.set("Sec-Fetch-Mode", "cors")
                    newHeaders.set("Sec-Fetch-Site", "same-origin")
                    newHeaders.set("Origin", baseUrl)
                    newHeaders.removeAll("Sec-Fetch-User")
                }
                path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".png") || path.endsWith(".webp") ||
                    path.contains("/thumbs/") -> {
                    // HAR shows images are sent WITHOUT any Sec-Fetch-* headers
                    newHeaders.removeAll("X-Requested-With")
                    newHeaders.removeAll("Sec-Fetch-Dest")
                    newHeaders.removeAll("Sec-Fetch-Mode")
                    newHeaders.removeAll("Sec-Fetch-Site")
                    newHeaders.removeAll("Sec-Fetch-User")
                    newHeaders.removeAll("Cache-Control")
                    newHeaders.removeAll("Priority")
                }
                else -> {
                    // Document navigation — per-request headers already applied
                    newHeaders.removeAll("X-Requested-With")
                }
            }

            chain.proceed(request.newBuilder().headers(newHeaders.build()).build())
        }
        .rateLimit(2)
        .build()

    // =============================== Selectors ===============================

    // HTML: <div class="page-item-detail manga">
    //   <div class="item-thumb"><a href="..."><img ...></a></div>
    //   <div class="item-summary">
    //     <div class="post-title font-title"><h3 class="h5"><a href="...">Title</a></h3></div>
    //   </div>
    // </div>
    override fun popularMangaSelector() = "div.page-item-detail.manga"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleLink = element.selectFirst(".post-title a")
        title = titleLink?.text()?.trim() ?: element.selectFirst("img")?.attr("alt")?.trim() ?: ""
        setUrlWithoutDomain(titleLink?.attr("abs:href").orEmpty())
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    override fun popularMangaNextPageSelector() = "div.wp-pagenavi a.page, div.wp-pagenavi a.last, div.nav-previous, nav.navigation-ajax, a.nextpostslink, a.next, .pagination a:contains(Next)"

    override val mangaDetailsSelectorTitle = ".manga-title, h1#mangaTitle, div.post-title h3, div.post-title h1, #manga-title > h1"
    override val mangaDetailsSelectorAuthor = ".meta-item:contains(Author:) .meta-value, div.author-content > a, div.manga-authors > a"
    override val mangaDetailsSelectorStatus = ".status-badge, div.summary-content, div.summary-heading:contains(Status) + div"
    override val mangaDetailsSelectorDescription = ".manga-description p, div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt"
    override val mangaDetailsSelectorThumbnail = ".manga-cover-large, div.summary_image img"
    override val mangaDetailsSelectorGenre = ".genre-tag, div.genres-content a"

    override fun chapterListSelector() = "li.chapter-list-item, li.wp-manga-chapter"

    // ================================ Dates ==================================

    // Site uses "30 Apr 26" = dd MMM yy Locale.US (confirmed from HTML)
    override fun parseChapterDate(date: String?): Long {
        date ?: return 0L
        val trimmed = date.trim()

        if (trimmed.contains("ago", ignoreCase = true) || trimmed.contains("yang lalu", ignoreCase = true)) {
            return parseRelativeDate(trimmed)
        }

        val dateFormats = listOf(
            SimpleDateFormat("dd MMM yy", Locale.US),
            SimpleDateFormat("dd MMM yyyy", Locale.US),
            SimpleDateFormat("dd/MM/yyyy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("MMMM d, yyyy", Locale.US),
        )

        for (sdf in dateFormats) {
            try {
                sdf.isLenient = false
                val parsed = sdf.parse(trimmed)
                if (parsed != null) return parsed.time
            } catch (_: ParseException) {
            }
        }

        return super.parseChapterDate(trimmed)
    }

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
        private const val CH_VERSION = "147"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH_VERSION.0.0.0 Mobile Safari/537.36"
    }
}
