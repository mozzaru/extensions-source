package eu.kanade.tachiyomi.extension.id.mgkomik

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
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
        "https://web.mgkomik.cc",
        "id",
        SimpleDateFormat("dd MMM yy", Locale("id")),
    ),
    ConfigurableSource {
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun headersBuilder() = super.headersBuilder().apply {
        setRandomUserAgent(userAgentType = UserAgentType.MOBILE, filterInclude = listOf("Chrome"))
        set("Accept-Language", "id-ID,id;q=0.9")
        set("Cache-Control", "no-cache")
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val ua = request.header("User-Agent").orEmpty()
            val chromeVersion = CHROME_REGEX.find(ua)?.groupValues?.get(1) ?: "131"

            val builder = request.newBuilder()

            // Identitas Browser (Client Hints) sinkron dengan User-Agent
            builder.header("Sec-CH-UA", "\"Chromium\";v=\"$chromeVersion\", \"Not?A_Brand\";v=\"24\", \"Google Chrome\";v=\"$chromeVersion\"")
            builder.header("Sec-CH-UA-Mobile", "?1")
            builder.header("Sec-CH-UA-Platform", "\"Android\"")
            builder.header("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"$chromeVersion.0.0.0\", \"Not?A_Brand\";v=\"24.0.0.0\", \"Google Chrome\";v=\"$chromeVersion.0.0.0\"")

            if (url.contains("admin-ajax.php") || url.contains("wp-json") || url.contains("ajax")) {
                builder.header("X-Requested-With", "XMLHttpRequest")
                builder.header("Sec-Fetch-Dest", "empty")
                builder.header("Sec-Fetch-Mode", "cors")
                builder.header("Sec-Fetch-Site", "same-origin")
                builder.header("Origin", baseUrl)
                builder.header("Referer", "$baseUrl/")
                builder.header("Accept", "*/*")
            } else if (url.contains(Regex("""\.(jpg|jpeg|png|webp|avif|gif)""", RegexOption.IGNORE_CASE))) {
                builder.removeHeader("X-Requested-With")
                builder.header("Sec-Fetch-Dest", "image")
                builder.header("Sec-Fetch-Mode", "no-cors")
                builder.header("Sec-Fetch-Site", "cross-site")
                builder.header("Referer", "$baseUrl/")
            } else {
                builder.removeHeader("X-Requested-With")
                builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                builder.header("Sec-Fetch-Dest", "document")
                builder.header("Sec-Fetch-Mode", "navigate")
                builder.header("Sec-Fetch-Site", "none")
                builder.header("Sec-Fetch-User", "?1")
                builder.header("Upgrade-Insecure-Requests", "1")
            }

            chain.proceed(builder.build())
        }
        .rateLimit(9, 2)
        .build()

    // Selector listing diperluas untuk web baru
    override fun popularMangaSelector() = "div.page-item-detail, .manga__item, .post-item, .item-manga"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a:has(img), div.item-thumb a, div.post-title a, .manga__item-title a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.attr("title").ifEmpty {
            element.selectFirst(".manga__item-title, .post-title, h3, h4")?.text() ?: link.text()
        }
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    // Details selector untuk web baru
    override val mangaDetailsSelectorTitle = "h1, .manga-title, .post-title"
    override val mangaDetailsSelectorDescription = ".manga-about, .summary__content, .manga-description, .description-summary"
    override val mangaDetailsSelectorAuthor = ".author-content, .meta-item:contains(Author:)"
    override val mangaDetailsSelectorArtist = ".artist-content"
    override val mangaDetailsSelectorGenre = ".genres-content a, .genre-tag, .manga-genre a"
    override val mangaDetailsSelectorStatus = ".post-status, .status-badge, .manga-status"

    // Chapter selector untuk web baru
    override fun chapterListSelector() = "li.chapter-list-item, li.wp-manga-chapter, .chapter-item"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        name = element.selectFirst(".chapter-name, .chapter-link, a")?.text() ?: link.text()
        date_upload = element.selectFirst(".chapter-release-date, .chapter-date, .date")?.text()?.let {
            parseChapterDate(it)
        } ?: 0L
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

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) :
        UriPartFilter(
            title,
            options.toTypedArray(),
        )

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a, .genres-list a").map { a ->
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
