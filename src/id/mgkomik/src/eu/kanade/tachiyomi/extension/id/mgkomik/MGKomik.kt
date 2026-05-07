package eu.kanade.tachiyomi.extension.id.mgkomik

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
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
    ),
    ConfigurableSource {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true
    override val mangaSubString = "komik"

    // =============================== Requests ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "trending")
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return GET(url, firstNavHeaders())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "latest")
            .addQueryParameter("t", System.currentTimeMillis().toString())
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

    override fun mangaDetailsRequest(manga: SManga): Request = super.mangaDetailsRequest(manga).addSameOriginNavHeaders()

    override fun chapterListRequest(manga: SManga): Request = super.chapterListRequest(manga).addSameOriginNavHeaders()

    override fun genresRequest(): Request = super.genresRequest().addSameOriginNavHeaders()

    override fun xhrChaptersRequest(mangaUrl: String): Request = POST(
        "${mangaUrl.trimEnd('/')}/ajax/chapters/",
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

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: page.url
        val imageHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .removeAll("Sec-Fetch-Dest")
            .removeAll("Sec-Fetch-Mode")
            .removeAll("Sec-Fetch-Site")
            .removeAll("Sec-Fetch-User")
            .removeAll("Upgrade-Insecure-Requests")
            .removeAll("Cache-Control")
            .removeAll("Priority")
            .removeAll("Sec-CH-UA-Arch")
            .removeAll("Sec-CH-UA-Bitness")
            .removeAll("Sec-CH-UA-Full-Version")
            .removeAll("Sec-CH-UA-Full-Version-List")
            .removeAll("Sec-CH-UA-Model")
            .removeAll("Sec-CH-UA-Platform-Version")
            .build()
        return GET(imageUrl, imageHeaders)
    }

    // =============================== Headers ================================

    private fun firstNavHeaders() = headers.newBuilder()
        .removeAll("Referer")
        .set("Cache-Control", "no-cache")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .build()

    private fun Request.addSameOriginNavHeaders(): Request = newBuilder()
        .header("Referer", "$baseUrl/$mangaSubString/")
        .header("Cache-Control", "no-cache")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-User", "?1")
        .build()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        set("Accept-Language", "id-ID,id;q=0.9")
        set("Upgrade-Insecure-Requests", "1")
        set("Priority", "u=0, i")
        setRandomUserAgent()
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(UserAgentClientHintsInterceptor())
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val isAjax = path.contains("admin-ajax.php") ||
                path.contains("wp-json") ||
                path.contains("/ajax/")

            if (isAjax) {
                val newHeaders = request.headers.newBuilder()
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Sec-Fetch-Dest", "empty")
                    .set("Sec-Fetch-Mode", "cors")
                    .set("Sec-Fetch-Site", "same-origin")
                    .set("Origin", baseUrl)
                    .removeAll("Sec-Fetch-User")
                    .removeAll("Upgrade-Insecure-Requests")
                    .build()
                chain.proceed(request.newBuilder().headers(newHeaders).build())
            } else {
                chain.proceed(
                    request.newBuilder()
                        .removeHeader("X-Requested-With")
                        .build(),
                )
            }
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

        if (trimmed.equals("hari ini", ignoreCase = true)) {
            return parseRelativeDate("0 hours ago")
        }

        if (trimmed.equals("kemarin", ignoreCase = true)) {
            return parseRelativeDate("1 day ago")
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

    // ================================ Utils =================================

    override fun getMangaUrl(manga: SManga): String = when {
        manga.url.startsWith("http") -> manga.url
        manga.url.startsWith("//") -> "https:${manga.url}"
        else -> "$baseUrl${manga.url}"
    }

    override fun getChapterUrl(chapter: SChapter): String = when {
        chapter.url.startsWith("http") -> chapter.url
        chapter.url.startsWith("//") -> "https:${chapter.url}"
        else -> "$baseUrl${chapter.url}"
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

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) : UriPartFilter(title, options.toTypedArray())

    override fun parseGenres(document: Document): List<Genre> = buildList {
        add(Genre("All", ""))
        addAll(document.select(".row.genres li a").map { Genre(it.text(), it.absUrl("href")) })
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }
}
