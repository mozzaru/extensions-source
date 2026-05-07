package eu.kanade.tachiyomi.extension.id.mgkomik

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.lib.randomua.UserAgentType
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class MGKomik :
    Madara(
        "MG Komik",
        "https://id.mgkomik.cc",
        "id",
        SimpleDateFormat("dd MMM yy", Locale.US),
    ),
    ConfigurableSource {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true
    override val mangaSubString = "komik"

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    // =============================== Requests ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "trending")
            .build()
        return GET(url, firstNavHeaders())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "latest")
            .build()
        return GET(url, firstNavHeaders())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (query.isNotBlank()) {
        super.searchMangaRequest(page, query, filters).addSameOriginNavHeaders()
    } else {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> if (filter.state != 0) url.addQueryParameter("m_orderby", filter.toUriPart())
                else -> {}
            }
        }
        GET(url.build(), firstNavHeaders())
    }

    override fun mangaDetailsRequest(manga: SManga): Request = super.mangaDetailsRequest(manga).addSameOriginNavHeaders()

    override fun chapterListRequest(manga: SManga): Request = super.chapterListRequest(manga).addSameOriginNavHeaders()

    override fun genresRequest(): Request = super.genresRequest().addSameOriginNavHeaders()

    override fun xhrChaptersRequest(mangaUrl: String): Request = POST(
        "$mangaUrl/ajax/chapters/",
        headers.newBuilder()
            .set("Referer", mangaUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .set("Origin", baseUrl)
            .removeAll("Sec-Fetch-User")
            .removeAll("Upgrade-Insecure-Requests")
            .build(),
    )

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterUrl = chapter.url.let {
            if (it.startsWith("http")) it else "$baseUrl$it"
        }
        val mangaUrl = chapterUrl.trimEnd('/')
            .substringBeforeLast("/")
            .trimEnd('/') + "/"

        return GET(
            chapterUrl,
            headers.newBuilder()
                .set("Referer", mangaUrl)
                .set("Cache-Control", "max-age=0")
                .set("Sec-Fetch-Dest", "document")
                .set("Sec-Fetch-Mode", "navigate")
                .set("Sec-Fetch-Site", "same-origin")
                .set("Sec-Fetch-User", "?1")
                .build(),
        )
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: page.url
        val imageHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .removeAll("Sec-Fetch-Dest")
            .removeAll("Sec-Fetch-Mode")
            .removeAll("Sec-Fetch-Site")
            .removeAll("Sec-Fetch-User")
            .removeAll("Upgrade-Insecure-Requests")
            .removeAll("Cache-Control")
            .removeAll("Priority")
            .removeAll("Sec-CH-UA-Arch")
            .removeAll("Sec-CH-UA-Bitness")
            .removeAll("Sec-CH-UA-Full-Version")
            .removeAll("Sec-CH-UA-Full-Version-List")
            .removeAll("Sec-CH-UA-Model")
            .removeAll("Sec-CH-UA-Platform-Version")
            .build()
        return GET(imageUrl, imageHeaders)
    }

    // =============================== Headers ================================

    private fun firstNavHeaders() = headers.newBuilder()
        .removeAll("Referer")
        .set("Cache-Control", "max-age=0")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .build()

    private fun Request.addSameOriginNavHeaders(): Request = newBuilder()
        .header("Referer", "$baseUrl/$mangaSubString/")
        .header("Cache-Control", "max-age=0")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-User", "?1")
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .setRandomUserAgent(userAgentType = UserAgentType.MOBILE)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .set("Accept-Language", "id-ID,id;q=0.9")
        .set("X-Requested-With", "com.android.chrome")
        .set("Upgrade-Insecure-Requests", "1")
        .set("Priority", "u=0, i")

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(UserAgentClientHintsInterceptor())
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val host = url.host
            val path = url.encodedPath
            val segments = url.pathSegments
            val newHeaders = request.headers.newBuilder()

            val isAjax = path.contains("admin-ajax.php") ||
                path.contains("wp-json") ||
                path.contains("/ajax/")

            val isImage = path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".png") || path.endsWith(".webp") ||
                path.endsWith(".gif") || path.contains("/thumbs/") ||
                request.header("Accept")?.startsWith("image/") == true

            // CDN image — domain berbeda dari baseUrl
            val isCdnImage = isImage && !host.contains("mgkomik.cc")

            val isReading = segments.size >= 4 && segments[0] == mangaSubString && segments[1] != "page"

            when {
                isAjax -> {
                    newHeaders.set("X-Requested-With", "XMLHttpRequest")
                    newHeaders.set("Sec-Fetch-Dest", "empty")
                    newHeaders.set("Sec-Fetch-Mode", "cors")
                    newHeaders.set("Sec-Fetch-Site", "same-origin")
                    newHeaders.set("Origin", baseUrl)
                    newHeaders.removeAll("Sec-Fetch-User")
                    newHeaders.removeAll("Upgrade-Insecure-Requests")
                }

                isCdnImage -> {
                    newHeaders.set("Referer", "$baseUrl/")
                    newHeaders.removeAll("Sec-Fetch-Dest")
                    newHeaders.removeAll("Sec-Fetch-Mode")
                    newHeaders.removeAll("Sec-Fetch-Site")
                    newHeaders.removeAll("Sec-Fetch-User")
                    newHeaders.removeAll("X-Requested-With")
                    newHeaders.removeAll("Cache-Control")
                    newHeaders.removeAll("Upgrade-Insecure-Requests")
                    newHeaders.removeAll("Sec-CH-UA-Arch")
                    newHeaders.removeAll("Sec-CH-UA-Bitness")
                    newHeaders.removeAll("Sec-CH-UA-Full-Version")
                    newHeaders.removeAll("Sec-CH-UA-Full-Version-List")
                    newHeaders.removeAll("Sec-CH-UA-Model")
                    newHeaders.removeAll("Sec-CH-UA-Platform-Version")
                }

                isReading -> {
                    // Keep X-Requested-With: com.android.chrome from headersBuilder to fix 403
                }

                else -> {
                    newHeaders.removeAll("X-Requested-With")
                    if (isImage) {
                        newHeaders.removeAll("Sec-Fetch-Dest")
                        newHeaders.removeAll("Sec-Fetch-Mode")
                        newHeaders.removeAll("Sec-Fetch-Site")
                        newHeaders.removeAll("Sec-Fetch-User")
                        newHeaders.removeAll("Upgrade-Insecure-Requests")
                        newHeaders.removeAll("Cache-Control")
                        newHeaders.removeAll("Priority")
                    }
                }
            }

            chain.proceed(request.newBuilder().headers(newHeaders.build()).build())
        }
        .rateLimit(3)
        .build()

    // =============================== Selectors ==============================

    override fun popularMangaSelector() = "div.page-item-detail.manga"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleLink = element.selectFirst(".post-title a")
        title = titleLink?.text()?.trim() ?: element.selectFirst("img")?.attr("alt")?.trim() ?: ""
        setUrlWithoutDomain(titleLink?.attr("abs:href").orEmpty())
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    override fun popularMangaNextPageSelector() = "div.wp-pagenavi a.page, div.wp-pagenavi a.last"

    override val mangaDetailsSelectorTitle = "div.post-title h1, div.post-title h3"
    override val mangaDetailsSelectorAuthor = "div.author-content > a"
    override val mangaDetailsSelectorStatus = "div.summary-heading:contains(Status) + div.summary-content"
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content p"
    override val mangaDetailsSelectorThumbnail = "div.summary_image img"
    override val mangaDetailsSelectorGenre = "div.genres-content a"

    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val urlElement = element.selectFirst(chapterUrlSelector)!!
        setUrlWithoutDomain(urlElement.attr("abs:href").substringBefore("?style=paged"))
        name = urlElement.text()
        date_upload = element.selectFirst("img:not(.thumb)")?.attr("alt")?.let { parseRelativeDate(it) }
            ?: element.selectFirst("span a")?.attr("title")?.let { parseRelativeDate(it) }
            ?: parseChapterDate(element.selectFirst(chapterDateSelector())?.text())
    }

    // ================================ Dates =================================

    override fun parseChapterDate(date: String?): Long {
        date ?: return 0L
        val trimmed = date.trim()

        if (trimmed.contains("ago", ignoreCase = true) || trimmed.contains("yang lalu", ignoreCase = true)) {
            return parseRelativeDate(trimmed)
        }

        val formats = listOf(
            SimpleDateFormat("dd MMM yy", Locale.US),
            SimpleDateFormat("dd MMM yyyy", Locale.US),
            SimpleDateFormat("dd/MM/yyyy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("MMMM d, yyyy", Locale.US),
        )

        for (sdf in formats) {
            try {
                sdf.isLenient = false
                sdf.parse(trimmed)?.let { return it.time }
            } catch (_: ParseException) {}
        }

        return super.parseChapterDate(trimmed)
    }

    // ================================ Filters ===============================

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

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) : UriPartFilter(title, options.toTypedArray())

    override fun parseGenres(document: Document): List<Genre> = buildList {
        add(Genre("All", ""))
        addAll(document.select(".row.genres li a").map { Genre(it.text(), it.absUrl("href")) })
    }

    // ================================ Preferences ===========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }

    /**
     * OkHttp Interceptor that adds Client Hints headers based on User-Agent
     */
    private class UserAgentClientHintsInterceptor : Interceptor {

        private val parser = UAParser()
        private val cache = ConcurrentHashMap<String, SecCHHeaders>(16, 0.75f)

        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val userAgent = originalRequest.header("User-Agent")
            val accept = originalRequest.header("Accept")

            if (userAgent.isNullOrEmpty() || accept?.startsWith("image/") == true) {
                return chain.proceed(originalRequest)
            }

            val secCHHeaders = cache.getOrPut(userAgent) {
                parser.parseUAtoSecCH(userAgent).also {
                    if (cache.size > 50) {
                        cache.keys.take(cache.size - 40).forEach { key -> cache.remove(key) }
                    }
                }
            }

            val newRequest = originalRequest.newBuilder()
                .header("Sec-CH-UA", secCHHeaders.secCHUA)
                .header("Sec-CH-UA-Mobile", secCHHeaders.secCHUAMobile)
                .header("Sec-CH-UA-Platform", secCHHeaders.secCHUAPlatform)
                .apply {
                    secCHHeaders.secCHUAModel?.let {
                        header("Sec-CH-UA-Model", "\"$it\"")
                    }
                    secCHHeaders.secCHUAPlatformVersion?.let {
                        header("Sec-CH-UA-Platform-Version", "\"$it\"")
                    }
                    secCHHeaders.secCHUAFullVersion?.let {
                        header("Sec-CH-UA-Full-Version", "\"$it\"")
                    }
                    secCHHeaders.secCHUAFullVersionList?.let {
                        header("Sec-CH-UA-Full-Version-List", it)
                    }
                }
                .build()

            return chain.proceed(newRequest)
        }
    }

    private data class SecCHHeaders(
        val secCHUA: String,
        val secCHUAMobile: String,
        val secCHUAPlatform: String,
        val secCHUAModel: String? = null,
        val secCHUAPlatformVersion: String? = null,
        val secCHUAFullVersion: String? = null,
        val secCHUAFullVersionList: String? = null,
    )

    private class UAParser {

        fun parseUAtoSecCH(ua: String): SecCHHeaders {
            val brands = mutableListOf<String>()
            val (platform, isMobile, platformVersion, model) = detectPlatform(ua)

            val fullVersion = extractVersion(ua, CHROME_FULL_VERSION_PATTERN) ?: (UNKNOWN_VERSION + ".0.0.0")
            val majorVersion = fullVersion.substringBefore(".")

            detectBrowserBrands(ua, brands, majorVersion)
            brands.add("\"Not.A/Brand\";v=\"$NOT_A_BRAND_VERSION\"")

            val fullBrands = brands.map { it.replace(majorVersion, fullVersion) }

            return SecCHHeaders(
                secCHUA = brands.joinToString(", "),
                secCHUAMobile = if (isMobile) "?1" else "?0",
                secCHUAPlatform = platform,
                secCHUAModel = model,
                secCHUAPlatformVersion = platformVersion,
                secCHUAFullVersion = fullVersion,
                secCHUAFullVersionList = fullBrands.joinToString(", "),
            )
        }

        private fun detectPlatform(ua: String): PlatformInfo = when {
            ua.contains("Windows NT 10.0") || ua.contains("Windows NT 11.0") -> PlatformInfo("\"Windows\"", false, "15.0.0")
            ua.contains("Macintosh") || ua.contains("Mac OS X") -> {
                val version = extractVersion(ua, MAC_OS_VERSION_PATTERN)?.replace("_", ".")
                PlatformInfo("\"macOS\"", false, version)
            }
            ua.contains("Android") -> {
                val version = extractVersion(ua, ANDROID_VERSION_PATTERN) ?: "11.0.0"
                val model = extractModel(ua)
                PlatformInfo("\"Android\"", true, version, model)
            }
            ua.contains("iPhone") || ua.contains("iPad") -> {
                val version = extractVersion(ua, IOS_VERSION_PATTERN)?.replace("_", ".")
                PlatformInfo("\"iOS\"", ua.contains("iPhone") || ua.contains("Mobile"), version)
            }
            ua.contains("Linux") -> PlatformInfo("\"Linux\"", ua.contains("Mobile"), null)
            else -> PlatformInfo("\"Windows\"", ua.contains("Mobile"), null)
        }

        private fun extractModel(ua: String): String? {
            val matcher = ANDROID_MODEL_PATTERN.matcher(ua)
            var model: String? = null
            while (matcher.find()) {
                val group = matcher.group(1)?.trim() ?: continue
                if (!group.startsWith("Android") && !group.contains("Build") && group != "wv") {
                    model = group
                }
            }
            return model
        }

        private fun detectBrowserBrands(ua: String, brands: MutableList<String>, version: String) {
            when {
                ua.contains("Edg/") -> {
                    brands.add("\"Chromium\";v=\"$version\"")
                    brands.add("\"Microsoft Edge\";v=\"$version\"")
                }
                ua.contains("OPR/") -> {
                    brands.add("\"Chromium\";v=\"$version\"")
                    brands.add("\"Opera\";v=\"$version\"")
                }
                ua.contains("Chrome") -> {
                    brands.add("\"Chromium\";v=\"$version\"")
                    brands.add("\"Google Chrome\";v=\"$version\"")
                }
                ua.contains("Firefox") -> {
                    brands.add("\"Firefox\";v=\"$version\"")
                }
                ua.contains("Safari") -> {
                    brands.add("\"Safari\";v=\"$version\"")
                }
                else -> {
                    brands.add("\"Chromium\";v=\"$version\"")
                }
            }
        }

        private fun extractVersion(ua: String, pattern: Pattern): String? = pattern.matcher(ua)
            .takeIf { it.find() }
            ?.group(1)

        private data class PlatformInfo(
            val platform: String,
            val isMobile: Boolean,
            val version: String?,
            val model: String? = null,
        )

        companion object {
            private const val UNKNOWN_VERSION = "147"
            private const val NOT_A_BRAND_VERSION = "8"
            private val MAC_OS_VERSION_PATTERN = Pattern.compile("Mac OS X (\\d+[._]\\d+)")
            private val ANDROID_VERSION_PATTERN = Pattern.compile("Android (\\d+)")
            private val ANDROID_MODEL_PATTERN = Pattern.compile("; ([^;)]+?)(?: Build/|\\)|;)")
            private val IOS_VERSION_PATTERN = Pattern.compile("OS (\\d+[._]\\d+)")
            private val CHROME_FULL_VERSION_PATTERN = Pattern.compile("Chrome/([\\d.]+)")
        }
    }
}
