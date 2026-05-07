package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
                is OrderByFilter -> if (filter.state != 0) url.addQueryParameter("m_orderby", filter.toUriPart())
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

    // Chapter AJAX endpoint with correct same-origin headers
    override fun xhrChaptersRequest(mangaUrl: String): Request =
        POST(
            "$mangaUrl/ajax/chapters/",
            headers.newBuilder()
                .set("Referer", mangaUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Sec-Fetch-Dest", "empty")
                .set("Sec-Fetch-Mode", "cors")
                .set("Sec-Fetch-Site", "same-origin")
                .removeAll("Sec-Fetch-User")
                .build(),
        )

    // =============================== Headers ===============================

    /** Fresh top-level navigation — no Referer, sec-fetch-site=none */
    private fun firstNavHeaders() = headers.newBuilder()
        .removeAll("Referer")
        .set("Cache-Control", "max-age=0")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .build()

    /** Same-origin navigation — with Referer, sec-fetch-site=same-origin */
    private fun Request.addSameOriginNavHeaders(): Request = newBuilder()
        .header("Referer", "$baseUrl/$mangaSubString/")
        .header("Cache-Control", "max-age=0")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-User", "?1")
        .build()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", USER_AGENT)
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        set("Accept-Language", "id-ID,id;q=0.9")
        set("DNT", "1")
        set("Sec-CH-UA", "\"Chromium\";v=\"$CH_VERSION\", \"Not.A/Brand\";v=\"8\"")
        set("Sec-CH-UA-Arch", "\"\"")
        set("Sec-CH-UA-Bitness", "\"\"")
        set("Sec-CH-UA-Full-Version", "\"$CH_VERSION.0.7727.93\"")
        set("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"$CH_VERSION.0.7727.93\", \"Not.A/Brand\";v=\"8.0.0.0\"")
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
            val path = request.url.encodedPath
            val newHeaders = request.headers.newBuilder()

            when {
                path.contains("admin-ajax.php") || path.contains("wp-json") || path.contains("/ajax/") -> {
                    // AJAX / chapter endpoint
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
                    // Images — no Sec-Fetch-* headers per HAR
                    newHeaders.removeAll("X-Requested-With")
                    newHeaders.removeAll("Sec-Fetch-Dest")
                    newHeaders.removeAll("Sec-Fetch-Mode")
                    newHeaders.removeAll("Sec-Fetch-Site")
                    newHeaders.removeAll("Sec-Fetch-User")
                    newHeaders.removeAll("Cache-Control")
                    newHeaders.removeAll("Priority")
                }
                else -> {
                    // Document navigation
                    newHeaders.removeAll("X-Requested-With")
                }
            }

            chain.proceed(request.newBuilder().headers(newHeaders.build()).build())
        }
        .rateLimit(3) // raised from 2 → 3 for faster chapter + cover loading
        .build()

    // =============================== Selectors ===============================

    override fun popularMangaSelector() = "div.page-item-detail.manga"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleLink = element.selectFirst(".post-title a")
        title = titleLink?.text()?.trim() ?: element.selectFirst("img")?.attr("alt")?.trim() ?: ""
        setUrlWithoutDomain(titleLink?.attr("abs:href").orEmpty())
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    // Site uses wp-pagenavi — only selectors that actually exist in the HTML
    override fun popularMangaNextPageSelector() = "div.wp-pagenavi a.page, div.wp-pagenavi a.last"

    override val mangaDetailsSelectorTitle = ".manga-title, h1#mangaTitle, div.post-title h3, div.post-title h1, #manga-title > h1"
    override val mangaDetailsSelectorAuthor = ".meta-item:contains(Author:) .meta-value, div.author-content > a, div.manga-authors > a"
    override val mangaDetailsSelectorStatus = ".status-badge, div.summary-content, div.summary-heading:contains(Status) + div"
    override val mangaDetailsSelectorDescription = ".manga-description p, div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt"
    override val mangaDetailsSelectorThumbnail = ".manga-cover-large, div.summary_image img"
    override val mangaDetailsSelectorGenre = ".genre-tag, div.genres-content a"

    override fun chapterListSelector() = "li.chapter-list-item, li.wp-manga-chapter"

    // =============================== Images =================================

    // Override to pick the smallest srcset image for faster thumbnail loading
    override fun imageFromElement(element: Element): String? = when {
        element.hasAttr("data-src") -> element.attr("abs:data-src")
        element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
        element.hasAttr("data-cfsrc") -> element.attr("abs:data-cfsrc")
        element.hasAttr("srcset") -> element.attr("srcset")
            .split(",")
            .mapNotNull { entry ->
                val parts = entry.trim().split(" ")
                val url = parts.firstOrNull()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val width = parts.lastOrNull()?.removeSuffix("w")?.toIntOrNull() ?: 0
                url to width
            }
            .minByOrNull { it.second } // smallest resolution = fastest thumbnail
            ?.first
        else -> element.attr("abs:src")
    }

    // ================================ Dates ==================================

    // Site date format: "30 Apr 26" = dd MMM yy (Locale.US)
    override fun parseChapterDate(date: String?): Long {
        date ?: return 0L
        val trimmed = date.trim()

        if (trimmed.contains("ago", ignoreCase = true) || trimmed.contains("yang lalu", ignoreCase = true)) {
            return parseRelativeDate(trimmed)
        }

        val formats = listOf(
            SimpleDateFormat("dd MMM yy", Locale.US),
            SimpleDateFormat("dd MMM yyyy", Locale.US),
            SimpleDateFormat("dd/MM/yyyy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("MMMM d, yyyy", Locale.US),
        )

        for (sdf in formats) {
            try {
                sdf.isLenient = false
                sdf.parse(trimmed)?.let { return it.time }
            } catch (_: ParseException) {}
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
        UriPartFilter(title, options.toTypedArray())

    override fun parseGenres(document: Document): List<Genre> = buildList {
        add(Genre("All", ""))
        addAll(document.select(".row.genres li a").map { Genre(it.text(), it.absUrl("href")) })
    }

    companion object {
        private const val CH_VERSION = "147"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH_VERSION.0.0.0 Mobile Safari/537.36"
    }
}
