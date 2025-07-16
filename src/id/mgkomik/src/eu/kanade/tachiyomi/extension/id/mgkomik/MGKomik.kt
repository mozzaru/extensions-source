package eu.kanade.tachiyomi.extension.id.mgkomik

import android.app.Application
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SFilter
import eu.kanade.tachiyomi.source.model.SUriFilter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"

    override val client: OkHttpClient = CloudflareBypassHelper.wrapClientWithBypass(
        context = Injekt.get<Application>(),
        base = network.cloudflareClient.newBuilder()
            .rateLimit(9, 2)
            .build(),
    )

    // ============================ Popular ============================
    override fun popularMangaNextPageSelector() = ".wp-pagenavi span.current + a"

    // ============================ Latest =============================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/${searchPage(page)}", headers)

    // ============================ Search =============================
    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        filters.filterIsInstance<GenreContentFilter>().forEach { filter ->
            val url = filter.toUriPart()
            if (url.isNotBlank()) {
                return GET(url, headers)
            }
        }
        return super.searchRequest(page, query, filters)
    }

    override fun searchMangaSelector() =
        "${super.searchMangaSelector()}, .page-listing-item .page-item-detail"

    override fun searchMangaNextPageSelector() = "a.page.larger"

    // ============================ Chapters ============================
    override val chapterUrlSuffix = ""

    // ============================ Filters =============================
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

    private class GenreContentFilter(
        title: String,
        options: List<Pair<String, String>>,
    ) : UriPartFilter(
        title,
        options.toTypedArray(),
    )

    override fun genresRequest(): Request = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf(Genre("All", ""))
        genres += document.select(".row.genres li a").map {
            Genre(it.text(), it.absUrl("href"))
        }
        return genres
    }
}
