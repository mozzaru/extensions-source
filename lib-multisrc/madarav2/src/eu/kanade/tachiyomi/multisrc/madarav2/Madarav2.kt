package eu.kanade.tachiyomi.multisrc.madarav2

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

abstract class Madarav2(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : ParsedHttpSource() {

    override val supportsLatest = true

    protected open fun searchPage(page: Int): String = if (page > 1) "page/$page/" else ""

    protected open val mangaSubString: String = "manga"

    protected open val chapterUrlSuffix: String = "?style=list"

    protected open val selectDesc = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, div.post-content div.manga-summary, div.post-content div.desc, div.c-page__content div.summary__content"
    protected open val selectGenre = "div.genres-content a"
    protected open val selectAlt = ".post-content_item:contains(Alt) .summary-content, .post-content_item:contains(Nomes alternativos: ) .summary-content"
    protected open val selectState = "div.post-content_item:contains(Status), div.post-content_item:contains(Statut), div.post-content_item:contains(État), div.post-content_item:contains(Estado), div.post-content_item:contains(สถานะ), div.post-content_item:contains(Stato), div.post-content_item:contains(Durum), div.post-content_item:contains(Statüsü), div.post-content_item:contains(Статус), div.post-content_item:contains(状态), div.post-content_item:contains(الحالة)"

    protected open val ongoingKeywords = listOf("ongoing", "en cours", "مستمرة", "em andamento", "aktif", "devam ediyor")
    protected open val completedKeywords = listOf("completed", "complet", "مكتملة", "tamamlandı", "bitti")
    protected open val hiatusKeywords = listOf("hiatus", "on hold", "pausado")
    protected open val canceledKeywords = listOf("canceled", "cancelled", "cancelado")

    open class Genre(name: String, val slug: String) : Filter.CheckBox(name)
    open class GenreFilter(val genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)

    open class Status(val value: String, name: String) : Filter.CheckBox(name)
    open class StatusFilter(val statuses: List<Status>) : Filter.Group<Status>("Status", statuses)

    open class ContentRating(name: String, val slug: String) : Filter.CheckBox(name)
    open class ContentRatingFilter(val ratings: List<ContentRating>) : Filter.Group<ContentRating>("Content Rating", ratings)

    protected open fun getGenreList(): List<Genre> = emptyList()
    protected open fun getStatusList(): List<Status> = listOf(
        Status("ongoing", "Ongoing"),
        Status("completed", "Completed"),
        Status("hiatus", "Hiatus"),
        Status("canceled", "Canceled")
    )
    protected open fun getRatingList(): List<ContentRating> = listOf(
        ContentRating("Safe", "safe"),
        ContentRating("Suggestive", "suggestive"),
        ContentRating("NSFW", "nsfw")
    )

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("NOTE: Ignored if using text search!"),
        GenreFilter(getGenreList()),
        StatusFilter(getStatusList()),
        ContentRatingFilter(getRatingList())
    )

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=views", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.page-item-detail, div.row.c-tabs-item__content").map(::popularMangaFromElement)
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst(".post-title a, .manga-title a")
        val imageElement = element.selectFirst("img")

        manga.setUrlWithoutDomain(titleElement?.attr("href") ?: "")
        manga.title = titleElement?.text() ?: ""
        manga.thumbnail_url = imageElement?.absUrl("src")

        return manga
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/?s=$query&post_type=wp-manga&page=$page", headers)
        }

        val form = FormBody.Builder()
            .add("action", "madara_load_more")
            .add("page", "$page")
            .add("template", "madara-core/content/content-archive")
            .add("vars[post_type]", "wp-manga")
            .add("vars[paged]", "$page")
            .add("vars[posts_per_page]", "20")

        filters.filterIsInstance<GenreFilter>().firstOrNull()?.state?.filter { it.state }?.forEach {
            form.add("vars[tax_query][0][taxonomy]", "wp-manga-genre")
            form.add("vars[tax_query][0][field]", "slug")
            form.add("vars[tax_query][0][terms][]", it.slug)
            form.add("vars[tax_query][0][operator]", "IN")
        }

        filters.filterIsInstance<StatusFilter>().firstOrNull()?.state?.filter { it.state }?.forEachIndexed { index, status ->
            form.add("vars[meta_query][$index][key]", "_wp_manga_status")
            form.add("vars[meta_query][$index][value]", status.value)
            form.add("vars[meta_query][$index][compare]", "=")
        }

        filters.filterIsInstance<ContentRatingFilter>().firstOrNull()?.state?.filter { it.state }?.forEachIndexed { index, rating ->
            form.add("vars[tax_query][1][taxonomy]", "wp-manga-content-rating")
            form.add("vars[tax_query][1][field]", "slug")
            form.add("vars[tax_query][1][terms][]", rating.slug)
            form.add("vars[tax_query][1][operator]", "IN")
        }

        return POST("$baseUrl/wp-admin/admin-ajax.php", headers, form.build())
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("h1")?.text() ?: ""
        manga.author = document.select("div.author-content > a").joinToString { it.text() }
        manga.artist = document.select("div.artist-content > a").joinToString { it.text() }
        manga.description = document.select(selectDesc).text()
        manga.thumbnail_url = document.selectFirst("div.summary_image img")?.absUrl("src")

        val statusText = document.select(selectState).text().lowercase(Locale.ROOT)
        manga.status = when {
            ongoingKeywords.any { statusText.contains(it) } -> SManga.ONGOING
            completedKeywords.any { statusText.contains(it) } -> SManga.COMPLETED
            hiatusKeywords.any { statusText.contains(it) } -> SManga.ON_HIATUS
            canceledKeywords.any { statusText.contains(it) } -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val altText = document.select(selectAlt).text()
        if (altText.isNotBlank()) manga.description += "\n\nAlternative Name: $altText"

        val genres = document.select(selectGenre).mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
        manga.genre = genres.joinToString()
        manga.genre = if (genres.any { it.equals("adult", ignoreCase = true) }) manga.genre + ", Adult" else manga.genre

        return manga
    }

    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.selectFirst("a")
        val url = link?.attr("href") ?: ""
        chapter.setUrlWithoutDomain(url + chapterUrlSuffix)
        chapter.name = link?.text() ?: ""

        val dateText = element.selectFirst("span.chapter-release-date i")?.text()
        chapter.date_upload = parseChapterDate(dateText)
        return chapter
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select(".reading-content img, .page-break img").mapIndexed { i, element ->
            Page(i, document.location(), element.absUrl("src"))
        }
    }

    override fun imageUrlParse(document: Document): String = ""

    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)

    private fun parseChapterDate(date: String?): Long {
        date ?: return 0L
        return try {
            dateFormat.parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
