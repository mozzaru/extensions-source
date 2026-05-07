package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
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
            .addQueryParameter("m_orderby", "trending")
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

    override fun xhrChaptersRequest(mangaUrl: String): Request =
        POST(
            "$mangaUrl/ajax/chapters/",
            headers.newBuilder()
                .set("Referer", mangaUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Sec-Fetch-Dest", "empty")
                .set("Sec-Fetch-Mode", "cors")
                .set("Sec-Fetch-Site", "same-origin")
                .set("Origin", baseUrl)
                .removeAll("Sec-Fetch-User")
                .removeAll("Upgrade-Insecure-Requests")
                .build(),
        )

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterUrl = chapter.url.let {
            if (it.startsWith("http")) it else "$baseUrl$it"
        }
        val mangaUrl = chapterUrl.trimEnd('/')
            .substringBeforeLast("/")
            .trimEnd('/') + "/"

        return GET(
            chapterUrl,
            headers.newBuilder()
                .set("Referer", mangaUrl)
                .set("Cache-Control", "max-age=0")
                .set("Sec-Fetch-Dest", "document")
                .set("Sec-Fetch-Mode", "navigate")
                .set("Sec-Fetch-Site", "same-origin")
                .set("Sec-Fetch-User", "?1")
                .build(),
        )
    }

    // =============================== Headers ================================

    private fun firstNavHeaders() = headers.newBuilder()
        .removeAll("Referer")
        .set("Cache-Control", "max-age=0")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .build()

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
        set("Sec-CH-UA", "\"Chromium\";v=\"$CH_VERSION\", \"Not.A/Brand\";v=\"8\"")
        set("Sec-CH-UA-Arch", "\"\"")
        set("Sec-CH-UA-Bitness", "\"\"")
        set("Sec-CH-UA-Full-Version", "\"$CH_VERSION.0.7727.93\"")
        set("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"$CH_VERSION.0.7727.93\", \"Not.A/Brand\";v=\"8.0.0.0\"")
        set("Sec-CH-UA-Mobile", "?1")
        set("Sec-CH-UA-Model", "\"Redmi Note 9\"")
        set("Sec-CH-UA-Platform", "\"Android\"")
        set("Sec-CH-UA-Platform-Version", "\"11.0.0\"")
        set("Upgrade-Insecure-Requests", "1")
        set("Priority", "u=0, i")
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val host = url.host
            val path = url.encodedPath
            val newHeaders = request.headers.newBuilder()

            val isAjax = path.contains("admin-ajax.php") ||
                path.contains("wp-json") ||
                path.contains("/ajax/")

            val isImage = path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".png") || path.endsWith(".webp") ||
                path.endsWith(".gif") || path.contains("/thumbs/")

            // CDN image — domain berbeda dari baseUrl
            val isCdnImage = isImage && host != "id.mgkomik.cc"

            when {
                isAjax -> {
                    newHeaders.set("X-Requested-With", "XMLHttpRequest")
                    newHeaders.set("Sec-Fetch-Dest", "empty")
                    newHeaders.set("Sec-Fetch-Mode", "cors")
                    newHeaders.set("Sec-Fetch-Site", "same-origin")
                    newHeaders.set("Origin", baseUrl)
                    newHeaders.removeAll("Sec-Fetch-User")
                    newHeaders.removeAll("Upgrade-Insecure-Requests")
                }

                isCdnImage -> {
                    newHeaders.set("Referer", "$baseUrl/")
                    newHeaders.removeAll("Sec-Fetch-Dest")
                    newHeaders.removeAll("Sec-Fetch-Mode")
                    newHeaders.removeAll("Sec-Fetch-Site")
                    newHeaders.removeAll("Sec-Fetch-User")
                    newHeaders.removeAll("X-Requested-With")
                    newHeaders.removeAll("Cache-Control")
                    newHeaders.removeAll("Upgrade-Insecure-Requests")
                    newHeaders.removeAll("Sec-CH-UA-Arch")
                    newHeaders.removeAll("Sec-CH-UA-Bitness")
                    newHeaders.removeAll("Sec-CH-UA-Full-Version")
                    newHeaders.removeAll("Sec-CH-UA-Full-Version-List")
                    newHeaders.removeAll("Sec-CH-UA-Model")
                    newHeaders.removeAll("Sec-CH-UA-Platform-Version")
                }

                isImage -> {
                    // Image di domain utama — same-site, hapus nav headers
                    newHeaders.removeAll("Sec-Fetch-Dest")
                    newHeaders.removeAll("Sec-Fetch-Mode")
                    newHeaders.removeAll("Sec-Fetch-Site")
                    newHeaders.removeAll("Sec-Fetch-User")
                    newHeaders.removeAll("X-Requested-With")
                    newHeaders.removeAll("Cache-Control")
                    newHeaders.removeAll("Upgrade-Insecure-Requests")
                }

                else -> {
                    newHeaders.removeAll("X-Requested-With")
                }
            }

            chain.proceed(request.newBuilder().headers(newHeaders.build()).build())
        }
        .rateLimit(3)
        .build()

    // =============================== Selectors ==============================

    override fun popularMangaSelector() = "div.page-item-detail.manga"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleLink = element.selectFirst(".post-title a")
        title = titleLink?.text()?.trim() ?: element.selectFirst("img")?.attr("alt")?.trim() ?: ""
        setUrlWithoutDomain(titleLink?.attr("abs:href").orEmpty())
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    override fun popularMangaNextPageSelector() = "div.wp-pagenavi a.page, div.wp-pagenavi a.last"

    override val mangaDetailsSelectorTitle = "div.post-title h1, div.post-title h3"
    override val mangaDetailsSelectorAuthor = "div.author-content > a"
    override val mangaDetailsSelectorStatus = "div.summary-heading:contains(Status) + div.summary-content"
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content p"
    override val mangaDetailsSelectorThumbnail = "div.summary_image img"
    override val mangaDetailsSelectorGenre = "div.genres-content a"

    override fun chapterListSelector() = "li.wp-manga-chapter"

    // ================================ Dates =================================

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

    // ================================ Filters ===============================

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

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Redmi Note 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH_VERSION.0.0.0 Mobile Safari/537.36"
    }
}
