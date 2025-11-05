package eu.kanade.tachiyomi.extension.id.mangkomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SirenKomik : HttpSource() {

    override val name = "SirenKomik"
    override val baseUrl = "https://sirenkomik.xyz"
    private val apiUrl = "$baseUrl/api"
    override val lang = "id"
    override val supportsLatest = true
    override val id = 8457447675410081142

    override val client: OkHttpClient = super.client

    // Signature state
    @Volatile private var serverEpoch: Long? = null
    @Volatile private var clientEpochBaseline: Long? = null
    @Volatile private var globalToken: String? = null

    // Secret from site JS
    private val jsSecret: String = "Feat_19_JUTA_LAPANGAN_PEKERJAAN"
    private val domainPublicSuffix: String = baseUrl.substringAfter("://").substringBefore("/")

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Referer", "$baseUrl/")
        .add("Cache-Control", "no-cache, no-store, must-revalidate")
        .add("Pragma", "no-cache")
        .add("Expires", "0")

    // Auth headers required by API
    private fun authHeaders(path: String): Headers {
        ensureBootstrap()

        val ts = currentServerSyncedEpoch()
        val hashText = generateBase64Url(32)
        val key = "$domainPublicSuffix|$ts|$jsSecret"
        val message = "$ts.$hashText"
        val signature = hmacSha256(key, message)

        return headersBuilder()
            .add("GlobalAuthorization", "Bearer ${globalToken!!}")
            .add("X-REQUEST-TIMESTAMP", ts.toString())
            .add("X-REQUEST-HASH", hashText)
            .add("X-REQUEST-SIGNATURE", signature)
            .build()
    }

    // Bootstrap time + token
    private fun ensureBootstrap() {
        if (globalToken == null) globalToken = generateBase64Url(32)
        if (serverEpoch == null || clientEpochBaseline == null) {
            val req = GET("$apiUrl/server-time", headersBuilder().build())
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body.string()
                    val json = JSONObject(body)
                    val server = json.optJSONObject("data")?.optLong("time")
                    if (server != null && server > 0) {
                        serverEpoch = server
                        clientEpochBaseline = System.currentTimeMillis() / 1000
                        return
                    }
                }
            }
            // fallback to local epoch if server-time fails
            serverEpoch = System.currentTimeMillis() / 1000
            clientEpochBaseline = serverEpoch
        }
    }

    private fun currentServerSyncedEpoch(): Long {
        val now = System.currentTimeMillis() / 1000
        return (serverEpoch ?: now) + (now - (clientEpochBaseline ?: now))
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(raw)
    }

    private fun generateBase64Url(length: Int): String {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "20")
            .addQueryParameter("sort", "popular")
            .build()
        return GET(url, authHeaders("/api/manga/list"))
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val arr = JSONObject(response.body.string()).getJSONObject("data").getJSONArray("manga")
        val mangas = arr.toSMangaList()
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "20")
            .addQueryParameter("sort", "latest")
            .build()
        return GET(url, authHeaders("/api/manga/list"))
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search + filters
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "20")
            .apply {
                if (query.isNotBlank()) addQueryParameter("search", query)
                filters.filterIsInstance<SirenKomikFilters.UrlFilter>().forEach { it.addToUrl(this) }
            }
            .build()
        return GET(url, authHeaders("/api/manga/list"))
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/manga/")
        return GET("$apiUrl/manga/$slug", authHeaders("/api/manga/$slug"))
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val d = JSONObject(response.body.string()).getJSONObject("data")
        return SManga.create().apply {
            title = d.optString("title")
            thumbnail_url = d.optString("coverImage", d.optString("cover"))
            author = d.optString("author")
            artist = d.optString("artist")
            status = parseStatus(d.optString("status"))
            description = d.optString("description")
            genre = d.optJSONArray("genres")?.toGenreString()
            url = "/manga/${d.optString("slug")}"
        }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val arr = JSONObject(response.body.string()).getJSONObject("data").getJSONArray("chapters")
        return (0 until arr.length()).map { i ->
            val ch = arr.getJSONObject(i)
            SChapter.create().apply {
                name = ch.optString("title").ifBlank { "Chapter ${ch.optString("number")}" }
                url = "/chapter/${ch.optString("slug")}"
                date_upload = ch.optLong("date", 0L) * 1000
                chapter_number = ch.optString("number").toFloatOrNull() ?: -1f
            }
        }.sortedByDescending { it.chapter_number }
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        val slug = chapter.url.substringAfter("/chapter/")
        return GET("$apiUrl/chapter/$slug", authHeaders("/api/chapter/$slug"))
    }

    override fun pageListParse(response: Response): List<Page> {
        val arr = JSONObject(response.body.string())
            .getJSONObject("data").getJSONObject("currentChapterData")
            .getJSONArray("images").getJSONArray(0)
        return (0 until arr.length()).map { i -> Page(i, imageUrl = arr.getString(i)) }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = SirenKomikFilters.getFilterList()

    // Helpers
    private fun JSONArray.toSMangaList(): List<SManga> {
        return (0 until length()).map { i ->
            val obj = getJSONObject(i)
            SManga.create().apply {
                title = obj.optString("title")
                url = "/manga/${obj.optString("slug")}"
                thumbnail_url = obj.optString("cover")
                status = parseStatus(obj.optString("status"))
                genre = obj.optJSONArray("genres")?.toGenreString()
            }
        }
    }

    private fun JSONArray.toGenreString(): String {
        val list = mutableListOf<String>()
        for (i in 0 until length()) {
            val name = optJSONObject(i)?.optString("name") ?: optString(i, "")
            val cleaned = name.trim()
            if (cleaned.isNotEmpty()) list.add(cleaned)
        }
        return list.joinToString()
    }

    private fun parseStatus(s: String?): Int = when (s?.lowercase(Locale.ROOT)) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "on hiatus" -> SManga.ON_HIATUS
        "canceled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}
