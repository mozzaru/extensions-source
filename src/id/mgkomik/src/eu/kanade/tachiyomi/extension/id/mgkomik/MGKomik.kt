package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
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
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override fun popularMangaRequest(page: Int): Request = super.popularMangaRequest(page).addTimestamp()

    override fun latestUpdatesRequest(page: Int): Request = super.latestUpdatesRequest(page).addTimestamp()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = super.searchMangaRequest(page, query, filters).addTimestamp()

    override fun mangaDetailsRequest(manga: SManga): Request = super.mangaDetailsRequest(manga).addTimestamp()

    override fun chapterListRequest(manga: SManga): Request = super.chapterListRequest(manga).addTimestamp()

    private fun Request.addTimestamp(): Request {
        val url = this.url.newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return this.newBuilder().url(url).build()
    }

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", USER_AGENT)
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        set("DNT", "1")
        set("Sec-CH-UA", "\"Chromium\";v=\"$CH_VERSION\", \"Not.A/Brand\";v=\"24\", \"Google Chrome\";v=\"$CH_VERSION\"")
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
            if (path.contains("admin-ajax.php") || path.contains("wp-json") || request.header("X-Requested-With") == "XMLHttpRequest") {
                headers.set("X-Requested-With", "XMLHttpRequest")
                headers.set("Sec-Fetch-Dest", "empty")
                headers.set("Sec-Fetch-Mode", "cors")
                headers.set("Sec-Fetch-Site", "same-origin")
                headers.removeAll("Upgrade-Insecure-Requests")
            } else if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") || path.contains("photon")) {
                headers.removeAll("X-Requested-With")
                headers.set("Sec-Fetch-Dest", "image")
                headers.set("Sec-Fetch-Mode", "no-cors")
                headers.set("Sec-Fetch-Site", "cross-site")
                headers.removeAll("Upgrade-Insecure-Requests")
            } else {
                headers.removeAll("X-Requested-With")
                headers.set("Sec-Fetch-Dest", "document")
                headers.set("Sec-Fetch-Mode", "navigate")
                headers.set("Sec-Fetch-Site", if (request.header("Referer") != null) "same-origin" else "none")
                headers.set("Sec-Fetch-User", "?1")
                headers.set("Priority", "u=0, i")
            }

            chain.proceed(request.newBuilder().headers(headers.build()).build())
        }
        .rateLimit(12, 3)
        .build()

    // ================================== Popular ======================================

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.select("div.item-thumb a").let {
            setUrlWithoutDomain(it.attr("abs:href"))
            title = it.attr("title")
            thumbnail_url = it.select("img").attr("abs:src")
        }
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
