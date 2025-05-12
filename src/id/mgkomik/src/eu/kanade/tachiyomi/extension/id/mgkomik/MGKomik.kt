package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : Madara(
    "MG Komik",
    "https://mgkomik.org",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"

    // Menambahkan header lengkap untuk meniru permintaan dari browser nyata
    override fun headersBuilder() = super.headersBuilder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        add("Accept-Encoding", "gzip, deflate, br")
        add("Accept-Language", "en-US,en;q=0.9")
        add("Connection", "keep-alive")
        add("Referer", "https://mgkomik.org/")
        add("Upgrade-Insecure-Requests", "1")
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
    }

    // Menambahkan rate limit yang lebih ketat untuk mencegah pemblokiran
    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
                add("Cookie", "SESSIONID=your_session_id_here")  // Tambahkan session ID jika ada
            }.build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(1, 5)  // 1 permintaan setiap 5 detik
        .build()

    // Fungsi untuk menambahkan delay acak antara permintaan
    private fun randomDelay() {
        val delay = (2000L..5000L).random()  // Delay acak antara 2 hingga 5 detik
        Thread.sleep(delay)
    }

    // =========================== Popular ===========================
    override fun popularMangaNextPageSelector() = ".wp-pagenavi span.current + a"

    // =========================== Latest ============================
    override fun latestUpdatesRequest(page: Int): Request {
        return if (useLoadMoreRequest()) {
            loadMoreRequest(page, popular = false)
        } else {
            val request = GET("$baseUrl/$mangaSubString/${searchPage(page)}", headers)
            randomDelay()  // Tambahkan delay acak setelah permintaan
            request
        }
    }

    // ============================ Search ===========================
    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<UriPartFilter>().firstOrNull()
        val genreUri = genreFilter?.toUriPart().orEmpty()

        return if (genreUri.isNotBlank()) {
            val request = GET("$baseUrl$genreUri", headers)
            randomDelay()  // Tambahkan delay acak setelah permintaan
            request
        } else if (query.isNotBlank()) {
            val request = GET("$baseUrl/page/$page?s=$query&post_type=wp-manga", headers)
            randomDelay()  // Tambahkan delay acak setelah permintaan
            request
        } else {
            super.searchRequest(page, query, filters)
        }
    }

    override fun searchMangaSelector() = "${super.searchMangaSelector()}, .page-listing-item .page-item-detail"
    override fun searchMangaNextPageSelector() = "a.page.larger"

    // ============================ Chapters =========================
    override val chapterUrlSuffix = ""

    // ============================ Filters ==========================
    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters = super.getFilterList().list.toMutableList()

        filters += if (genresList.isNotEmpty()) {
            listOf(
                Filter.Separator(),
                UriPartFilter(
                    intl["genre_filter_title"],
                    genresList.map { it.name to it.id }.toTypedArray()
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

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a").map { a ->
            Genre(a.text(), a.attr("href").removePrefix(baseUrl))
        }
        return genres
    }

    // ============================ Local Filter =======================
    class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}