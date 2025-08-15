package eu.kanade.tachiyomi.multisrc.madarav2

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

abstract class Madarav4(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val pageSize: Int = 12
) : ParsedHttpSource() {

    override val supportsLatest: Boolean = true

    // Change these values only if the site does not support manga listings via ajax
    protected open val withoutAjax: Boolean = false
    protected open val authorSearchSupported: Boolean = false

    protected open val tagPrefix: String = "manga-genre/"
    protected open val datePattern: String = "MMMM d, yyyy"
    protected open val stylePage: String = "?style=list"
    protected open val postReq: Boolean = false

    // Status keywords in various languages
    private val ongoing: Set<String> = setOf(
        "مستمرة", "en curso", "ongoing", "on going", "ativo", "en cours", "en cours 🟢",
        "en cours de publication", "activo", "đang tiến hành", "em lançamento", "онгоінг",
        "publishing", "devam ediyor", "em andamento", "in corso", "güncel", "berjalan",
        "продолжается", "updating", "lançando", "in arrivo", "emision", "en emision",
        "مستمر", "curso", "en marcha", "publicandose", "publicando", "连载中"
    )

    private val finished: Set<String> = setOf(
        "completed", "complete", "completo", "complété", "fini", "achevé", "terminé",
        "terminé ⚫", "tamamlandı", "đã hoàn thành", "hoàn thành", "مكتملة", "завершено",
        "завершен", "finished", "finalizado", "completata", "one-shot", "bitti", "tamat",
        "completado", "concluído", "concluido", "已完结", "bitmiş", "end", "منتهية"
    )

    private val abandoned: Set<String> = setOf(
        "canceled", "cancelled", "cancelado", "cancellato", "cancelados",
        "dropped", "discontinued", "abandonné"
    )

    private val paused: Set<String> = setOf(
        "hiatus", "on hold", "pausado", "en espera", "en pause", "en attente"
    )

    private val upcoming: Set<String> = setOf(
        "upcoming", "لم تُنشَر بعد", "prochainement", "à venir"
    )

    // Selectors for manga details
    protected open val selectDesc: String = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, div.post-content div.manga-summary, div.post-content div.desc, div.c-page__content div.summary__content"
    protected open val selectGenre: String = "div.genres-content a"
    protected open val selectTestAsync: String = "div.listing-chapters_wrap"
    protected open val selectState: String = "div.post-content_item:contains(Status), div.post-content_item:contains(Statut), div.post-content_item:contains(État), div.post-content_item:contains(حالة العمل), div.post-content_item:contains(Estado), div.post-content_item:contains(สถานะ), div.post-content_item:contains(Stato), div.post-content_item:contains(Durum), div.post-content_item:contains(Statüsü), div.post-content_item:contains(Статус), div.post-content_item:contains(状态), div.post-content_item:contains(الحالة)"
    protected open val selectAlt: String = ".post-content_item:contains(Alt) .summary-content, .post-content_item:contains(Nomes alternativos: ) .summary-content"

    // Chapter selectors
    protected open val selectDate: String = "span.chapter-release-date i"
    protected open val selectChapter: String = "li.wp-manga-chapter"

    // Page selectors
    protected open val selectBodyPage: String = "div.main-col-inner div.reading-content"
    protected open val selectPage: String = "div.page-break"
    protected open val selectRequiredLogin: String = ".content-blocked, .login-required"

    protected open val postDataReq: String = "action=manga_get_chapters&manga="
    protected open val listUrl: String = "manga/"

    // Popular manga
    override fun popularMangaRequest(page: Int): Request {
        return if (withoutAjax) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                if (page > 1) addPathSegment("page").addPathSegment(page.toString())
                addQueryParameter("post_type", "wp-manga")
                addQueryParameter("m_orderby", "views")
            }.build()
            GET(url.toString(), headers)
        } else {
            val formBody = createAjaxFormBody(page).apply {
                add("vars[meta_key]", "_wp_manga_views")
                add("vars[orderby]", "meta_value_num")
                add("vars[order]", "desc")
            }.build()
            POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        }
    }

    override fun popularMangaSelector(): String = "div.row.c-tabs-item__content, div.page-item-detail"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val href = element.selectFirst("a")!!.attr("href")
            url = href.substringAfter(baseUrl)
            title = element.select(".tab-summary h3, .tab-summary h4, .manga-name, .post-title").first()?.text().orEmpty()
            thumbnail_url = element.selectFirst("img")?.attr("src")
            
            val summary = element.selectFirst(".tab-summary") ?: element.selectFirst(".item-summary")
            author = summary?.selectFirst(".mg_author a")?.ownText()
            
            val genresElement = summary?.selectFirst(".mg_genres")
            genre = genresElement?.select("a")?.joinToString { it.text() }
            
            val statusText = summary?.selectFirst(".mg_status .summary-content")?.ownText()?.lowercase()
            status = parseStatus(statusText)
        }
    }

    override fun popularMangaNextPageSelector(): String = "div.row.c-tabs-item__content, div.page-item-detail"

    // Latest manga
    override fun latestUpdatesRequest(page: Int): Request {
        return if (withoutAjax) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                if (page > 1) addPathSegment("page").addPathSegment(page.toString())
                addQueryParameter("post_type", "wp-manga")
                addQueryParameter("m_orderby", "latest")
            }.build()
            GET(url.toString(), headers)
        } else {
            val formBody = createAjaxFormBody(page).apply {
                add("vars[meta_key]", "_latest_update")
                add("vars[orderby]", "meta_value_num")
                add("vars[order]", "desc")
            }.build()
            POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        }
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // Search manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        
        if (withoutAjax) {
            if (page > 1) {
                url.addPathSegment("page").addPathSegment(page.toString())
            }
            
            url.addQueryParameter("s", query)
            url.addQueryParameter("post_type", "wp-manga")
            
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        filter.state.forEachIndexed { index, genre ->
                            if (genre.state) {
                                url.addQueryParameter("genre[]", genre.value)
                            }
                        }
                    }
                    is StatusFilter -> {
                        if (filter.state != 0) {
                            url.addQueryParameter("status[]", filter.values[filter.state])
                        }
                    }
                    is OrderByFilter -> {
                        url.addQueryParameter("m_orderby", filter.values[filter.state])
                    }
                }
            }
            
            return GET(url.build().toString(), headers)
        } else {
            val formBody = createAjaxFormBody(page).apply {
                if (query.isNotEmpty()) {
                    add("vars[s]", query)
                }
                
                filters.forEach { filter ->
                    when (filter) {
                        is GenreFilter -> {
                            val includedGenres = mutableListOf<String>()
                            val excludedGenres = mutableListOf<String>()
                            
                            filter.state.forEach { genre ->
                                when (genre.state) {
                                    Filter.TriState.STATE_INCLUDE -> includedGenres.add(genre.value)
                                    Filter.TriState.STATE_EXCLUDE -> excludedGenres.add(genre.value)
                                }
                            }
                            
                            if (includedGenres.isNotEmpty()) {
                                add("vars[tax_query][0][taxonomy]", "wp-manga-genre")
                                add("vars[tax_query][0][field]", "slug")
                                includedGenres.forEachIndexed { index, genre ->
                                    add("vars[tax_query][0][terms][$index]", genre)
                                }
                                add("vars[tax_query][0][operator]", "IN")
                            }
                            
                            if (excludedGenres.isNotEmpty()) {
                                add("vars[tax_query][1][taxonomy]", "wp-manga-genre")
                                add("vars[tax_query][1][field]", "slug")
                                excludedGenres.forEachIndexed { index, genre ->
                                    add("vars[tax_query][1][terms][$index]", genre)
                                }
                                add("vars[tax_query][1][operator]", "NOT IN")
                            }
                        }
                        is StatusFilter -> {
                            if (filter.state != 0) {
                                add("vars[meta_query][0][0][key]", "_wp_manga_status")
                                add("vars[meta_query][0][0][compare]", "IN")
                                add("vars[meta_query][0][0][value][]", filter.values[filter.state])
                            }
                        }
                        is OrderByFilter -> {
                            when (filter.values[filter.state]) {
                                "views" -> {
                                    add("vars[meta_key]", "_wp_manga_views")
                                    add("vars[orderby]", "meta_value_num")
                                    add("vars[order]", "desc")
                                }
                                "latest" -> {
                                    add("vars[meta_key]", "_latest_update")
                                    add("vars[orderby]", "meta_value_num")
                                    add("vars[order]", "desc")
                                }
                                "new-manga" -> {
                                    add("vars[orderby]", "date")
                                    add("vars[order]", "desc")
                                }
                                "alphabet" -> {
                                    add("vars[orderby]", "post_title")
                                    add("vars[order]", "asc")
                                }
                                "rating" -> {
                                    add("vars[meta_query][0][query_avarage_reviews][key]", "_manga_avarage_reviews")
                                    add("vars[meta_query][0][query_total_reviews][key]", "_manga_total_votes")
                                    add("vars[orderby][query_avarage_reviews]", "DESC")
                                    add("vars[orderby][query_total_reviews]", "DESC")
                                }
                            }
                        }
                    }
                }
            }.build()
            
            return POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        }
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Manga details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".summary_image img")?.attr("src")
            
            description = document.select(selectDesc).html()
            
            val genreElements = document.select(selectGenre)
            genre = genreElements.joinToString { it.text() }
            
            val altTitles = document.select(selectAlt).firstOrNull()?.text()
            if (!altTitles.isNullOrEmpty()) {
                description = "Alternative titles: $altTitles\n\n$description"
            }
            
            val statusElement = document.selectFirst(selectState)?.selectFirst("div.summary-content")
            status = parseStatus(statusElement?.text()?.lowercase())
            
            val authorElement = document.selectFirst(".author-content a, .artist-content a")
            author = authorElement?.text()
            artist = author
            
            // Check if adult content
            if (document.selectFirst(".adult-confirm") != null) {
                genre = if (genre.isNullOrEmpty()) "Adult" else "$genre, Adult"
            }
        }
    }

    // Chapter list
    override fun chapterListSelector(): String = selectChapter

    override fun chapterListRequest(manga: SManga): Request {
        val mangaUrl = "$baseUrl${manga.url}"
        
        return if (postReq) {
            // For sites that require POST request to get chapters
            GET(mangaUrl, headers)
        } else {
            // For sites with AJAX chapter loading
            val url = mangaUrl.removeSuffix("/") + "/ajax/chapters/"
            POST(url, headers, FormBody.Builder().build())
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val testAsync = document.select(selectTestAsync)
        
        return if (testAsync.isEmpty()) {
            // Direct parsing from the page
            parseChapterList(document)
        } else {
            // Need to make AJAX request for chapters
            val mangaId = document.select("div#manga-chapters-holder").attr("data-id")
            val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
            val formBody = FormBody.Builder()
                .add("action", "manga_get_chapters")
                .add("manga", mangaId)
                .build()
            
            val ajaxResponse = client.newCall(POST(ajaxUrl, headers, formBody)).execute()
            parseChapterList(ajaxResponse.asJsoup())
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val dateFormat = SimpleDateFormat(datePattern, Locale.getDefault())
        
        document.select(selectChapter).reversed().forEachIndexed { index, element ->
            val chapter = SChapter.create().apply {
                val a = element.selectFirst("a")!!
                url = a.attr("href").substringAfter(baseUrl) + stylePage
                name = a.selectFirst("p")?.text() ?: a.ownText()
                chapter_number = (index + 1).toFloat()
                
                val dateText = element.selectFirst("a.c-new-tag")?.attr("title") 
                    ?: element.selectFirst(selectDate)?.text()
                date_upload = parseChapterDate(dateFormat, dateText)
            }
            chapters.add(chapter)
        }
        
        return chapters
    }

    override fun chapterFromElement(element: Element): SChapter {
        throw UnsupportedOperationException("Use chapterListParse instead")
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        val chapterProtector = document.getElementById("chapter-protector-data")
        
        return if (chapterProtector != null) {
            // Encrypted pages
            parseEncryptedPages(chapterProtector)
        } else {
            // Regular pages
            if (document.selectFirst(selectRequiredLogin) != null) {
                throw Exception("Login required to read this chapter")
            }
            
            val root = document.selectFirst(selectBodyPage)
                ?: throw Exception("No images found")
            
            val pages = mutableListOf<Page>()
            root.select(selectPage).forEachIndexed { index, div ->
                div.select("img").forEach { img ->
                    val imageUrl = img.attr("src")
                    if (imageUrl.isNotEmpty()) {
                        pages.add(Page(index, "", imageUrl))
                    }
                }
            }
            pages
        }
    }

    private fun parseEncryptedPages(chapterProtector: Element): List<Page> {
        val chapterProtectorHtml = if (chapterProtector.attr("src").startsWith("data:text/javascript;base64,")) {
            val base64Data = chapterProtector.attr("src").substringAfter("data:text/javascript;base64,")
            String(Base64.decode(base64Data, Base64.DEFAULT))
        } else {
            chapterProtector.html()
        }

        val password = chapterProtectorHtml.substringAfter("wpmangaprotectornonce='").substringBefore("';")
        val chapterDataJson = chapterProtectorHtml.substringAfter("chapter_data='").substringBefore("';").replace("\\/", "/")
        val chapterData = JSONObject(chapterDataJson)
        
        val unsaltedCiphertext = Base64.decode(chapterData.getString("ct"), Base64.DEFAULT)
        val salt = chapterData.getString("s").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ciphertext = "Salted__".toByteArray() + salt + unsaltedCiphertext

        val decryptedData = decryptAES(Base64.encodeToString(ciphertext, Base64.DEFAULT), password)
        val imgArrayString = decryptedData.filter { it != '[' && it != ']' && it != '\\' && it != '"' }

        return imgArrayString.split(",").mapIndexed { index, url ->
            Page(index, "", url.trim())
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // Filters
    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<Any>>()
        
        if (authorSearchSupported) {
            filters.add(Filter.Header("Search by author name"))
        }
        
        filters.addAll(listOf(
            GenreFilter(getGenreList()),
      
