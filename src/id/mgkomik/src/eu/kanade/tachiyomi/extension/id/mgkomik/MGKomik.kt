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
        "https://id.mgkomik.cc",
        "id",
        SimpleDateFormat("dd MMM yy", Locale.US),
    ),
    ConfigurableSource {
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    // Migrasi link lama agar library user tetap aman
    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}".replace("mgkomik.com", "id.mgkomik.cc")
        .replace("/manga/", "/$mangaSubString/")

    override fun getChapterUrl(chapter: SChapter): String = chapter.url.replace("mgkomik.com", "id.mgkomik.cc")
        .replace("/manga/", "/$mangaSubString/")

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

            // Sinkronisasi identitas browser untuk publik user
            builder.header("Sec-CH-UA", "\"Chromium\";v=\"$chromeVersion\", \"Not_A Brand\";v=\"24\"")
            builder.header("Sec-CH-UA-Mobile", "?1")
            builder.header("Sec-CH-UA-Platform", "\"Android\"")

            if (url.contains("admin-ajax.php") || url.contains("wp-json")) {
                builder.header("X-Requested-With", "XMLHttpRequest")
            } else {
                builder.removeHeader("X-Requested-With")

                // Header browser standard untuk request halaman/dokumen
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

    // Selector lebih luas agar lebih stabil
    override fun popularMangaSelector() = "div.page-item-detail, .manga__item, .post-item"

    override val popularMangaUrlSelector = "div.post-title a, .manga-title a, .manga__title a, .item-thumb a"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst(popularMangaUrlSelector)!!.let {
            setUrlWithoutDomain(it.attr("abs:href"))
            title = it.attr("title").ifEmpty { it.text() }
        }
        element.selectFirst("img")?.let {
            thumbnail_url = imageFromElement(it)
        }
    }

    // Selector detail tambahan
    override val mangaDetailsSelectorTitle = "div.post-title h3, div.post-title h1, #manga-title > h1, .manga-title, h1#mangaTitle"
    override val mangaDetailsSelectorAuthor = "div.author-content > a, div.manga-authors > a, .meta-item:contains(Author:) a"
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, .manga-about, .manga-description"

    // Ambil gambar kualitas asli (bukan resize)
    override fun imageFromElement(element: Element): String? {
        val url = super.imageFromElement(element) ?: return null
        val isPage = element.parents().any { it.hasClass("reading-content") || it.hasClass("page-break") }
        return if (isPage) {
            url.replace(RESIZE_REGEX, "").replace(SCALED_REGEX, "")
        } else {
            url
        }
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
        private val RESIZE_REGEX = """-\d+x\d+(?=\.(jpg|jpeg|png|webp))""".toRegex()
        private val SCALED_REGEX = """-scaled(?=\.(jpg|jpeg|png|webp))""".toRegex()
    }
}
