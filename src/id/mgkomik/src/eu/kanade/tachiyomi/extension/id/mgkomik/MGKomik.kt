package eu.kanade.tachiyomi.extension.id.mgkomik

import android.util.Log
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
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
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
        set("Upgrade-Insecure-Requests", "1")
        set("Referer", "$baseUrl/")
    }

    override val client = network.client.newBuilder()
        .rateLimit(9, 2)
        .build()

    // ================================== Popular/Latest ======================================

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst("div.item-thumb a")?.let { a ->
            setUrlWithoutDomain(a.attr("abs:href"))
        }
        title = element.selectFirst("div.post-title a")?.text()
            ?: element.selectFirst("div.item-thumb a")?.attr("title")
            ?: ""
        thumbnail_url = element.selectFirst("div.item-thumb img")?.let {
            imageFromElement(it)
        }
        Log.d("MGKomik", "item: url=$url title=$title")
    }

    override fun popularMangaNextPageSelector(): String =
        "a.page.larger, .wp-pagenavi a.last"

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
}
