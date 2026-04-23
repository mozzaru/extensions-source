package eu.kanade.tachiyomi.extension.id.inazumanga

import eu.kanade.tachiyomi.network.GET
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

    override val client = network.cloudflareClient

    private val dateFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val startIndex = (page - 1) * 20 + 1
        val url = "$baseUrl/feeds/posts/default/-/Series".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "20")
            .addQueryParameter("start-index", startIndex.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val startIndex = (page - 1) * 20 + 1
        val url = "$baseUrl/feeds/posts/default/-/Series".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("orderby", "published")
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
            url.addPathSegment("-")
            url.addPathSegment("Series")
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
                    url = entry.link?.firstOrNull { it.rel == "alternate" }?.href?.substringAfter(baseUrl) ?: ""
                    thumbnail_url = entry.mediaThumbnail?.url?.replace(Regex("/s\\d+(-c)?/"), "/s600/")
                        ?: entry.content?.t?.let { Jsoup.parse(it).selectFirst("img")?.attr("src") }
                }
            }

        val hasNextPage = entries.size == 20
        return MangasPage(mangas, hasNextPage)
    }

    // Details
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("#post-title")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("#syn_bod")?.text()

            val excludedGenres = listOf("Series", "Ongoing", "Completed", "Project")
            genre = document.select("a[rel=tag]")
                .map { it.text() }
                .filterNot { g ->
                    excludedGenres.any { it.equals(g, true) } || g.equals(title, true) || g.toDoubleOrNull() != null
                }
                .distinct()
                .joinToString { it }

            author = document.selectFirst("#tauthers, #tauther")?.text() ?: document.selectFirst("span:contains(Author:) + span")?.text()
            artist = document.selectFirst("#tartists, #tartist")?.text() ?: document.selectFirst("span:contains(Artist:) + span")?.text()

            val altName = document.selectFirst("#talternatives, #talternative")?.text()
            if (!altName.isNullOrBlank()) {
                description = "Alternative: $altName\n\n$description"
            }

            val statusText = document.select(".capitalize").firstOrNull {
                val text = it.text().lowercase()
                text.contains("ongoing") || text.contains("completed")
            }?.text()
            status = when (statusText?.lowercase()) {
                "ongoing", "berjalan" -> SManga.ONGOING
                "completed", "selesai", "tamat" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val title = document.selectFirst("#post-title")?.text() ?: ""
        val label = document.selectFirst(".chapter_get")?.attr("data-labelchapter")
            ?: document.select("a[rel=tag]").map { it.text() }.firstOrNull { it.equals(title, true) }
            ?: throw Exception("Failed to find chapter identifier")

        val chapters = mutableListOf<SChapter>()
        var startIndex = 1

        while (true) {
            val chapterUrl = "$baseUrl/feeds/posts/default/-/Chapter".toHttpUrl().newBuilder()
                .addPathSegment(label)
                .addQueryParameter("alt", "json")
                .addQueryParameter("max-results", "150")
                .addQueryParameter("start-index", startIndex.toString())
                .build()

            val result = client.newCall(GET(chapterUrl, headers)).execute().parseAs<BloggerDto>()
            val entries = result.feed?.entry.orEmpty()
            if (entries.isEmpty()) break

            chapters.addAll(
                entries.map { entry ->
                    SChapter.create().apply {
                        name = cleanChapterName(title, entry.title?.t ?: "")
                        url = entry.link?.firstOrNull { it.rel == "alternate" }?.href?.substringAfter(baseUrl) ?: ""
                        date_upload = parseDate(entry.published?.t)
                    }
                },
            )

            val totalResults = result.feed?.totalResults?.t?.toIntOrNull() ?: 0
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

        // Fallback: try to remove manga title variations and common separators
        var name = entryTitle
        val titleVariations = mutableListOf(mangaTitle)
        if (mangaTitle.contains(":")) titleVariations.add(mangaTitle.substringBefore(":"))
        if (mangaTitle.contains("(")) titleVariations.add(mangaTitle.substringBefore("("))

        // Sort by length descending to replace longest variations first
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
        return document.select(".i_img img").mapIndexed { i, img ->
            Page(i, "", img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("Gunakan filter untuk mencari berdasarkan genre"),
        GenreList(getGenreListData()),
    )

    private class GenreData(val name: String, val value: String)
    private class GenreList(genres: List<GenreData>) : Filter.Group<GenreCheckbox>("Genre", genres.map { GenreCheckbox(it.name, it.value) })
    private class GenreCheckbox(name: String, val value: String) : Filter.CheckBox(name)

    private fun getGenreListData() = listOf(
        GenreData("Action", "Action"),
        GenreData("Adventure", "Adventure"),
        GenreData("Comedy", "Comedy"),
        GenreData("Drama", "Drama"),
        GenreData("Fantasy", "Fantasy"),
        GenreData("Magic", "Magic"),
        GenreData("Martial Arts", "Martial Arts"),
        GenreData("Romance", "Romance"),
        GenreData("Sci-Fi", "Sci-Fi"),
        GenreData("Slice of Life", "Slice of Life"),
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
