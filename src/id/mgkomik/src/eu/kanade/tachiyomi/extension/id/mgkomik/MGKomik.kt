package eu.kanade.tachiyomi.extension.id.mgkomik

import android.app.Application
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Cache
import okhttp3.brotli.BrotliInterceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val id = 5845004992097969882

    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
        add(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        )
        add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        add("Accept-Encoding", "gzip, deflate, br")
        add("DNT", "1")
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "none")
        add("Sec-Fetch-User", "?1")
        add("Upgrade-Insecure-Requests", "1")
        add("Cache-Control", "max-age=0")
        add("X-Requested-With", randomString((8..15).random()))
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(2, 1)
        .apply {
            val index = networkInterceptors().indexOfFirst { it is BrotliInterceptor }
            if (index >= 0) interceptors().add(networkInterceptors().removeAt(index))
        }
        .cache(
            Cache(
                directory = File(Injekt.get<Application>().externalCacheDir, "network_cache_mgkomik"),
                maxSize = 50L * 1024 * 1024, // 50 MiB
            ),
        )
        .build()

    private val pagesClient = client.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    // ================================== Popular ======================================

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            selectFirst("div.item-thumb a")?.let { anchor ->
                manga.setUrlWithoutDomain(anchor.attr("abs:href"))
                manga.title = anchor.attr("title").takeIf { it.isNotBlank() }
                    ?: anchor.text().trim()
            }

            selectFirst("img")?.let { img ->
                manga.thumbnail_url = imageFromElement(img)
            }
        }

        return manga
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

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) : UriPartFilter(
        title,
        options.toTypedArray(),
    )

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")

        try {
            genres += document.select(".row.genres li a").mapNotNull { anchor ->
                val name = anchor.text().trim()
                val url = anchor.absUrl("href")
                if (name.isNotBlank() && url.isNotBlank()) {
                    Genre(name, url)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            // Log error if needed, but don't crash the extension
        }

        return genres
    }

    // =============================== Utilities ==============================

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return List(length) { charPool.random() }.joinToString("")
    }
}
