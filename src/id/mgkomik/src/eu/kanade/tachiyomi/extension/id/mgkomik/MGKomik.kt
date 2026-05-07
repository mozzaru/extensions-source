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
        SimpleDateFormat("dd MMM yyyy", Locale.US),
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
        // First request: no referer to get Sec-Fetch-Site: none
        return GET(url, headers.newBuilder().removeAll("Referer").build())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString/${if (page > 1) "page/$page/" else ""}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "latest")
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return GET(url, headers.newBuilder().removeAll("Referer").build())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (query.isNotBlank()) {
        super.searchMangaRequest(page, query, filters).addTimestamp()
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
        GET(url.build(), headers.newBuilder().removeAll("Referer").build())
    }

    override fun mangaDetailsRequest(manga: SManga): Request = super.mangaDetailsRequest(manga).addTimestamp()

    override fun chapterListRequest(manga: SManga): Request = super.chapterListRequest(manga).addTimestamp()

    override fun genresRequest(): Request = super.genresRequest().addTimestamp()

    private fun Request.addTimestamp(): Request {
        val url = this.url.newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return this.newBuilder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("Referer", "$baseUrl/$mangaSubString/")
            .build()
    }

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
                // Documents - Removing X-Requested-With for pure browser mimicry
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
        .rateLimit(3)
        .build()

    // =============================== Selectors ===============================

    override fun popularMangaSelector() = "div.page-item-detail:not(:has(a[href*='bilibilicomics.com'])), .manga__item, .post-item, .c-tabs-item__content"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleElement = element.selectFirst(".manga-title a, .post-title a, h3 a, h2 a, a:has(h3), a:has(h2), .item-thumb a")
        title = titleElement?.text()?.trim() ?: element.select("img").attr("alt").trim()
        setUrlWithoutDomain(titleElement?.attr("abs:href").orEmpty())
        element.selectFirst("img")?.let {
            thumbnail_url = imageFromElement(it)
        }
    }

    override fun popularMangaNextPageSelector() = "div.nav-previous, nav.navigation-ajax, a.nextpostslink, a.next, .pagination a:contains(Next)"

    override val mangaDetailsSelectorTitle = ".manga-title, h1#mangaTitle, div.post-title h3, div.post-title h1, #manga-title > h1"
    override val mangaDetailsSelectorAuthor = ".meta-item:contains(Author:) .meta-value, div.author-content > a, div.manga-authors > a"
    override val mangaDetailsSelectorStatus = ".status-badge, div.summary-content, div.summary-heading:contains(Status) + div"
    override val mangaDetailsSelectorDescription = ".manga-description p, div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt"
    override val mangaDetailsSelectorThumbnail = ".manga-cover-large, div.summary_image img"
    override val mangaDetailsSelectorGenre = ".genre-tag, div.genres-content a"

    override fun chapterListSelector() = "li.chapter-list-item, li.wp-manga-chapter"

    // ================================ Dates ==================================

    override fun parseChapterDate(date: String?): Long {
        date ?: return 0L

        val dateLow = date.lowercase()
        if (dateLow.contains("hari ini") || dateLow.contains("today")) {
            return java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        if (dateLow.contains("kemarin") || dateLow.contains("yesterday")) {
            return java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        if (dateLow.contains("ago") || dateLow.contains("yang lalu")) {
            return parseRelativeDate(date)
        }

        val dateFormats = listOf(
            "dd MMM yyyy" to Locale.US,
            "dd MMM yyyy" to Locale("id"),
            "dd MMM yy" to Locale.US,
            "dd MMM yy" to Locale("id"),
            "dd/MM/yyyy" to Locale.US,
            "MMMM d, yyyy" to Locale.US,
            "MMMM d, yyyy" to Locale("id"),
            "dd/MM/yy" to Locale.US,
            "yyyy-MM-dd" to Locale.US,
        )

        for ((pattern, locale) in dateFormats) {
            try {
                val simpleDateFormat = SimpleDateFormat(pattern, locale)
                return simpleDateFormat.parse(date)?.time ?: 0L
            } catch (_: ParseException) {
            }
        }

        return super.parseChapterDate(date)
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
