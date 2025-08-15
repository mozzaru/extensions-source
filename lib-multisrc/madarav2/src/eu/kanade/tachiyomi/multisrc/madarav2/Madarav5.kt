package eu.kanade.tachiyomi.multisrc.madarav2

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

abstract class Madarav5(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US),
) : ParsedHttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // Change these values only if the site does not support manga listings via ajax
    protected open val withoutAjax = false
    protected open val authorSearchSupported = false

    protected open val tagPrefix = "manga-genre/"
    protected open val datePattern = "MMMM d, yyyy"
    protected open val stylePage = "?style=list"
    protected open val postReq = false

    // Selectors
    protected open val selectDesc =
        "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, div.post-content div.manga-summary, div.post-content div.desc, div.c-page__content div.summary__content"
    protected open val selectGenre = "div.genres-content a"
    protected open val selectTestAsync = "div.listing-chapters_wrap"
    protected open val selectState =
        "div.post-content_item:contains(Status), div.post-content_item:contains(Statut), " +
            "div.post-content_item:contains(État), div.post-content_item:contains(حالة العمل), div.post-content_item:contains(Estado), div.post-content_item:contains(สถานะ)," +
            "div.post-content_item:contains(Stato), div.post-content_item:contains(Durum), div.post-content_item:contains(Statüsü), div.post-content_item:contains(Статус)," +
            "div.post-content_item:contains(状态), div.post-content_item:contains(الحالة)"
    protected open val selectAlt =
        ".post-content_item:contains(Alt) .summary-content, .post-content_item:contains(Nomes alternativos: ) .summary-content"
    protected open val selectDate = "span.chapter-release-date i"
    protected open val selectChapter = "li.wp-manga-chapter"
    protected open val selectBodyPage = "div.main-col-inner div.reading-content"
    protected open val selectPage = "div.page-break"
    protected open val selectRequiredLogin = ".content-blocked, .login-required"

    protected open val postDataReq = "action=manga_get_chapters&manga="
    protected open val listUrl = "manga/"

    // Status sets
    private val ongoing = setOf(
        "مستمرة",
        "en curso",
        "ongoing",
        "on going",
        "ativo",
        "en cours",
        "en cours \uD83D\uDFE2",
        "en cours de publication",
        "activo",
        "đang tiến hành",
        "em lançamento",
        "онгоінг",
        "publishing",
        "devam ediyor",
        "em andamento",
        "in corso",
        "güncel",
        "berjalan",
        "продолжается",
        "updating",
        "lançando",
        "in arrivo",
        "emision",
        "en emision",
        "مستمر",
        "curso",
        "en marcha",
        "publicandose",
        "publicando",
        "连载中",
    )

    private val finished = setOf(
        "completed",
        "complete",
        "completo",
        "complété",
        "fini",
        "achevé",
        "terminé",
        "terminé ⚫",
        "tamamlandı",
        "đã hoàn thành",
        "hoàn thành",
        "مكتملة",
        "завершено",
        "завершен",
        "finished",
        "finalizado",
        "completata",
        "one-shot",
        "bitti",
        "tamat",
        "completado",
        "concluído",
        "concluido",
        "已完结",
        "bitmiş",
        "end",
        "منتهية",
    )

    private val abandoned = setOf(
        "canceled",
        "cancelled",
        "cancelado",
        "cancellato",
        "cancelados",
        "dropped",
        "discontinued",
        "abandonné",
    )

    private val paused = setOf(
        "hiatus",
        "on hold",
        "pausado",
        "en espera",
        "en pause",
        "en attente",
    )

    private val upcoming = setOf(
        "upcoming",
        "لم تُنشَر بعد",
        "prochainement",
        "à venir",
    )

    // Popular manga
    override fun popularMangaRequest(page: Int): Request {
        return if (withoutAjax) {
            val url = "$baseUrl/page/${page + 1}/?s=&post_type=wp-manga&m_orderby=views"
            GET(url, headers)
        } else {
            val payload = createRequestTemplate()
            payload["page"] = page.toString()
            payload["vars[meta_key]"] = "_wp_manga_views"
            payload["vars[orderby]"] = "meta_value_num"
            payload["vars[order]"] = "desc"

            val formBody = FormBody.Builder()
            payload.forEach { (key, value) ->
                formBody.add(key, value)
            }

            POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody.build())
        }
    }

    override fun popularMangaSelector() = "div.row.c-tabs-item__content, div.page-item-detail"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val summary = element.selectFirst(".tab-summary") ?: element.selectFirst(".item-summary")

        manga.url = element.selectFirst("a")!!.attr("href")
        manga.title = (summary?.selectFirst("h3, h4") ?: element.selectFirst(".manga-name, .post-title"))?.text()
            ?: ""
        manga.thumbnail_url = element.selectFirst("img")?.attr("src")
            ?: element.selectFirst("img")?.attr("data-src")

        return manga
    }

    override fun popularMangaNextPageSelector(): String? = "a.next"

    // Latest manga
    override fun latestUpdatesRequest(page: Int): Request {
        return if (withoutAjax) {
            val url = "$baseUrl/page/${page + 1}/?s=&post_type=wp-manga&m_orderby=latest"
            GET(url, headers)
        } else {
            val payload = createRequestTemplate()
            payload["page"] = page.toString()
            payload["vars[meta_key]"] = "_latest_update"
            payload["vars[orderby]"] = "meta_value_num"
            payload["vars[order]"] = "desc"

            val formBody = FormBody.Builder()
            payload.forEach { (key, value) ->
                formBody.add(key, value)
            }

            POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody.build())
        }
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (withoutAjax) {
            val url = buildString {
                append(baseUrl)
                if (page > 0) {
                    append("/page/")
                    append((page + 1).toString())
                }
                append("/?s=")
                append(URLEncoder.encode(query, "UTF-8"))
                append("&post_type=wp-manga")

                filters.forEach { filter ->
                    when (filter) {
                        is GenreFilter -> {
                            filter.state.forEach {
                                if (it.state) {
                                    append("&genre[]=")
                                    append(it.id)
                                }
                            }
                        }
                        is StatusFilter -> {
                            if (filter.state != 0) {
                                append("&status[]=")
                                append(getStatusList()[filter.state])
                            }
                        }
                        is OrderByFilter -> {
                            append("&m_orderby=")
                            append(getOrderByList()[filter.state])
                        }
                        is YearFilter -> {
                            if (filter.state.isNotEmpty()) {
                                append("&release=")
                                append(filter.state)
                            }
                        }
                        is AuthorFilter -> {
                            if (filter.state.isNotEmpty()) {
                                append("&author=")
                                append(filter.state.lowercase().replace(" ", "-"))
                            }
                        }
                        is AdultContentFilter -> {
                            if (filter.state != 0) {
                                append("&adult=")
                                append(if (filter.state == 1) "0" else "1")
                            }
                        }
                    }
                }
            }
            GET(url, headers)
        } else {
            val payload = createRequestTemplate()
            payload["page"] = page.toString()

            if (query.isNotEmpty()) {
                payload["vars[s]"] = URLEncoder.encode(query, "UTF-8")
            }

            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        val includedGenres = filter.state.filter { it.state }.map { it.id }
                        if (includedGenres.isNotEmpty()) {
                            payload["vars[tax_query][0][taxonomy]"] = "wp-manga-genre"
                            payload["vars[tax_query][0][field]"] = "slug"
                            includedGenres.forEachIndexed { i, genre ->
                                payload["vars[tax_query][0][terms][$i]"] = genre
                            }
                            payload["vars[tax_query][0][operator]"] = "IN"
                        }
                    }
                    is StatusFilter -> {
                        if (filter.state != 0) {
                            payload["vars[meta_query][0][0][key]"] = "_wp_manga_status"
                            payload["vars[meta_query][0][0][compare]"] = "IN"
                            payload["vars[meta_query][0][0][value][]"] = getStatusList()[filter.state]
                        }
                    }
                    is OrderByFilter -> {
                        when (getOrderByList()[filter.state]) {
                            "views" -> {
                                payload["vars[meta_key]"] = "_wp_manga_views"
                                payload["vars[orderby]"] = "meta_value_num"
                                payload["vars[order]"] = "desc"
                            }
                            "latest" -> {
                                payload["vars[meta_key]"] = "_latest_update"
                                payload["vars[orderby]"] = "meta_value_num"
                                payload["vars[order]"] = "desc"
                            }
                            "new-manga" -> {
                                payload["vars[orderby]"] = "date"
                                payload["vars[order]"] = "desc"
                            }
                            "alphabet" -> {
                                payload["vars[orderby]"] = "post_title"
                                payload["vars[order]"] = "asc"
                            }
                            "rating" -> {
                                payload["vars[meta_query][0][query_avarage_reviews][key]"] = "_manga_avarage_reviews"
                                payload["vars[meta_query][0][query_total_reviews][key]"] = "_manga_total_votes"
                                payload["vars[orderby][query_avarage_reviews]"] = "DESC"
                                payload["vars[orderby][query_total_reviews]"] = "DESC"
                            }
                        }
                    }
                    is YearFilter -> {
                        if (filter.state.isNotEmpty()) {
                            payload["vars[tax_query][2][taxonomy]"] = "wp-manga-release"
                            payload["vars[tax_query][2][field]"] = "slug"
                            payload["vars[tax_query][2][terms][]"] = filter.state
                        }
                    }
                    is AdultContentFilter -> {
                        if (filter.state != 0) {
                            payload["vars[meta_query][0][1][key]"] = "manga_adult_content"
                            payload["vars[meta_query][0][1][value]"] = if (filter.state == 1) "" else "a%3A1%3A%7Bi%3A0%3Bs%3A3%3A%22yes%22%3B%7D"
                        }
                    }
                }
            }

            val formBody = FormBody.Builder()
            payload.forEach { (key, value) ->
                formBody.add(key, value)
            }

            POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody.build())
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        manga.title = document.selectFirst("h1")?.text() ?: ""
        manga.description = document.select(selectDesc).html()

        // Author
        val author = document.selectFirst(".fmed b:contains(Author) + span, .imptdt:contains(Author) i")?.text()
        manga.author = author

        // Artist
        val artist = document.selectFirst(".fmed b:contains(Artist) + span, .imptdt:contains(Artist) i")?.text()
        manga.artist = artist

        // Status
        val stateDiv = document.selectFirst(selectState)?.selectLast("div.summary-content")
        stateDiv?.let {
            manga.status = when (it.text().lowercase()) {
                in ongoing -> SManga.ONGOING
                in finished -> SManga.COMPLETED
                in abandoned -> SManga.CANCELLED
                in paused -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }

        // Genres
        val genres = document.select(selectGenre).map { it.text() }
        manga.genre = genres.joinToString()

        // Thumbnail
        manga.thumbnail_url = document.selectFirst(".summary_image img")?.attr("src")
            ?: document.selectFirst(".summary_image img")?.attr("data-src")

        return manga
    }

    // Chapter list
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val testCheckAsync = document.select(selectTestAsync)

        return if (testCheckAsync.isNullOrEmpty()) {
            loadChapters(response.request.url.toString(), document)
        } else {
            getChapters(document)
        }
    }

    override fun chapterListSelector() = selectChapter

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val a = element.selectFirst("a")!!

        chapter.url = a.attr("href") + stylePage
        chapter.name = a.selectFirst("p")?.text() ?: a.ownText()

        val dateText = element.selectFirst("a.c-new-tag")?.attr("title")
            ?: element.selectFirst(selectDate)?.text()
        chapter.date_upload = parseChapterDate(dateText)

        return chapter
    }

    protected open fun getChapters(document: Document): List<SChapter> {
        return document.select(selectChapter).map { chapterFromElement(it) }.reversed()
    }

    protected open fun loadChapters(mangaUrl: String, document: Document): List<SChapter> {
        val doc = if (postReq) {
            val mangaId = document.select("div#manga-chapters-holder").attr("data-id")
            val url = "$baseUrl/wp-admin/admin-ajax.php"
            val postData = postDataReq + mangaId
            val formBody = FormBody.Builder()
                .add("action", "manga_get_chapters")
                .add("manga", mangaId)
                .build()
            client.newCall(POST(url, headers, formBody)).execute().asJsoup()
        } else {
            val url = mangaUrl.removeSuffix('/') + "/ajax/chapters/"
            client.newCall(POST(url, headers, FormBody.Builder().build())).execute().asJsoup()
        }
        return doc.select(selectChapter).map { chapterFromElement(it) }.reversed()
    }

    // Page list
    override fun pageListParse(document: Document): List<Page> {
        val chapterProtector = document.getElementById("chapter-protector-data")

        return if (chapterProtector == null) {
            if (document.selectFirst(selectRequiredLogin) != null) {
                throw Exception("Login required")
            } else {
                val root = document.selectFirst(selectBodyPage)
                    ?: throw Exception("No image found, try to log in")

                val pages = mutableListOf<Page>()
                root.select(selectPage).forEach { div ->
                    div.select("img").forEachIndexed { index, img ->
                        val imageUrl = img.attr("src").ifEmpty { img.attr("data-src") }
                        pages.add(Page(pages.size, "", imageUrl))
                    }
                }
                pages
            }
        } else {
            val chapterProtectorHtml = if (chapterProtector.attr("src").startsWith("data:text/javascript;base64,")) {
                val base64Data = chapterProtector.attr("src").substringAfter("data:text/javascript;base64,")
                String(Base64.decode(base64Data, Base64.DEFAULT))
            } else {
                chapterProtector.html()
            }

            val password = chapterProtectorHtml.substringAfter("wpmangaprotectornonce='").substringBefore("';")
            val chapterData = JSONObject(
                chapterProtectorHtml.substringAfter("chapter_data='").substringBefore("';").replace("\\/", "/"),
            )

            val unsaltedCiphertext = Base64.decode(chapterData.getString("ct"), Base64.DEFAULT)
            val salt = chapterData.getString("s").decodeHex()
            val ciphertext = "Salted__".toByteArray() + salt + unsaltedCiphertext

            val rawImgArray = CryptoAES.decrypt(Base64.encodeToString(ciphertext, Base64.DEFAULT), password)
            val imgArrayString = rawImgArray.filterNot { c -> c == '[' || c == ']' || c == '\\' || c == '"' }

            imgArrayString.split(",").mapIndexed { index, url ->
                Page(index, "", url.trim())
            }
        }
    }

    // Image
    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // Filters
    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>()

        filters.add(Filter.Header("Filters"))
        filters.add(GenreFilter(getGenreList()))
        filters.add(StatusFilter(getStatusList()))
        filters.add(OrderByFilter(getOrderByList()))
        filters.add(YearFilter())
        if (authorSearchSupported) {
            filters.add(AuthorFilter())
        }
        filters.add(AdultContentFilter())

        return FilterList(filters)
    }

    private fun getGenreList(): List<Genre> = emptyList() // Override in implementation

    private fun getStatusList(): Array<String> = arrayOf(
        "All",
        "on-going",
        "end",
        "canceled",
        "on-hold",
        "upcoming",
    )

    private fun getOrderByList(): Array<String> = arrayOf(
        "latest",
        "views",
        "new-manga",
        "alphabet",
        "rating",
    )

    // Utility functions
    private fun parseChapterDate(dateStr: String?): Long {
        return when {
            dateStr == null -> 0L
            dateStr.contains("ago") || dateStr.contains("atrás") -> parseRelativeDate(dateStr)
            dateStr.contains("yesterday") -> {
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            dateStr.contains("today") -> {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            dateStr.contains(Regex("""\d(st|nd|rd|th)""")) -> {
                val cleanDate = dateStr.split(" ").map {
                    if (it.contains(Regex("""\d\D\D"""))) {
                        it.replace(Regex("""\D"""), "")
                    } else {
                        it
                    }
                }.joinToString(" ")
                try {
                    dateFormat.parse(cleanDate)?.time ?: 0L
                } catch (e: ParseException) {
                    0L
                }
            }
            else -> {
                try {
                    dateFormat.parse(dateStr)?.time ?: 0L
                } catch (e: ParseException) {
                    0L
                }
            }
        }
    }

    private fun parseRelativeDate(date: String): Long {
        val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()

        return when {
            date.contains("second") || date.contains("segundo") ->
                cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
            date.contains("minute") || date.contains("minuto") || date.contains("min") ->
                cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            date.contains("hour") || date.contains("hora") || date.contains("h") ->
                cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            date.contains("day") || date.contains("día") || date.contains("d") ->
                cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            date.contains("month") || date.contains("mes") ->
                cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            date.contains("year") || date.contains("año") ->
                cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
            else -> 0L
        }
    }

    private fun createRequestTemplate(): MutableMap<String, String> {
        return mutableMapOf(
            "action" to "madara_load_more",
            "page" to "0",
            "template" to "madara-core/content/content-search",
            "vars[s]" to "",
            "vars[paged]" to "1",
            "vars[template]" to "search",
            "vars[meta_query][0][relation]" to "AND",
            "vars[meta_query][relation]" to "AND",
            "vars[post_type]" to "wp-manga",
            "vars[post_status]" to "publish",
            "vars[manga_archives_item_layout]" to "default",
        )
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    // Filter classes
    class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)
    class Genre(name: String, val id: String) : Filter.CheckBox(name)

    class StatusFilter(statuses: Array<String>) : Filter.Select<String>("Status", statuses)
    class OrderByFilter(orders: Array<String>) : Filter.Select<String>("Order By", orders)
    class YearFilter : Filter.Text("Year")
    class AuthorFilter : Filter.Text("Author")
    class AdultContentFilter : Filter.Select<String>("Adult Content", arrayOf("All", "Non-Adult", "Adult"))
}

// Crypto helper class
object CryptoAES {
    fun decrypt(data: String, password: String): String {
        val encryptedData = Base64.decode(data, Base64.DEFAULT)

        // Extract salt (first 8 bytes after "Salted__")
        val salt = encryptedData.sliceArray(8..15)
        val ciphertext = encryptedData.sliceArray(16 until encryptedData.size)

        // Derive key and IV using EVP_BytesToKey equivalent
        val keyIv = deriveKeyAndIv(password.toByteArray(), salt, 32, 16)
        val key = keyIv.sliceArray(0..31)
        val iv = keyIv.sliceArray(32..47)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decrypted = cipher.doFinal(ciphertext)

        return String(decrypted)
    }

    private fun deriveKeyAndIv(password: ByteArray, salt: ByteArray, keyLength: Int, ivLength: Int): ByteArray {
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val derivedBytes = ByteArray(keyLength + ivLength)
        var currentHash = byteArrayOf()
        var currentIndex = 0

        while (currentIndex < derivedBytes.size) {
            md5.reset()
            md5.update(currentHash)
            md5.update(password)
            md5.update(salt)
            currentHash = md5.digest()

            val bytesToCopy = minOf(currentHash.size, derivedBytes.size - currentIndex)
            System.arraycopy(currentHash, 0, derivedBytes, currentIndex, bytesToCopy)
            currentIndex += bytesToCopy
        }

        return derivedBytes
    }
}
