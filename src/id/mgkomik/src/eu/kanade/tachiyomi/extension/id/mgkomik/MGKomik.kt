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
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val ua = request.header("User-Agent").orEmpty()
            val chromeVersion = CHROME_REGEX.find(ua)?.groupValues?.get(1) ?: "131"

            val builder = request.newBuilder()

            // On-point Client Hints sinkron dengan User-Agent untuk Publik
            builder.header("Sec-CH-UA", "\"Chromium\";v=\"$chromeVersion\", \"Not_A Brand\";v=\"24\"")
            builder.header("Sec-CH-UA-Mobile", "?1")
            builder.header("Sec-CH-UA-Platform", "\"Android\"")
            builder.header("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"$chromeVersion.0.0.0\", \"Not_A Brand\";v=\"24.0.0.0\"")
            builder.header("Sec-CH-UA-Platform-Version", "\"11.0.0\"")
            builder.header("Sec-CH-UA-Model", "\"\"")

            if (url.contains("admin-ajax.php") || url.contains("wp-json")) {
                builder.header("X-Requested-With", "XMLHttpRequest")
                builder.header("Sec-Fetch-Dest", "empty")
                builder.header("Sec-Fetch-Mode", "cors")
                builder.header("Sec-Fetch-Site", "same-origin")
                builder.header("Referer", "$baseUrl/")
                builder.header("Accept", "*/*")
            } else {
                builder.removeHeader("X-Requested-With")
                if (request.header("Accept")?.contains("text/html") == true) {
                    builder.header("Sec-Fetch-Dest", "document")
                    builder.header("Sec-Fetch-Mode", "navigate")
                    builder.header("Sec-Fetch-Site", "none")
                    builder.header("Sec-Fetch-User", "?1")
                    builder.header("Upgrade-Insecure-Requests", "1")
                }
            }

            chain.proceed(builder.build())
        }
        .rateLimit(3)
        .build()

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("div.item-thumb a, div.post-title a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.attr("title").ifEmpty { link.text() }
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    override fun getFilterList(): FilterList {
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
        }
        return FilterList(filters)
    }

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) : UriPartFilter(title, options.toTypedArray())

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

    companion object {
        private val CHROME_REGEX = """Chrome/(\d+)""".toRegex()
    }
}
