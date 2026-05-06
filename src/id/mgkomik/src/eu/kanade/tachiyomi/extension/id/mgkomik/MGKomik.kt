package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MGKomik : ParsedHttpSource() {

    override val name = "MG Komik"
    override val baseUrl = "https://id.mgkomik.cc"
    override val lang = "id"
    override val supportsLatest = true

    private val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
    private val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale("id"))
    private val dateFormatAlt = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Accept-Language", "id-ID,id;q=0.9")
        .add("Referer", "$baseUrl/")
        .add("Upgrade-Insecure-Requests", "1")
        .add("sec-ch-ua", "\"Chromium\";v=\"147\", \"Not.A/Brand\";v=\"8\"")
        .add("sec-ch-ua-mobile", "?1")
        .add("sec-ch-ua-platform", "\"Android\"")
        .add("sec-fetch-dest", "document")
        .add("sec-fetch-mode", "navigate")
        .add("sec-fetch-site", "same-origin")
        .add("sec-fetch-user", "?1")

    //  POPULAR

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/komik/".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "trending")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "div.page-item-detail"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst("div.post-title a, h3.h5 a")?.let {
            title = it.text().trim()
            setUrlWithoutDomain(it.attr("abs:href"))
        }
        thumbnail_url = element.selectFirst("div.item-thumb img.img-responsive")
            ?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
    }

    override fun popularMangaNextPageSelector() = "div.wp-pagenavi a.nextpostslink, a.next.page-numbers"

    //  LATEST

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/komik/".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "latest")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    //  SEARCH

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith(URL_SEARCH_PREFIX)) {
        val url = query.removePrefix(URL_SEARCH_PREFIX)
        client.newCall(GET("$baseUrl/$url", headers))
            .asObservableSuccess()
            .map { response ->
                MangasPage(listOf(mangaDetailsParse(response.asJsoup())), false)
            }
    } else {
        client.newCall(searchRequest(page, query, filters))
            .asObservableSuccess()
            .map { searchMangaParse(it) }
    }

    private fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        var genre = ""
        var orderBy = ""
        var status = ""
        var type = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> genre = filter.selected()
                is SortFilter -> orderBy = filter.selected()
                is StatusFilter -> status = filter.selected()
                is TypeFilter -> type = filter.selected()
                else -> {}
            }
        }

        return if (query.isNotBlank()) {
            val body = FormBody.Builder()
                .add("action", "wp-manga-search-manga")
                .add("title", query)
                .build()
            POST(ajaxUrl, headers, body)
        } else {
            val urlBuilder = "$baseUrl/komik/".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
            if (genre.isNotEmpty()) urlBuilder.addQueryParameter("genre[]", genre)
            if (orderBy.isNotEmpty()) urlBuilder.addQueryParameter("m_orderby", orderBy)
            if (status.isNotEmpty()) urlBuilder.addQueryParameter("status", status)
            if (type.isNotEmpty()) urlBuilder.addQueryParameter("type", type)
            GET(urlBuilder.build(), headers)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaParse(response: Response): MangasPage {
        val contentType = response.header("Content-Type", "")
        if (contentType?.contains("json") == true) {
            val json = response.body.string()
            if (json.contains("\"data\":[")) {
                val mangas = mutableListOf<SManga>()
                val regex = Regex(""""title":"([^"]+)","url":"([^"]+)"""")
                regex.findAll(json).forEach { match ->
                    mangas.add(
                        SManga.create().apply {
                            title = match.groupValues[1]
                                .replace("\\/", "/")
                                .replace("\\u0026", "&")
                            setUrlWithoutDomain(
                                match.groupValues[2].replace("\\/", "/")
                            )
                        },
                    )
                }
                return MangasPage(mangas, false)
            }
            return MangasPage(emptyList(), false)
        }
        return super.searchMangaParse(response)
    }

    //  MANGA DETAIL

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        // Cover
        thumbnail_url = document.selectFirst(
            "div.summary_image a img, div.tab-summary img.img-responsive",
        )?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }

        title = document.selectFirst("div.post-title h1, div.post-title h3")
            ?.text()?.trim() ?: ""

        val metaRows = document.select(
            "div.post-content_item, div.post-status div.post-content_item",
        )

        var altTitle = ""

        for (row in metaRows) {
            val heading = row.selectFirst(".summary-heading h5")?.text()
                ?.trim()?.lowercase() ?: continue
            val content = row.selectFirst(".summary-content")

            when {
                heading.contains("author") || heading.contains("pengarang") ->
                    author = content?.select("a")?.joinToString { it.text().trim() }
                        ?: content?.text()?.trim() ?: ""

                heading.contains("artist") ->
                    artist = content?.select("a")?.joinToString { it.text().trim() }
                        ?: content?.text()?.trim() ?: ""

                heading.contains("genre") || heading.contains("kategori") ->
                    genre = content?.select("a")?.joinToString { it.text().trim() }
                        ?: content?.text()?.trim() ?: ""

                heading.contains("status") ->
                    status = parseStatus(content?.text()?.trim())

                heading.contains("alternative") || heading.contains("judul lain") ||
                heading.contains("alt") ->
                        altTitle = content?.text()?.trim() ?: ""
            }
        }

        val desc = document.selectFirst("div.description-summary div.summary__content")
            ?.text()?.trim()
            ?: document.selectFirst("div.summary__content")?.text()?.trim()
            ?: ""

        description = buildString {
            if (desc.isNotEmpty()) append(desc)
            if (altTitle.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Judul Alternatif: ")
                append(altTitle)
            }
        }
    }

    private fun parseStatus(text: String?): Int = when (text?.lowercase()) {
        "ongoing", "on going", "berlangsung", "berjalan" -> SManga.ONGOING
        "completed", "complete", "selesai", "tamat" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    //  CHAPTER LIST

    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val mangaUrl = "$baseUrl${manga.url}"
        return client.newCall(GET(mangaUrl, headers))
            .asObservableSuccess()
            .flatMap { response ->
                val doc = response.asJsoup()
                val postId = doc.selectFirst("div#manga-chapters-holder")
                    ?.attr("data-id")
                    ?.ifBlank { null }

                if (postId != null) {
                    val body = FormBody.Builder()
                        .add("action", "manga_get_chapters")
                        .add("manga", postId)
                        .build()
                    client.newCall(POST(ajaxUrl, headers, body))
                        .asObservableSuccess()
                        .map { ajaxResp ->
                            val ajaxDoc = ajaxResp.asJsoup()
                            ajaxDoc.select(chapterListSelector()).map { chapterFromElement(it) }
                        }
                } else {
                    Observable.just(
                        doc.select(chapterListSelector()).map { chapterFromElement(it) }
                    )
                }
            }
    }

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        element.selectFirst("a")?.let { a ->
            name = a.text().trim()
            setUrlWithoutDomain(a.attr("abs:href"))
        }
        date_upload = element.selectFirst("span.chapter-release-date i, span.chapter-release-date")
            ?.text()?.trim()
            ?.let { parseDate(it) } ?: 0L
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { dateFormat.parse(dateStr)?.time }
            .getOrNull()
            ?: runCatching { dateFormatAlt.parse(dateStr)?.time }
                .getOrNull()
            ?: 0L
    }

    //  PAGE LIST

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.reading-content img.wp-manga-chapter-img")
            .mapIndexed { index, img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.trim()
                Page(index, "", url)
            }
    }

    override fun imageUrlParse(document: Document) = ""

    //  FILTERS

    override fun getFilterList() = FilterList(
        Filter.Header("Filter tidak berlaku saat pencarian teks"),
        Filter.Separator(),
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    private class SortFilter : SelectFilter(
        "Urutkan",
        listOf(
            Pair("Default", ""),
            Pair("A-Z", "alphabet"),
            Pair("Rating", "rating"),
            Pair("Trending", "trending"),
            Pair("Views", "views"),
            Pair("New", "new-manga"),
            Pair("Latest", "latest"),
        )
    )

    private class StatusFilter : SelectFilter(
        "Status",
        listOf(
            Pair("Semua", ""),
            Pair("Ongoing", "on-going"),
            Pair("Completed", "end"),
        )
    )

    private class TypeFilter : SelectFilter(
        "Tipe",
        listOf(
            Pair("Semua", ""),
            Pair("Manga", "manga"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhua", "manhua"),
        )
    )

    private class GenreFilter : SelectFilter(
        "Genre",
        listOf(
            Pair("Semua", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mature", "mature"),
            Pair("Mecha", "mecha"),
            Pair("Mystery", "mystery"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("School Life", "school-life"),
            Pair("Sci-fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural"),
            Pair("Tragedy", "tragedy"),
            Pair("Webtoon", "webtoon"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    )

    open class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
    ) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        fun selected() = options[state].second
    }

    companion object {
        const val URL_SEARCH_PREFIX = "url:"
    }
}
