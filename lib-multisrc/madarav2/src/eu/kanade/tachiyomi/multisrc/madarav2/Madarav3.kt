package eu.kanade.tachiyomi.multisrc.madarav2

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.HttpSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.random.Random

/**
 * Madara base (Tachiyomi) — port lengkap setara Kotatsu MadaraParser.
 *
 * Fitur:
 * - Popular/Latest/Search list
 * - Detail manga (desc/genre/author/status/thumbnail)
 * - Chapter list via HTML & AJAX (/wp-admin/admin-ajax.php, /ajax/chapters/)
 * - Page list (reader) dari <img>, dari skrip inline (array/atob), hingga protector (AES/Base64)
 * - Header custom + X-Requested-With random (sekali per instance)
 * - Tanggal relatif & multi-format
 *
 * Cara pakai: Buat ekstensi seperti MgKomik : Madara(...), override properti/selector bila perlu.
 */
abstract class Madarav3(
    override val name: String,
    protected val baseDomain: String,        // contoh: "id.mgkomik.cc"
    override val lang: String,
    protected open val datePattern: String = "MMMM d, yyyy", // boleh di-override oleh site
) : HttpSource() {

    // ---- Konfigurasi umum (boleh di-override di subclass) ----
    override val baseUrl: String = "https://$baseDomain"
    override val supportsLatest: Boolean = true

    // Mirror dari Kotatsu
    protected open val tagPrefix: String = "manga-genre/"
    protected open val listUrl: String = "manga/"
    protected open val stylePage: String = "" // mis. "?style=list" jika perlu
    protected open val sourceLocale: Locale = Locale.ENGLISH
    protected open val withoutAjax: Boolean = false          // jika true, skip AJAX chapter fetch
    protected open val postReq: Boolean = false              // jika true pakai admin-ajax POST
    protected open val postDataReqKey: String = "manga"      // kunci POST data id (default "manga")
    protected open val authorSearchSupported: Boolean = false

    // Selectors default (override jika perlu)
    protected open val selMangaTile: String = "div.c-tabs-item__content, div.page-item-detail, .page-item-detail, .bs, .item"
    protected open val selMangaTileTitle: String = ".post-title, h3, h4, .manga-name"
    protected open val selMangaTileThumb: String = "img"
    protected open val selNextPage: String = "a.next, .pagination .next"

    protected open val selSummaryTitle: String = "h1, .post-title, .entry-title"
    protected open val selSummaryDesc: String = "div.description-summary .summary__content, .summary_content .post-content_item > h5 + div, .post-content .manga-summary, .post-content .desc, .c-page__content .summary__content"
    protected open val selSummaryGenre: String = ".genres-content a"
    protected open val selSummaryAuthor: String = ".author-content a, .post-content_item:contains(Author) a, .post-content_item:contains(Penulis) a"
    protected open val selSummaryStatus: String = ".post-content_item:contains(Status), .post-content_item:contains(Statut), .post-content_item:contains(Estado), .post-content_item:contains(حالة)"
    protected open val selSummaryThumb: String = ".summary_image img, .post-thumb img, .attachment-post-thumbnail"

    protected open val selChapterListWrap: String = "div.listing-chapters_wrap, #chapterlist, .chapters"
    protected open val selChapterItem: String = "li.wp-manga-chapter, .chapter, .wp-manga-chapter"
    protected open val selChapterAnchor: String = "a"
    protected open val selChapterDate: String = "span.chapter-release-date i, span.chapter-release-date, .chapter-release-date"

    protected open val selReaderContainer: String = "div.reading-content, div.main-col-inner"
    protected open val selReaderPage: String = "img, img.wp-manga-chapter-img"

    // Status keyword
    private val statusOngoing = setOf("ongoing", "publishing", "berjalan", "en cours", "en curso", "连载中")
    private val statusCompleted = setOf("completed", "finished", "tamat", "terminé", "completo", "已完结")

    // Random X-Requested-With (sekali per instance)
    private val randomXRequestedWith: String by lazy {
        generateRandomString(Random.Default.nextInt(13, 21))
    }

    // ---- Header ----
    protected open fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-User", "?1")
            .add("Upgrade-Insecure-Requests", "1")
            .add("X-Requested-With", randomXRequestedWith)
    }

    // ---- Request builders ----
    override fun popularMangaRequest(page: Int): Request {
        return GET(buildListUrl(page, latest = false), headersBuilder().build())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(buildListUrl(page, latest = true), headersBuilder().build())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: List<Filter>): Request {
        return GET(buildSearchUrl(page, query, filters), headersBuilder().build())
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(manga.url.toAbsoluteUrl(baseDomain), headersBuilder().build())
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(manga.url.toAbsoluteUrl(baseDomain), headersBuilder().build())
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(chapter.url.toAbsoluteUrl(baseDomain), headersBuilder().build())
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headersBuilder().build())
    }

    // ---- URL builders ----
    protected open fun buildListUrl(page: Int, latest: Boolean): String {
        val p = if (page <= 1) 1 else page
        // Banyak tema Madara pakai pagination ?page=2 atau /page/2 — kita pakai query agar aman
        return if (p <= 1) {
            "$baseUrl/$listUrl"
        } else {
            "$baseUrl/$listUrl?page=$p"
        }
    }

    protected open fun buildSearchUrl(page: Int, query: String, @Suppress("UNUSED_PARAMETER") filters: List<Filter>): String {
        val p = max(1, page)
        val q = URLEncoder.encode(query, "UTF-8")
        return if (p <= 1) {
            "$baseUrl/$listUrl?s=$q&post_type=wp-manga"
        } else {
            "$baseUrl/$listUrl?s=$q&post_type=wp-manga&paged=$p"
        }
    }

    // ---- Parsers: Popular/Latest/Search ----
    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asDocument()
        val mangas = parseMangaTiles(doc)
        val hasNext = doc.select(selNextPage).isNotEmpty()
        return MangasPage(mangas, hasNext)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    protected open fun parseMangaTiles(doc: Document): List<SManga> {
        val elements: Elements = doc.select(selMangaTile)
        if (elements.isEmpty()) return emptyList()
        return elements.mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val title = el.selectFirst(selMangaTileTitle)?.text()
                ?: a.attr("title").ifBlank { a.text() }
            val img = el.selectFirst(selMangaTileThumb)?.let { imgEl ->
                (imgEl.attr("data-src").ifBlank { imgEl.attr("src") })
            }
            SManga.create().apply {
                url = href
                this.title = title ?: href
                thumbnail_url = img
            }
        }
    }

    // ---- Manga details ----
    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asDocument()
        val manga = SManga.create()
        manga.url = response.request.url.toString()
        manga.title = doc.selectFirst(selSummaryTitle)?.text() ?: manga.title
        manga.description = doc.selectFirst(selSummaryDesc)?.text()?.trim()
        manga.genre = doc.select(selSummaryGenre).joinToString(", ") { it.text() }.ifBlank { null }
        manga.author = doc.select(selSummaryAuthor).joinToString(", ") { it.text() }.ifBlank { null }
        manga.thumbnail_url = doc.selectFirst(selSummaryThumb)?.attr("src")
            ?: doc.selectFirst("img")?.attr("src")

        val statusText = doc.selectFirst(selSummaryStatus)?.text()?.lowercase(Locale.ROOT) ?: ""
        manga.status = when {
            statusOngoing.any { statusText.contains(it) } -> SManga.ONGOING
            statusCompleted.any { statusText.contains(it) } -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        return manga
    }

    // ---- Chapter list ----
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asDocument()

        // 1) Jika container chapter sudah dirender, parse langsung
        doc.selectFirst(selChapterListWrap)?.let { wrap ->
            val parsed = parseChaptersFromContainer(wrap)
            if (parsed.isNotEmpty()) return parsed
        }

        // 2) Kalau tidak (atau kosong), coba AJAX (kecuali tanpaAjax=true)
        if (!withoutAjax) {
            try {
                val ajaxChaps = fetchChaptersViaAjax(doc)
                if (ajaxChaps.isNotEmpty()) return ajaxChaps
            } catch (_: Exception) {
                // fallback ke HTML
            }
        }

        // 3) Fallback: pakai HTML penuh dokumen
        return parseChaptersFromContainer(doc)
    }

    protected open fun parseChaptersFromContainer(container: Element): List<SChapter> {
        val items = container.select(selChapterItem).ifEmpty { container.select("$selChapterItem $selChapterAnchor") }
        if (items.isEmpty()) return emptyList()

        val df: DateFormat = SimpleDateFormat(datePattern, sourceLocale).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val chapters = items.mapNotNull { el ->
            val a = (if (el.tagName().equals("a", true)) el else el.selectFirst(selChapterAnchor))
                ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val name = a.text().ifBlank { a.attr("title") }.ifBlank { "Chapter" }
            val dateText = el.selectFirst(selChapterDate)?.text()

            SChapter.create().apply {
                url = href + stylePage
                this.name = name
                date_upload = parseChapterDate(df, dateText)
                chapter_number = parseChapterNumber(name)
            }
        }

        // Urutkan dari terlama ke terbaru (preferensi Tachiyomi)
        return chapters.sortedBy { it.date_upload }
    }

    protected open fun fetchChaptersViaAjax(doc: Document): List<SChapter> {
        val mangaId = doc.selectFirst("#manga-chapters-holder")?.attr("data-id")
            ?: doc.selectFirst("[data-id]")?.attr("data-id")
            ?: ""

        // Dua pola umum:
        // - POST: /wp-admin/admin-ajax.php { action=manga_get_chapters, manga=<id> }
        // - GET : <chapter-url>/ajax/chapters/
        val tryPost = postReq && mangaId.isNotEmpty()
        val req: Request = if (tryPost) {
            val body: RequestBody = FormBody.Builder()
                .add("action", "manga_get_chapters")
                .add(postDataReqKey, mangaId)
                .build()
            Request.Builder()
                .url("$baseUrl/wp-admin/admin-ajax.php")
                .headers(headersBuilder().build())
                .post(body)
                .build()
        } else {
            val ajaxUrl = doc.location().removeSuffix("/") + "/ajax/chapters/"
            GET(ajaxUrl, headersBuilder().build())
        }

        client.newCall(req).execute().use { resp ->
            val ajaxDoc = resp.asDocument()
            val wrap = ajaxDoc.selectFirst(selChapterListWrap) ?: ajaxDoc
            return parseChaptersFromContainer(wrap)
        }
    }

    // ---- Page list (reader) ----
    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asDocument()

        // 1) Coba ekstrak dari skrip inline (array, atob, chapter_data)
        val fromScript = extractImagesFromScripts(doc)
        if (fromScript.isNotEmpty()) return fromScript.mapIndexed { i, url -> Page(i, url) }

        // 2) Coba dari container reader (img)
        val container = doc.selectFirst(selReaderContainer) ?: doc
        val imgs = container.select(selReaderPage)
            .mapNotNull { img ->
                val raw = img.attr("data-src").ifBlank { img.attr("src") }
                raw.takeIf { it.isNotBlank() }?.toAbsoluteUrl(baseDomain)
            }
        if (imgs.isNotEmpty()) return imgs.mapIndexed { i, url -> Page(i, url) }

        // 3) Coba proteksi base64 inline <script src="data:text/javascript;base64,...">
        val fromProtector = tryDecryptProtector(doc)
        if (fromProtector.isNotEmpty()) return fromProtector.mapIndexed { i, url -> Page(i, url) }

        throw Exception("No pages found")
    }

    // ---- Helpers umum ----
    protected fun Response.asDocument(): Document {
        val bodyStr = this.body?.string() ?: ""
        return Jsoup.parse(bodyStr, this.request.url.toString())
    }

    protected fun parseChapterNumber(name: String?): Float {
        if (name.isNullOrBlank()) return 0f
        // tangkap angka float (12, 12.5, 12,5)
        val rx = Regex("""([0-9]+(?:[.,][0-9]+)?)""")
        val m = rx.find(name) ?: return 0f
        return m.groupValues[1].replace(",", ".").toFloatOrNull() ?: 0f
    }

    protected fun parseChapterDate(df: DateFormat, text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val raw = text.trim().lowercase(Locale.ROOT)

        val cal = Calendar.getInstance()
        fun midnight(c: Calendar): Long {
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        // Lokal umum
        when {
            raw.startsWith("today") || raw.startsWith("hari ini") -> {
                return midnight(cal)
            }
            raw.startsWith("yesterday") || raw.startsWith("kemarin") -> {
                cal.add(Calendar.DAY_OF_MONTH, -1)
                return midnight(cal)
            }
            raw.contains("ago") || raw.contains("yang lalu") -> {
                // "2 days ago", "3 hours ago", "2 hari yang lalu", "5 jam yang lalu"
                val num = Regex("""(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return 0L
                return when {
                    raw.contains("day") || raw.contains("hari") -> {
                        cal.add(Calendar.DAY_OF_MONTH, -num); cal.timeInMillis
                    }
                    raw.contains("hour") || raw.contains("jam") -> {
                        cal.add(Calendar.HOUR_OF_DAY, -num); cal.timeInMillis
                    }
                    raw.contains("minute") || raw.contains("menit") -> {
                        cal.add(Calendar.MINUTE, -num); cal.timeInMillis
                    }
                    else -> 0L
                }
            }
            else -> {
                // Coba parse berdasarkan pola site (datePattern), fallback dd MMM yy
                try {
                    return df.parse(text)?.time ?: 0L
                } catch (_: Exception) {
                    return try {
                        SimpleDateFormat("dd MMM yy", sourceLocale).parse(text)?.time ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                }
            }
        }
    }

    protected fun String.toAbsoluteUrl(domain: String): String {
        return when {
            startsWith("http://") || startsWith("https://") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "https://$domain$this"
            else -> "https://$domain/$this"
        }
    }

    // ---- Script extractors & protector ----
    protected fun extractImagesFromScripts(doc: Document): List<String> {
        val scripts = doc.select("script").mapNotNull { it.data() }
        if (scripts.isEmpty()) return emptyList()

        // 1) JS array: var images = ["url1","url2"]
        val arrRegex = Regex("""var\s+images\s*=\s*(\[(?:.|\n|\r)*?])""", RegexOption.IGNORE_CASE)
        scripts.forEach { s ->
            val m = arrRegex.find(s)
            if (m != null) {
                val arr = m.groupValues[1]
                runCatching {
                    val json = JSONArray(arr)
                    val urls = mutableListOf<String>()
                    for (i in 0 until json.length()) {
                        val u = json.optString(i)
                        if (u.isNotBlank()) urls.add(u.toAbsoluteUrl(baseDomain))
                    }
                    if (urls.isNotEmpty()) return urls
                }
            }
        }

        // 2) chapter_data = '["...","..."]'
        val chapDataRegex = Regex("""chapter_data\s*=\s*['"](.+?)['"]""", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
        scripts.forEach { s ->
            val m = chapDataRegex.find(s)
            if (m != null) {
                val raw = m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"").replace("\\'", "'")
                runCatching {
                    val json = JSONArray(raw)
                    val urls = mutableListOf<String>()
                    for (i in 0 until json.length()) {
                        val u = json.optString(i)
                        if (u.isNotBlank()) urls.add(u.toAbsoluteUrl(baseDomain))
                    }
                    if (urls.isNotEmpty()) return urls
                }
            }
        }

        // 3) atob("base64-json-array")
        val atobRegex = Regex("""atob\(['"]([A-Za-z0-9+/=]+)['"]\)""")
        scripts.forEach { s ->
            val m = atobRegex.find(s)
            if (m != null) {
                val b64 = m.groupValues[1]
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(b64))
                    val json = JSONArray(decoded)
                    val urls = mutableListOf<String>()
                    for (i in 0 until json.length()) {
                        val u = json.optString(i)
                        if (u.isNotBlank()) urls.add(u.toAbsoluteUrl(baseDomain))
                    }
                    if (urls.isNotEmpty()) return urls
                }
            }
        }

        // 4) Encrypted: var enc="..."; var key="..."; var iv="..."
        val encRegex = Regex("""var\s+enc\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""")
        val keyRegex = Regex("""var\s+key\s*=\s*['"](.+?)['"]""")
        val ivRegex = Regex("""var\s+iv\s*=\s*['"](.+?)['"]""")
        val merged = scripts.joinToString("\n")
        val enc = encRegex.find(merged)?.groupValues?.getOrNull(1)
        val key = keyRegex.find(merged)?.groupValues?.getOrNull(1)
        val iv = ivRegex.find(merged)?.groupValues?.getOrNull(1)
        if (enc != null && key != null && iv != null) {
            runCatching {
                val decrypted = CryptoAES.decryptBase64(enc, key, iv)
                val json = JSONArray(decrypted)
                val urls = mutableListOf<String>()
                for (i in 0 until json.length()) {
                    val u = json.optString(i)
                    if (u.isNotBlank()) urls.add(u.toAbsoluteUrl(baseDomain))
                }
                if (urls.isNotEmpty()) return urls
            }
        }

        return emptyList()
    }

    protected fun tryDecryptProtector(doc: Document): List<String> {
        val out = mutableListOf<String>()

        // <script src="data:text/javascript;base64,....">
        doc.select("script[src^=\"data:text/javascript;base64,\"]").forEach { s ->
            val src = s.attr("src")
            val b64 = src.substringAfter("data:text/javascript;base64,", "")
            if (b64.isNotBlank()) {
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(b64))
                    // cari array JSON di dalam skrip
                    val arrayRx = Regex("""\[(?:\s*"(?:\\.|[^"])*"\s*(?:,\s*)?)+]""", RegexOption.DOT_MATCHES_ALL)
                    val arr = arrayRx.find(decoded)?.value
                    if (!arr.isNullOrBlank()) {
                        val json = JSONArray(arr)
                        for (i in 0 until json.length()) {
                            val u = json.optString(i)
                            if (u.isNotBlank()) out.add(u.toAbsoluteUrl(baseDomain))
                        }
                    }
                }
            }
        }

        // pattern: chapter_data = "base64..."
        doc.select("script").mapNotNull { it.data() }.forEach { s ->
            val m = Regex("""chapter_data\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""").find(s)
            val enc = m?.groupValues?.getOrNull(1)
            if (!enc.isNullOrBlank()) {
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(enc))
                    val json = JSONArray(decoded)
                    for (i in 0 until json.length()) {
                        val u = json.optString(i)
                        if (u.isNotBlank()) out.add(u.toAbsoluteUrl(baseDomain))
                    }
                }
            }
        }

        return out
    }

    // ---- Crypto AES helper (CBC/PKCS5) ----
    object CryptoAES {
        /**
         * Decrypt AES-CBC/PKCS5Padding dari ciphertext base64.
         * key & iv berupa string; akan dinormalisasi ke 16 byte (AES-128).
         */
        fun decryptBase64(cipherBase64: String, key: String, iv: String): String {
            val cipherBytes = Base64.getDecoder().decode(cipherBase64)
            val keyBytes = normalizeKey(key)
            val ivBytes = normalizeKey(iv)

            val skey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, skey, ivSpec)
            val plain = cipher.doFinal(cipherBytes)
            return String(plain, Charsets.UTF_8)
        }

        private fun normalizeKey(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            return when {
                b.size == 16 -> b
                b.size > 16 -> b.copyOf(16)
                else -> ByteArray(16).also { System.arraycopy(b, 0, it, 0, b.size) }
            }
        }
    }

    // ---- Util kecil ----
    private fun Response.asDocument(): Document {
        val body = this.body?.string().orEmpty()
        return Jsoup.parse(body, this.request.url.toString())
    }

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = Random.Default
        val sb = StringBuilder(length)
        repeat(length) { sb.append(charset[rnd.nextInt(charset.length)]) }
        return sb.toString()
    }
}
