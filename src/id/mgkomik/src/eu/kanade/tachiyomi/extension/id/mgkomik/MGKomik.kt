package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class MGKomik : HttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")

    // Popular / Latest
    override fun popularMangaRequest(page: Int): Request = comicsRequest(page, orderBy = "trending")

    override fun popularMangaParse(response: Response): MangasPage = mangaListParse(response)

    override fun latestUpdatesRequest(page: Int): Request = comicsRequest(page, orderBy = "latest")

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    private fun comicsRequest(
        page: Int,
        orderBy: String,
        filter: String = "",
        completed: Boolean = false,
        project: Boolean = false,
    ): Request {
        val url = "$baseUrl/komik/".toHttpUrl().newBuilder()
            .addQueryParameter("filter", filter)
            .addQueryParameter("order_by", orderBy)
            .addQueryParameter("page", page.toString())

        if (completed) {
            url.addQueryParameter("completed", "1")
        }
        if (project) {
            url.addQueryParameter("project", "1")
        }

        return GET(url.build(), headers)
    }

    private fun mangaListParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("a[href] img").mapNotNull { image ->
            val link = image.parents().firstOrNull { it.tagName() == "a" && it.absUrl("href").isMangaUrl() }
                ?: return@mapNotNull null

            mangaFromElement(link, image)
        }.distinctBy { it.url }

        val nextPage = response.request.url.queryParameter("page")?.toIntOrNull()?.plus(1) ?: 2
        val hasNextPage = document.select("a[href]").any { element ->
            val href = element.absUrl("href")
            element.text().equals("Next", ignoreCase = true) || href.contains("page=$nextPage")
        }

        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(link: Element, image: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(link.absUrl("href"))
        title = link.attr("title")
            .ifBlank { image.attr("alt") }
            .ifBlank { link.text().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty() }
            .ifBlank { url.trim('/').substringAfterLast('/').replace('-', ' ').titleCase() }
            .cleanText()
        thumbnail_url = image.imageUrl()
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selectedValue().orEmpty().ifBlank { "latest" }
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selectedValue().orEmpty()
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selectedValue().orEmpty()
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedValue().orEmpty()
        val selectedFilter = type.ifBlank { genre }

        if (query.isNotBlank() || status == "on-going") {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())

            if (query.isNotBlank()) {
                url.addQueryParameter("q", query)
            }
            if (status == "on-going") {
                url.addQueryParameter("status", status)
            }
            if (selectedFilter.isNotBlank()) {
                url.addQueryParameter("filter", selectedFilter)
            }

            return GET(url.build(), headers)
        }

        return comicsRequest(
            page = page,
            orderBy = sort,
            filter = selectedFilter,
            completed = status == "completed",
            project = status == "project",
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response)

    //  Manga Details
    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url.toSourcePath()

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url.toSourcePath(), headers)

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: document.title().substringBefore(" - ")

        return SManga.create().apply {
            this.title = title.cleanText()
            thumbnail_url = document.select("img").firstOrNull { image ->
                val src = image.imageUrl()
                val alt = image.attr("alt")
                src.isNotBlank() && !src.isNonContentImage() &&
                    (alt.contains(title, ignoreCase = true) || src.contains("/wp-content/uploads/"))
            }?.imageUrl()
            author = document.extractField("Author")
            artist = document.extractField("Artist")
            status = parseStatus(document.detailText(title))
            genre = document.select("a[href*=\"/komik/?filter=\"]")
                .drop(3) // Header menu has Manga / Manhua / Manhwa before the real detail tags.
                .map { it.text().substringBefore("(").trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
            description = document.descriptionText()
        }
    }

    private fun parseStatus(text: String): Int = when {
        ONGOING_STATUS_REGEX.containsMatchIn(text) -> SManga.ONGOING
        COMPLETED_STATUS_REGEX.containsMatchIn(text) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun Document.detailText(title: String): String {
        val wholeText = body().wholeText()
        return wholeText.substringAfter(title, wholeText).substringBefore("Sinopsis", wholeText)
    }

    private fun Document.descriptionText(): String {
        val wholeText = body().wholeText()
        return wholeText.substringAfter("Sinopsis", "")
            .substringBefore("Baca Pertama")
            .substringBefore("Daftar Chapter")
            .replace("Baca Selengkapnya", "")
            .cleanText()
    }

    private fun Document.extractField(fieldName: String): String? {
        val fieldRegex = Regex("""(?im)^\s*$fieldName:\s*(.+)$""")
        return fieldRegex.find(body().wholeText())?.groupValues?.get(1)?.trim()
    }

    // Chapters
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.toSourcePath()

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url.toSourcePath(), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("a[href]")
            .mapNotNull { link ->
                val url = link.absUrl("href")
                if (!url.isChapterUrl()) return@mapNotNull null

                val rawName = link.text().trim()
                if (!rawName.contains("Chapter", ignoreCase = true)) return@mapNotNull null

                SChapter.create().apply {
                    setUrlWithoutDomain(url)
                    name = rawName.cleanChapterName(url)
                    chapter_number = chapterNumberFromNameOrUrl(name, url)
                    date_upload = parseChapterDate(link.parent()?.text().orEmpty())
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        chapter.chapter_number = chapterNumberFromNameOrUrl(chapter.name, chapter.url)
    }

    // Pages
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val chapterUrl = response.request.url.toString()
        return document.select("img").mapNotNull { image ->
            val imageUrl = image.imageUrl()
            if (!image.isPageImage(imageUrl)) return@mapNotNull null
            imageUrl
        }.distinct().mapIndexed { index, imageUrl ->
            Page(index, chapterUrl, imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", page.url.ifBlank { "$baseUrl/" })
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    // Filters
    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Pencarian teks memakai halaman /search/. Filter mengikuti parameter situs MGKOMIK v2."),
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )
}
