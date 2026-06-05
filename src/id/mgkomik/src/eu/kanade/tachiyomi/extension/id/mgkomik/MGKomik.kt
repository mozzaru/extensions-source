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

class MGKomik :
    Madara(
        "MG Komik",
        "https://id.mgkomik.cc",
        "id",
        SimpleDateFormat("dd MMM yy", Locale.US),
    ) {
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
        val ua = get("User-Agent")!!

        val chromeVersion = Regex("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)")
            .find(ua)?.groupValues?.get(1) ?: "124.0.0.0"
        val chromeMajor = chromeVersion.split(".").firstOrNull() ?: "124"
        val androidVersion = Regex("Android (\\d+)")
            .find(ua)?.groupValues?.get(1) ?: "10"

        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        set("Accept-Language", "id-ID,id;q=0.9")
        set("Upgrade-Insecure-Requests", "1")
        set("sec-fetch-dest", "document")
        set("sec-fetch-mode", "navigate")
        set("sec-fetch-site", "none")
        set("sec-fetch-user", "?1")
        set("sec-ch-ua", "\"Chromium\";v=\"$chromeMajor\", \"Not.A/Brand\";v=\"8\"")
        set("sec-ch-ua-mobile", "?1")
        set("sec-ch-ua-platform", "\"Android\"")
        set("sec-ch-ua-arch", "\"\"")
        set("sec-ch-ua-bitness", "\"\"")
        set("sec-ch-ua-full-version", "\"$chromeVersion\"")
        set("sec-ch-ua-full-version-list", "\"Chromium\";v=\"$chromeVersion\", \"Not.A/Brand\";v=\"8.0.0.0\"")
        set("sec-ch-ua-platform-version", "\"$androidVersion.0.0\"")
        set("sec-ch-ua-model", "\"\"")
    }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(3)
        .build()

    // ================================== Popular ==================================

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

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a").map { a ->
            Genre(a.text(), a.absUrl("href"))
        }
        return genres
    }
}
