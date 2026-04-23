package eu.kanade.tachiyomi.extension.id.inazumanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ReYume : HttpSource() {

    override val name = "ReYume"
    override val baseUrl = "https://www.re-yume.my.id"
    override val lang = "id"
    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    private val dateFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/", headers)
    } else {
        val startIndex = (page - 1) * 20 + 1
        val url = "$baseUrl/feeds/posts/default/-/Series".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "20")
            .addQueryParameter("start-index", startIndex.toString())
            .build()
        GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        if (url == "$baseUrl/" || url == "$baseUrl") {
            val document = response.asJsoup()
            val mangas = document.select("#Side .group").map { element ->
                SManga.create().apply {
                    val a = element.selectFirst("a:has(h3)")
                        ?: element.selectFirst("a[href][title]")
                    title = element.selectFirst("h3")?.text() ?: a?.attr("title") ?: ""
                    val href = a?.attr("abs:href") ?: ""
                    this.url = if (href.startsWith(baseUrl)) {
                        "/" + href.substringAfter(baseUrl).removePrefix("/")
                    } else {
                        href
                    }
                    thumbnail_url = element.selectFirst("a[style*='background-image']")?.attr("style")?.let { style ->
                        Regex("""url\(['"]?(.+?)['"]?\)""").find(style)?.groupValues?.get(1)
                            ?.replace(Regex("/s\\d+(-c)?/"), "/s600/")
                    }
                }
            }.filter { it.title.isNotBlank() }
            return MangasPage(mangas, false)
        }
        return searchMangaParse(response)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val startIndex = (page - 1) * 20 + 1
        val url = "$baseUrl/feeds/posts/default/-/Series".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("orderby", "updated")
            .addQueryParameter("max-results", "20")
            .addQueryParameter("start-index", startIndex.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val startIndex = (page - 1) * 20 + 1
        val url = "$baseUrl/feeds/posts/default".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "20")
            .addQueryParameter("start-index", startIndex.toString())

        if (query.isNotBlank()) {
            url.addPathSegment("-")
            url.addPathSegment("Series")
            url.addQueryParameter("q", query)
        } else {
            val genres = filters.filterIsInstance<GenreList>().flatMap { it.state }.filter { it.state }.map { it.value }
            val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.let {
                if (it.state > 0) it.values[it.state] else null
            }
            url.addPathSegment("-")
            url.addPathSegment("Series")
            if (type != null) url.addPathSegment(type)
            genres.forEach { url.addPathSegment(it) }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<BloggerDto>()
        val entries = result.feed?.entry.orEmpty()

        val mangas = entries
            .filter { entry -> entry.category.orEmpty().any { it.term == "Series" } }
            .map { entry ->
                SManga.create().apply {
                    title = entry.title?.t ?: ""
                    url = entry.link?.firstOrNull { it.rel == "alternate" }?.href?.let { href ->
                        if (href.startsWith(baseUrl)) {
                            "/" + href.substringAfter(baseUrl).removePrefix("/")
                        } else {
                            href
                        }
                    } ?: ""
                    thumbnail_url = entry.mediaThumbnail?.url?.replace(Regex("/s\\d+(-c)?/"), "/s600/")
                        ?: entry.content?.t?.let { Jsoup.parse(it).selectFirst("img")?.attr("src") }
                }
            }

        val hasNextPage = entries.size == 20
        return MangasPage(mangas, hasNextPage)
    }

    // Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$baseUrl/feeds/posts/default".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("path", manga.url)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<BloggerDto>()
        val entry = result.feed?.entry?.firstOrNull() ?: throw Exception("Manga tidak ditemukan")
        val titleStr = entry.title?.t ?: ""
        val content = entry.content?.t ?: ""
        val document = Jsoup.parse(content)

        return SManga.create().apply {
            title = titleStr
            thumbnail_url = entry.mediaThumbnail?.url?.replace(Regex("/s\\d+(-c)?/"), "/s600/")
                ?: document.selectFirst("img")?.attr("src")
            description = document.selectFirst("#syn_bod")?.text() ?: document.text()

            val categories = entry.category.orEmpty().map { it.term }
            val excludedLabels = listOf("Series", "Ongoing", "Completed", "Project", "Manga", "Manhwa", "Manhua", "Chapter", "Hot", "New", "JP", "CN")

            genre = categories
                .filterNot { g ->
                    excludedLabels.any { it.equals(g, true) } || g.equals(titleStr, true) || g.toDoubleOrNull() != null
                }
                .distinct()
                .joinToString { it }

            author = document.selectFirst("#tauthers, #tauther")?.text() ?: document.selectFirst("span:contains(Author:) + span")?.text()
            artist = document.selectFirst("#tartists, #tartist")?.text() ?: document.selectFirst("span:contains(Artist:) + span")?.text()

            val altName = document.selectFirst("#talternatives, #talternative")?.text()
            if (!altName.isNullOrBlank()) {
                description = "Alternative: $altName\n\n$description"
            }

            status = when {
                categories.any { it.equals("Ongoing", true) } -> SManga.ONGOING
                categories.any { it.equals("Completed", true) } -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<BloggerDto>()
        val entry = result.feed?.entry?.firstOrNull() ?: throw Exception("Manga tidak ditemukan")
        val titleStr = entry.title?.t ?: ""
        val content = entry.content?.t ?: ""
        val document = Jsoup.parse(content)

        val excludedLabels = listOf("Series", "Ongoing", "Completed", "Project", "Manga", "Manhwa", "Manhua", "Chapter", "Hot", "New", "JP", "CN")
        val label = document.selectFirst(".chapter_get")?.attr("data-labelchapter")
            ?: entry.category.orEmpty().map { it.term }
                .firstOrNull { it !in excludedLabels && it != titleStr && it.toDoubleOrNull() == null }
            ?: titleStr

        val chapters = mutableListOf<SChapter>()
        var startIndex = 1

        while (true) {
            val chapterUrl = "$baseUrl/feeds/posts/default/-/Chapter".toHttpUrl().newBuilder()
                .addPathSegment(label)
                .addQueryParameter("alt", "json")
                .addQueryParameter("max-results", "150")
                .addQueryParameter("start-index", startIndex.toString())
                .build()

            val chapterResponse = client.newCall(GET(chapterUrl, headers)).execute()
            val chapterResult = chapterResponse.parseAs<BloggerDto>()
            val entries = chapterResult.feed?.entry.orEmpty()
            if (entries.isEmpty()) break

            chapters.addAll(
                entries.map { e ->
                    SChapter.create().apply {
                        name = cleanChapterName(titleStr, e.title?.t ?: "")
                        url = e.link?.firstOrNull { it.rel == "alternate" }?.href?.let { href ->
                            if (href.startsWith(baseUrl)) {
                                "/" + href.substringAfter(baseUrl).removePrefix("/")
                            } else {
                                href
                            }
                        } ?: ""
                        date_upload = parseDate(e.published?.t)
                    }
                },
            )

            val totalResults = chapterResult.feed?.totalResults?.t?.toIntOrNull() ?: 0
            if (startIndex + entries.size > totalResults) break
            startIndex += entries.size
        }

        return chapters
    }

    private fun cleanChapterName(mangaTitle: String, entryTitle: String): String {
        val chapterKeywords = listOf("Chapter ", "Ch.", "Ch ", "Prolog", "Epilog", "Ending", "End", "Tamat")
        for (keyword in chapterKeywords) {
            val index = entryTitle.lastIndexOf(keyword, ignoreCase = true)
            if (index != -1) {
                return entryTitle.substring(index).trim()
            }
        }

        var name = entryTitle
        val titleVariations = mutableListOf(mangaTitle)
        if (mangaTitle.contains(":")) titleVariations.add(mangaTitle.substringBefore(":"))
        if (mangaTitle.contains("(")) titleVariations.add(mangaTitle.substringBefore("("))

        titleVariations.sortByDescending { it.length }

        for (variation in titleVariations.filter { it.length > 3 }) {
            if (name.contains(variation, ignoreCase = true)) {
                name = name.replace(variation, "", ignoreCase = true)
            }
        }

        return name.trim()
            .removePrefix("-").removePrefix(":").trim()
            .ifBlank { entryTitle }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        val cleanedDate = dateStr.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        return runCatching { dateFormatter.parse(cleanedDate)?.time }.getOrNull()
            ?: runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()).parse(cleanedDate)?.time }.getOrNull()
            ?: 0L
    }

    // Pages
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select(".i_img img, a[imageanchor] img, .separator a img, .post-body img").mapIndexed { i, img ->
            val url = img.parent()?.takeIf { it.tagName() == "a" }?.attr("abs:href")
                ?.takeIf { it.contains(Regex("\\.(jpg|jpeg|png|webp|gif|bmp)(\\?.*)?$", RegexOption.IGNORE_CASE)) }
                ?: img.attr("abs:src")
            Page(i, "", url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("Gunakan filter untuk mencari berdasarkan genre"),
        TypeFilter(),
        GenreList(getGenreListData()),
    )

    private class TypeFilter : Filter.Select<String>("Type", arrayOf("All", "Manga", "Manhwa", "Manhua"))

    private class GenreData(val name: String, val value: String)
    private class GenreList(genres: List<GenreData>) : Filter.Group<GenreCheckbox>("Genre", genres.map { GenreCheckbox(it.name, it.value) })
    private class GenreCheckbox(name: String, val value: String) : Filter.CheckBox(name)

    private fun getGenreListData() = listOf(
        GenreData("Action", "Action"),
        GenreData("Adventure", "Adventure"),
        GenreData("Comedy", "Comedy"),
        GenreData("Drama", "Drama"),
        GenreData("Ecchi", "Ecchi"),
        GenreData("Fantasy", "Fantasy"),
        GenreData("Gore", "Gore"),
        GenreData("Harem", "Harem"),
        GenreData("Horror", "Horror"),
        GenreData("Isekai", "Isekai"),
        GenreData("Magic", "Magic"),
        GenreData("Martial Arts", "Martial Arts"),
        GenreData("Mystery", "Mystery"),
        GenreData("Psychological", "Psychological"),
        GenreData("Romance", "Romance"),
        GenreData("School Life", "School Life"),
        GenreData("Sci-Fi", "Sci-Fi"),
        GenreData("Seinen", "Seinen"),
        GenreData("Shoujo", "Shoujo"),
        GenreData("Shounen", "Shounen"),
        GenreData("Slice of Life", "Slice of Life"),
        GenreData("Supernatural", "Supernatural"),
        GenreData("Thriller", "Thriller"),
        GenreData("Tragedy", "Tragedy"),
        GenreData("Yuri", "Yuri"),
    )

    @Serializable
    data class BloggerDto(val feed: BloggerFeedDto? = null)

    @Serializable
    data class BloggerFeedDto(
        val entry: List<BloggerEntryDto>? = emptyList(),
        @SerialName("openSearch\$totalResults") val totalResults: BloggerTDto? = null,
    )

    @Serializable
    data class BloggerEntryDto(
        val title: BloggerTDto? = null,
        val published: BloggerTDto? = null,
        val link: List<BloggerLinkDto>? = emptyList(),
        val category: List<BloggerCategoryDto>? = emptyList(),
        val content: BloggerTDto? = null,
        @SerialName("media\$thumbnail") val mediaThumbnail: BloggerThumbnailDto? = null,
    )

    @Serializable
    data class BloggerTDto(@SerialName("\$t") val t: String)

    @Serializable
    data class BloggerLinkDto(val rel: String, val href: String)

    @Serializable
    data class BloggerCategoryDto(val term: String)

    @Serializable
    data class BloggerThumbnailDto(val url: String)
}
