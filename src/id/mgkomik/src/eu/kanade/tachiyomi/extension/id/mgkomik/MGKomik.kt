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

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"
    override val id = 5845004992097969882

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomString((1..20).random()))
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(9, 2)
        .build()

    // ================================== Popular ======================================

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            // Aman dari NPE
            selectFirst("div.item-thumb a")?.let {
                manga.setUrlWithoutDomain(it.attr("abs:href"))
                manga.title = it.attr("title") ?: "Untitled"
            } ?: run {
                // fallback kalau struktur berubah
                selectFirst("a")?.let {
                    manga.setUrlWithoutDomain(it.attr("abs:href"))
                    manga.title = it.attr("title") ?: "Untitled"
                }
            }

            selectFirst("img")?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
        }

        return manga
    }

    // ================================ Chapters ================================

    override val chapterUrlSuffix = ""

    // ================================ Filters ================================

    override fun getFilterList(): FilterList {
        return try {
            launchIO { fetchGenres() }

            val filters = super.getFilterList().list.toMutableList()

            if (genresList.isNotEmpty()) {
                filters += listOf(
                    Filter.Separator(),
                    GenreContentFilter(
                        title = intl["genre_filter_title"],
                        options = genresList.map { it.name to it.id },
                    ),
                )
            } else {
                filters += listOf(
                    Filter.Separator(),
                    Filter.Header(intl["genre_missing_warning"]),
                )
            }

            FilterList(filters)
        } catch (e: Exception) {
            FilterList(
                super.getFilterList().list + listOf(
                    Filter.Separator(),
                    Filter.Header("Error loading genres"),
                )
            )
        }
    }

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) : UriPartFilter(
        title,
        options.toTypedArray(),
    )

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")

        document.select(".row.genres li a").forEach { a ->
            val name = a.text().ifBlank { "Unknown" }
            val url = a.absUrl("href").ifBlank { "" }
            genres += Genre(name, url)
        }

        return genres
    }

    // =============================== Utilities ==============================

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + '.'
        return List(length) { charPool.random() }.joinToString("")
    }
}
