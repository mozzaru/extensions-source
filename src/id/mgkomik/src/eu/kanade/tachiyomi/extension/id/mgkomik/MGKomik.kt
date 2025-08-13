package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
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

        val link = element.selectFirst("div.item-thumb a")
        if (link != null) {
            manga.setUrlWithoutDomain(link.absUrl("href").removePrefix(baseUrl))
            manga.title = link.attr("title").ifBlank { link.text().trim() }
        }

        element.selectFirst("img")?.let {
            manga.thumbnail_url = imageFromElement(it)
        }

        return manga
    }

    // ================================ Chapters ================================
    override val chapterUrlSuffix = ""

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        val a = element.selectFirst("a") ?: return chapter
        val href = a.absUrl("href").ifBlank { a.attr("href") }

        // Skip link "baca di web"
        if (href.contains("baca-di-web", true) || a.text().contains("baca di web", true)) {
            return chapter
        }

        chapter.setUrlWithoutDomain(href.removePrefix(baseUrl))
        chapter.name = a.text().trim()

        element.selectFirst(".chapter-release-date, .chapter-release-date i")
            ?.text()
            ?.let { dateStr -> chapter.date_upload = parseChapterDate(dateStr) }

        return chapter
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

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a").map { a ->
            Genre(a.text(), a.absUrl("href"))
        }
        return genres
    }

    // =============================== Utilities ==============================
    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + '.'
        return List(length) { charPool.random() }.joinToString("")
    }
}
