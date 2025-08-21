package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
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
    override val id = 5845004992097969882

    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomString((1..20).random())) // added for webview, and removed in interceptor for normal use
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
        .build()

    // ================================== Popular ======================================

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        val anchor = element.selectFirst("div.item-thumb a, .tab-thumb a, h3 a, .post-title a, a[href]")

        if (anchor != null) {
            manga.setUrlWithoutDomain(anchor.attr("abs:href"))
            manga.title = anchor.attr("title").takeIf { it.isNotBlank() } ?: anchor.text().trim()
        } else {
            manga.setUrlWithoutDomain(baseUrl)
            manga.title = element.selectFirst("h2, h3, .post-title")?.text()?.trim() ?: "Unknown"
        }

        element.selectFirst("img")?.let { img ->
            manga.thumbnail_url = imageFromElement(img)
        }

        return manga
    }

    // ================================ Latest (skip invalid elements) ================================

    private fun latestMangaFromElementOrNull(element: Element): SManga? {
        val anchor = element.selectFirst(
            "div.item-thumb a, .tab-thumb a, .c-image-hover a, .post-title a, h3 a, a[href]",
        ) ?: return null

        val manga = SManga.create()
        val url = anchor.attr("abs:href").trim()
        if (url.isBlank()) return null

        manga.setUrlWithoutDomain(url)
        manga.title = anchor.attr("title").takeIf { it.isNotBlank() } ?: anchor.text().trim()
        element.selectFirst("img")?.let { img ->
            manga.thumbnail_url = imageFromElement(img)
        }
        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val anchor = element.selectFirst("a[href]")
        val manga = SManga.create()
        if (anchor != null) {
            manga.setUrlWithoutDomain(anchor.attr("abs:href"))
            manga.title = anchor.attr("title").takeIf { it.isNotBlank() } ?: anchor.text().trim()
            element.selectFirst("img")?.let { manga.thumbnail_url = imageFromElement(it) }
        } else {
            manga.setUrlWithoutDomain(baseUrl)
            manga.title = element.text().trim().takeIf { it.isNotBlank() } ?: "Unknown"
        }
        return manga
    }

    override fun latestUpdatesParse(document: Document): MangasPage {
        val items = document.select(
            "div.item-thumb, .tab-thumb, .c-image-hover, .bs, .swiper-slide, article, .post",
        )

        val mangas = items.mapNotNull { latestMangaFromElementOrNull(it) }

        val hasNext = document.selectFirst(".navigation a.next, .pagination a.next, a.next") != null

        return MangasPage(mangas, hasNext)
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
        } catch (_: Exception) {
            // ignore
        }

        return genres
    }

    // =============================== Utilities ==============================

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charPool.random() }.joinToString("")
    }
}
