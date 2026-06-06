package eu.kanade.tachiyomi.extension.id.mgkomik

import android.util.Log
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
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

    // Override xhrHeaders — hapus X-Requested-With, ganti sec-fetch untuk AJAX
    override val xhrHeaders: Headers by lazy {
        headersBuilder()
            .removeAll("X-Requested-With")
            .set("sec-fetch-dest", "empty")
            .set("sec-fetch-mode", "cors")
            .set("sec-fetch-site", "same-origin")
            .removeAll("Upgrade-Insecure-Requests")
            .removeAll("sec-fetch-user")
            .build()
    }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()

            Log.d("MGKomik", "headersBuilder() headers:")
            request.headers.forEach { (name, value) ->
                Log.d("MGKomik", "  $name: $value")
            }

            Log.d("MGKomik", "→ REQUEST: ${request.url}")
            Log.d("MGKomik", "  User-Agent: ${request.header("User-Agent")}")
            Log.d("MGKomik", "  sec-ch-ua: ${request.header("sec-ch-ua") ?: "TIDAK ADA!"}")
            Log.d("MGKomik", "  X-Requested-With: ${request.header("X-Requested-With") ?: "tidak ada ✓"}")

            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            val newRequest = request.newBuilder().headers(headers).build()
            val response = chain.proceed(newRequest)

            Log.d("MGKomik", "← RESPONSE: ${response.code} | URL: ${response.request.url}")

            response
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

    override fun popularMangaParse(response: okhttp3.Response): eu.kanade.tachiyomi.source.model.MangasPage {
        val result = super.popularMangaParse(response)
        Log.d("MGKomik", "popularMangaParse: parsed=${result.mangas.size} hasNextPage=${result.hasNextPage}")
        result.mangas.take(3).forEach {
            Log.v("MGKomik", "  manga: title='${it.title}' url=${it.url}")
        }
        return result
    }

    override fun latestUpdatesParse(response: okhttp3.Response): eu.kanade.tachiyomi.source.model.MangasPage {
        val result = super.latestUpdatesParse(response)
        Log.d("MGKomik", "latestUpdatesParse: parsed=${result.mangas.size} hasNextPage=${result.hasNextPage}")
        result.mangas.take(3).forEach {
            Log.v("MGKomik", "  manga: title='${it.title}' url=${it.url}")
        }
        return result
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
