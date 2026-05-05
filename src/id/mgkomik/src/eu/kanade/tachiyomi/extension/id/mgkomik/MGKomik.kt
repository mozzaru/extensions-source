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

    override fun getMangaUrl(manga: SManga): String {
        val url = manga.url.let {
            if (it.startsWith("http")) {
                it
            } else if (it.startsWith("//")) {
                "https:$it"
            } else {
                "$baseUrl$it"
            }
        }
        return url.replace("mgkomik.com", "id.mgkomik.cc")
            .replace("/manga/", "/$mangaSubString/")
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url.replace("mgkomik.com", "id.mgkomik.cc")
        .replace("/manga/", "/$mangaSubString/")

    override fun headersBuilder() = super.headersBuilder().apply {
        setRandomUserAgent(userAgentType = UserAgentType.MOBILE, filterInclude = listOf("Chrome"))

        val userAgent = build().get("User-Agent").orEmpty()
        val chromeVersion = Regex("""Chrome/(\d+)""").find(userAgent)?.groupValues?.get(1) ?: "131"

        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        set("Accept-Language", "id-ID,id;q=0.9")
        set("Cache-Control", "max-age=0")
        set("Sec-CH-UA", "\"Chromium\";v=\"$chromeVersion\", \"Not_A Brand\";v=\"24\"")
        set("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"$chromeVersion.0.0.0\", \"Not_A Brand\";v=\"24.0.0.0\"")
        set("Sec-CH-UA-Mobile", "?1")
        set("Sec-CH-UA-Model", "\"\"")
        set("Sec-CH-UA-Platform", "\"Android\"")
        set("Sec-CH-UA-Platform-Version", "\"11.0.0\"")
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", "none")
        set("Sec-Fetch-User", "?1")
        set("Upgrade-Insecure-Requests", "1")
        set("X-Requested-With", "com.android.chrome")
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            val newRequest = request.newBuilder().apply {
                if (url.contains("admin-ajax.php") || url.contains("wp-json")) {
                    header("X-Requested-With", "XMLHttpRequest")
                    header("Sec-Fetch-Dest", "empty")
                    header("Sec-Fetch-Mode", "cors")
                    header("Sec-Fetch-Site", "same-origin")
                    removeHeader("Sec-Fetch-User")
                    removeHeader("Upgrade-Insecure-Requests")
                    header("Accept", "*/*")
                } else if (url.contains(Regex("""\.(jpg|jpeg|png|webp|avif|gif)""", RegexOption.IGNORE_CASE))) {
                    removeHeader("X-Requested-With")
                    header("Sec-Fetch-Dest", "image")
                    header("Sec-Fetch-Mode", "no-cors")
                    header("Sec-Fetch-Site", "same-origin")
                    removeHeader("Sec-Fetch-User")
                    removeHeader("Upgrade-Insecure-Requests")
                    header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                } else {
                    removeHeader("X-Requested-With")
                    // Standard document navigation
                    if (request.method == "GET" && !url.contains("?")) {
                        url("$url?t=${System.currentTimeMillis() / 600000}")
                    }
                }
            }.build()

            chain.proceed(newRequest)
        }
        .rateLimit(9, 2)
        .build()

    // ================================== Popular ======================================

    override fun popularMangaSelector() = "div.page-item-detail:not(:has(a[href*='bilibilicomics.com'])), .manga__item, .post-item"

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

    // ================================ Chapters ================================

    override val chapterUrlSuffix = ""

    // ================================ Details ================================

    override val mangaDetailsSelectorTitle = "div.post-title h3, div.post-title h1, #manga-title > h1, .manga-title, h1#mangaTitle"
    override val mangaDetailsSelectorAuthor = "div.author-content > a, div.manga-authors > a, .meta-item:contains(Author:) a"
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, .manga-about, .manga-description"

    override fun imageFromElement(element: Element): String? {
        val url = super.imageFromElement(element) ?: return null
        val isPage = element.parents().any { it.hasClass("reading-content") || it.hasClass("page-break") }
        if (isPage) {
            return url.replace(RESIZE_REGEX, "")
                .replace(SCALED_REGEX, "")
        }
        return url
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
        private val RESIZE_REGEX = """-\d+x\d+(?=\.(jpg|jpeg|png|webp))""".toRegex()
        private val SCALED_REGEX = """-scaled(?=\.(jpg|jpeg|png|webp))""".toRegex()
    }
}
