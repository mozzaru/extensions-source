package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik :
    Madara(
        "MG Komik",
        "https://id.mgkomik.cc",
        "id",
        SimpleDateFormat("dd MMM yy", Locale("id")),
    ) {
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val filterNonMangaItems = false

    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        // Consistent identity for Cloudflare bypass: spoof Chrome mobile
        add("X-Requested-With", "com.android.chrome")
    }

    override fun popularMangaSelector() = "div.page-item-detail:not(:has(a[href*='bilibilicomics.com'])), .manga__item"

    override val client = super.client.newBuilder()
        .addInterceptor(::browserLikeInterceptor)
        .rateLimit(9, 2)
        .build()

    private fun browserLikeInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val isAjax = request.header("X-Requested-With") == "XMLHttpRequest" ||
            url.contains("admin-ajax.php") ||
            url.contains("ajax/chapters")

        val newHeaders = request.headers.newBuilder().apply {
            set("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            set("Referer", "$baseUrl/")

            if (isAjax) {
                set("X-Requested-With", "XMLHttpRequest")
                set("Accept", "*/*")
                set("Sec-Fetch-Dest", "empty")
                set("Sec-Fetch-Mode", "cors")
                set("Sec-Fetch-Site", "same-origin")
            } else {
                // Remove X-Requested-With for normal document/image navigation to mimic real browser
                removeAll("X-Requested-With")
                set("Sec-Fetch-Dest", if (url.contains("img") || url.contains("uploads")) "image" else "document")
                set("Sec-Fetch-Mode", if (url.contains("img") || url.contains("uploads")) "no-cors" else "navigate")
                set("Sec-Fetch-Site", if (url.contains(baseUrl)) "same-origin" else "cross-site")
                if (!url.contains("img")) {
                    set("Sec-Fetch-User", "?1")
                    set("Upgrade-Insecure-Requests", "1")
                }
            }
        }.build()

        return chain.proceed(request.newBuilder().headers(newHeaders).build())
    }

    // =========================== URL Migration ============================

    override fun getMangaUrl(manga: SManga): String {
        val url = manga.url.replace("mgkomik.com", "id.mgkomik.cc")
            .replace("/manga/", "/komik/")
        return if (url.startsWith("http")) url else "$baseUrl$url"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val url = chapter.url.replace("mgkomik.com", "id.mgkomik.cc")
            .replace("/manga/", "/komik/")
        return if (url.startsWith("http")) url else "$baseUrl$url"
    }

    // ================================ Details ================================

    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, .manga-description"

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
        genres += document.select(".row.genres li a, .checkbox-group .checkbox label").map { a ->
            Genre(a.text(), a.absUrl("href").ifEmpty { a.previousElementSibling()?.`val`() ?: "" })
        }
        return genres
    }

    // =============================== Utilities ==============================

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charPool.random() }.joinToString("")
    }
}
