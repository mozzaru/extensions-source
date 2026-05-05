package eu.kanade.tachiyomi.extension.id.mgkomik

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.lib.randomua.UserAgentType
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

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun headersBuilder() = super.headersBuilder().apply {
        setRandomUserAgent(userAgentType = UserAgentType.MOBILE, filterInclude = listOf("Chrome"))
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        set("Accept-Language", "id-ID,id;q=0.9")
        set("Upgrade-Insecure-Requests", "1")
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val userAgent = request.header("User-Agent").orEmpty()

            if (url.contains("admin-ajax.php") || url.contains("wp-json")) {
                return@addInterceptor chain.proceed(
                    request.newBuilder()
                        .header("X-Requested-With", "XMLHttpRequest")
                        .build(),
                )
            }

            val chromeVersion = Regex("""Chrome/(\d+)""").find(userAgent)?.groupValues?.get(1) ?: "131"
            val newRequest = request.newBuilder().apply {
                removeHeader("X-Requested-With")
                if (request.header("Accept")?.contains("text/html") == true) {
                    header("Sec-CH-UA", "\"Chromium\";v=\"$chromeVersion\", \"Not_A Brand\";v=\"24\"")
                    header("Sec-CH-UA-Mobile", "?1")
                    header("Sec-CH-UA-Platform", "\"Android\"")
                    header("Sec-Fetch-Dest", "document")
                    header("Sec-Fetch-Mode", "navigate")
                    header("Sec-Fetch-Site", "none")
                    header("Sec-Fetch-User", "?1")
                }
            }.build()

            chain.proceed(newRequest)
        }
        .rateLimit(9, 2)
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

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a").map { a ->
            Genre(a.text(), a.absUrl("href"))
        }
        return genres
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }
}
