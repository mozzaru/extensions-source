package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class Ikiru : ParsedHttpSource {

    override val name = "Ikiru"
    override val baseUrl = "https://01.ikiru.wtf"
    override val lang = "id"
    override val supportsLatest = true
    override val id = 1532456597012176985

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var nonce: String? = null

    // Get nonce for search functionality
    private fun getNonce(): String? {
        if (nonce == null) {
            try {
                val response = client.newCall(
                    GET("$baseUrl/ajax-call?type=search_form&action=get_nonce", headers)
                ).execute()
                val document = response.asJsoup()
                nonce = document.select("input[name=search_nonce]").attr("value")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return nonce
    }

    // Popular manga
    override fun popularMangaRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("action", "advanced_search")
            .add("page", page.toString())
            .add("orderby", "popular")
            .add("nonce", getNonce() ?: "")
            .build()

        return POST("$baseUrl/ajax-call", headers, formBody)
    }

    override fun popularMangaSelector() = "body > div"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val linkElement = element.selectFirst("a[href*='/manga/']") ?: return@apply
            url = linkElement.attr("href")
            title = element.selectFirst("a.text-base, a.text-white, h1")?.text()?.trim()
                ?: linkElement.attr("title").ifEmpty { linkElement.text() }
            thumbnail_url = element.selectFirst("img")?.attr("src")

            // Parse status
            val statusText = element.selectFirst("span.bg-accent, p:contains(Ongoing), p:contains(Completed)")
                ?.text()?.lowercase()
            status = when {
                statusText?.contains("ongoing") == true -> SManga.ONGOING
                statusText?.contains("completed") == true -> SManga.COMPLETED
                statusText?.contains("hiatus") == true -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun popularMangaNextPageSelector() = null

    // Latest manga
    override fun latestUpdatesRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("action", "advanced_search")
            .add("page", page.toString())
            .add("orderby", "updated")
            .add("nonce", getNonce() ?: "")
            .build()

        return POST("$baseUrl/ajax-call", headers, formBody)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBodyBuilder = FormBody.Builder()
            .add("action", "advanced_search")
            .add("page", page.toString())
            .add("nonce", getNonce() ?: "")

        if (query.isNotEmpty()) {
            formBodyBuilder.add("query", query)
        }

        var orderBy = "popular"

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val selectedGenres = filter.state.filter { it.state }.map { it.id }
                    if (selectedGenres.isNotEmpty()) {
                        formBodyBuilder.add("genre-relation", "AND")
                        val genreArray = JSONArray(selectedGenres)
                        formBodyBuilder.add("genre", genreArray.toString())
                    }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        val statusArray = JSONArray()
                        when (filter.state) {
                            1 -> statusArray.put("ongoing")
                            2 -> statusArray.put("completed")
                            3 -> statusArray.put("on-hiatus")
                        }
                        if (statusArray.length() > 0) {
                            formBodyBuilder.add("status", statusArray.toString())
                        }
                    }
                }
                is TypeFilter -> {
                    if (filter.state != 0) {
                        val typeArray = JSONArray()
                        when (filter.state) {
                            1 -> typeArray.put("manga")
                            2 -> typeArray.put("manhwa")
                            3 -> typeArray.put("manhua")
                            4 -> typeArray.put("comic")
                            5 -> typeArray.put("novel")
                        }
                        if (typeArray.length() > 0) {
                            formBodyBuilder.add("type", typeArray.toString())
                        }
                    }
                }
                is SortFilter -> {
                    orderBy = when (filter.state?.index) {
                        0 -> "popular"
                        1 -> "updated"
                        2 -> "title"
                        3 -> "rating"
                        else -> "popular"
                    }
                }
                is AuthorFilter -> {
                    if (filter.state.isNotEmpty()) {
                        val authorArray = JSONArray(filter.state)
                        formBodyBuilder.add("series-author", authorArray.toString())
                    }
                }
            }
        }

        formBodyBuilder.add("orderby", orderBy)

        return POST("$baseUrl/ajax-call", headers, formBodyBuilder.build())
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1[itemprop=name]")?.text() ?: ""

            // Alternative titles
            val altTitles = document.selectFirst("h1[itemprop=name]")?.nextElementSibling()?.text()
                ?.split(",")
                ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                ?.joinToString("; ")

            description = buildString {
                if (!altTitles.isNullOrEmpty()) {
                    append("Alternative titles: $altTitles\n\n")
                }
                
                val desc = document.select("div[itemprop=description]")
                    .joinToString("\n\n") { it.text() }
                    .trim()
                if (desc.isNotBlank()) {
                    append(desc)
                }
            }

            thumbnail_url = document.selectFirst("div[itemprop=image] > img")?.attr("src")

            // Genres
            genre = document.select("a[itemprop=genre]").joinToString { it.text() }

            // Status
            val statusText = findInfoText(document, "Status")?.lowercase()
            status = when {
                statusText?.contains("ongoing") == true -> SManga.ONGOING
                statusText?.contains("completed") == true -> SManga.COMPLETED
                statusText?.contains("hiatus") == true -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            // Author
            author = findInfoText(document, "Author")
        }
    }

    private fun findInfoText(document: Document, key: String): String? {
        return document.select("div.space-y-2 > .flex:has(h4)")
            .find { it.selectFirst("h4")?.text()?.contains(key, ignoreCase = true) == true }
            ?.selectFirst("p.font-normal")?.text()
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaUrl = response.request.url.toString()
        
        // Extract manga ID
        val mangaId = document.selectFirst("[hx-get*='manga_id=']")
            ?.attr("hx-get")
            ?.substringAfter("manga_id=")
            ?.substringBefore("&")
            ?.trim()
            ?: document.selectFirst("input#manga_id, [data-manga-id]")
                ?.let { it.attr("value").ifEmpty { it.attr("data-manga-id") } }
            ?: mangaUrl.substringAfterLast("/manga/").substringBefore("/")

        return loadAllChapters(mangaId, mangaUrl)
    }

    private fun loadAllChapters(mangaId: String, refererUrl: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1

        val requestHeaders = Headers.Builder()
            .add("hx-request", "true")
            .add("Referer", refererUrl)
            .build()

        while (true) {
            try {
                val url = "$baseUrl/ajax-call?manga_id=$mangaId&page=$page&action=chapter_list"
                val response = client.newCall(GET(url, requestHeaders)).execute()
                val document = response.asJsoup()

                val chapterElements = document.select("div#chapter-list > div[data-chapter-number]")
                if (chapterElements.isEmpty()) break

                chapterElements.forEach { element ->
                    val linkElement = element.selectFirst("a") ?: return@forEach
                    val chapterUrl = linkElement.attr("href")
                    if (chapterUrl.isBlank()) return@forEach

                    val chapterTitle = element.selectFirst("div.font-medium span")?.text()?.trim() ?: ""
                    val dateText = element.selectFirst("time")?.text()
                    val number = element.attr("data-chapter-number").toFloatOrNull() ?: -1f

                    chapters.add(
                        SChapter.create().apply {
                            name = chapterTitle.ifEmpty { "Chapter ${number.toInt()}" }
                            url = chapterUrl
                            chapter_number = number
                            date_upload = parseDate(dateText)
                        }
                    )
                }

                page++
                if (page > 100) break // Safety limit
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }

        return chapters.reversed()
    }

    override fun chapterListSelector(): String = throw UnsupportedOperationException("Not used")
    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        return document.select("main section section > img").mapIndexed { index, img ->
            Page(index, "", img.attr("src"))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // Filters
    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>()
        
        filters.add(Filter.Header("Search Filters"))
        filters.add(SortFilter())
        filters.add(StatusFilter())
        filters.add(TypeFilter())
        filters.add(AuthorFilter())
        filters.add(GenreFilter(getGenreList()))

        return FilterList(filters)
    }

    private fun getGenreList(): List<Tag> {
        // This would normally be fetched from the site, but for simplicity we'll use a static list
        return listOf(
            Tag("action", "Action"),
            Tag("adventure", "Adventure"),
            Tag("comedy", "Comedy"),
            Tag("drama", "Drama"),
            Tag("ecchi", "Ecchi"),
            Tag("fantasy", "Fantasy"),
            Tag("harem", "Harem"),
            Tag("horror", "Horror"),
            Tag("isekai", "Isekai"),
            Tag("magic", "Magic"),
            Tag("martial-arts", "Martial Arts"),
            Tag("mystery", "Mystery"),
            Tag("psychological", "Psychological"),
            Tag("romance", "Romance"),
            Tag("school-life", "School Life"),
            Tag("sci-fi", "Sci-Fi"),
            Tag("shounen", "Shounen"),
            Tag("slice-of-life", "Slice of Life"),
            Tag("supernatural", "Supernatural"),
            Tag("thriller", "Thriller"),
        )
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0

        return try {
            when {
                dateStr.contains("ago") -> {
                    val number = Regex("""(\d+)""").find(dateStr)?.value?.toIntOrNull() ?: return 0
                    val cal = Calendar.getInstance()
                    when {
                        dateStr.contains("min") -> cal.apply { add(Calendar.MINUTE, -number) }
                        dateStr.contains("hour") -> cal.apply { add(Calendar.HOUR, -number) }
                        dateStr.contains("day") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }
                        dateStr.contains("week") -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }
                        dateStr.contains("month") -> cal.apply { add(Calendar.MONTH, -number) }
                        dateStr.contains("year") -> cal.apply { add(Calendar.YEAR, -number) }
                        else -> cal
                    }.timeInMillis
                }
                else -> {
                    SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).parse(dateStr)?.time ?: 0
                }
            }
        } catch (e: Exception) {
            0
        }
    }

    // Filter classes
    class SortFilter : Filter.Sort(
        "Sort by",
        arrayOf("Popular", "Latest", "A-Z", "Rating"),
        Selection(0, false)
    )
    
    class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed", "On Hiatus"))
    
    class TypeFilter : Filter.Select<String>("Type", arrayOf("All", "Manga", "Manhwa", "Manhua", "Comic", "Novel"))
    
    class AuthorFilter : Filter.Text("Author")
    
    class GenreFilter(genres: List<Tag>) : Filter.Group<Tag>("Genres", genres)
    
    class Tag(val id: String, name: String) : Filter.CheckBox(name)
}
