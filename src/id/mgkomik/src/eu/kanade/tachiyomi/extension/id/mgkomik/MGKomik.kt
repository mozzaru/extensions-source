package eu.kanade.tachiyomi.extension.id.mgkomik

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
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
    ),
    ConfigurableSource {
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
        setRandomUserAgent()
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Referer", "$baseUrl/")
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", "none")
        set("Sec-Fetch-User", "?1")
        set("Upgrade-Insecure-Requests", "1")
        set("X-Requested-With", "com.android.chrome")
        set("Priority", "u=0, i")
    }

    override val client = network.client.newBuilder()
        .addInterceptor(UserAgentClientHintsInterceptor())
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val headers = request.headers.newBuilder().apply {
                if (url.contains("admin-ajax.php") || url.contains("wp-json") || url.contains("ajax/chapters")) {
                    set("X-Requested-With", "XMLHttpRequest")
                    set("Sec-Fetch-Dest", "empty")
                    set("Sec-Fetch-Mode", "cors")
                    set("Sec-Fetch-Site", "same-origin")
                    set("Origin", baseUrl)
                    removeAll("Sec-Fetch-User")
                    removeAll("Upgrade-Insecure-Requests")
                    if (request.method == "POST") {
                        set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    }
                } else {
                    if (url.contains(baseUrl)) {
                        set("Sec-Fetch-Site", "same-origin")
                    }
                }

                // Identify image requests
                val accept = request.header("Accept").orEmpty()
                if (accept.startsWith("image/")) {
                    set("Sec-Fetch-Dest", "image")
                    set("Sec-Fetch-Mode", "no-cors")
                    removeAll("X-Requested-With")
                    if (!url.contains(baseUrl)) {
                        set("Sec-Fetch-Site", "cross-site")
                        removeAll("Referer")
                    }
                }
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(3)
        .build()

    // ================================== Popular ======================================

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

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }

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
