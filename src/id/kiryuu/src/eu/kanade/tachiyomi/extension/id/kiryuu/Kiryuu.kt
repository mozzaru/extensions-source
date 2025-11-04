package eu.kanade.tachiyomi.extension.id.kiryuu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

class Kiryuu : ParsedHttpSource() {

    override val id = 3639673976007021338
    override val name = "Kiryuu"
    override val baseUrl = "https://kiryuu03.com"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(12, 3)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request =
        searchMangaRequest(page, "", FilterList())

    override fun popularMangaSelector(): String = searchMangaSelector()
    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun popularMangaNextPageSelector(): String = searchMangaNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest/?the_page=$page", headers)
    }

    override fun latestUpdatesSelector(): String = "#search-results > div:not(.col-span-full)"
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String =
        "#search-results ~ div.col-span-full a:has(svg):last-of-type"

    // Search (nonce-based POST to advanced_search)
    private var searchNonce: String? = null

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (searchNonce.isNullOrEmpty()) {
            val nonceDoc = client.newCall(
                GET("$baseUrl/wp-admin/admin-ajax.php?type=search_form&action=get_nonce", headers),
            ).execute().asJsoup()

            searchNonce = nonceDoc.selectFirst("input[name=search_nonce]")?.attr("value")
                ?: error("Search nonce not found")
        }

        val body: RequestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("query", query)
            .addFormDataPart("page", "$page")
            .addFormDataPart("nonce", searchNonce!!)
            .build()

        return POST("$baseUrl/wp-admin/admin-ajax.php?action=advanced_search", body = body, headers = headers)
    }

    override fun searchMangaSelector(): String = "div.overflow-hidden:has(a.font-medium)"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")?.absUrl("href") ?: "")
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.absUrl("src")
                .ifEmpty { img.absUrl("data-src") }
                .ifEmpty { img.absUrl("srcset").substringBefore(" ") }
        }.orEmpty()
        title = element.selectFirst("a.font-medium")?.text() ?: ""
        status = parseStatus(element.selectFirst("div span ~ p")?.text() ?: "")
    }

    override fun searchMangaNextPageSelector(): String = "div button:has(svg)"

    // Manga details
    private fun Element.extractMangaId(): String? {
        return selectFirst("#chapter-list, #gallery-list")?.attr("hx-get")
            ?.substringAfter("manga_id=")?.substringBefore("&")
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val section = document.selectFirst("article > section")
        return SManga.create().apply {
            thumbnail_url = section?.selectFirst(".contents img, img.w-full")?.let { img ->
                img.absUrl("src")
                    .ifEmpty { img.absUrl("data-src") }
                    .ifEmpty { img.absUrl("srcset").substringBefore(" ") }
            }.takeIf { it?.isNotBlank() == true }
                ?: document.selectFirst("meta[property=og:image]")?.absUrl("content").orEmpty()

            title = section?.selectFirst("h1.font-bold, h1.text-2xl")?.text() ?: ""

            val altNames = section?.selectFirst("h1 ~ .line-clamp-1")?.text().orEmpty()
            val synopsis = section?.selectFirst("#tabpanel-description div[data-show='false']")?.text().orEmpty()

            description = buildString {
                append(synopsis)
                if (altNames.isNotEmpty()) {
                    append("\n\nAlternative Title: ", altNames)
                }
                document.body()?.extractMangaId()?.also {
                    append("\n\nID: ", it)
                }
            }

            genre = section?.select(".space-y-2 div:has(img) p, #tabpanel-description .flex-wrap span")
                ?.joinToString { it.text() }.orEmpty()
        }
    }

    // Chapters
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val storedId = manga.description
            ?.substringAfterLast("ID: ", "")
            ?.takeIf { it.toIntOrNull() != null }

        val mangaId = storedId ?: run {
            val doc = client.newCall(mangaDetailsRequest(manga)).execute().asJsoup()
            doc.body().extractMangaId() ?: throw Exception("Could not find manga ID")
        }

        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php".toHttpUrl().newBuilder()
            .addQueryParameter("manga_id", mangaId)
            .addQueryParameter("page", "${Random.nextInt(99, 99999999)}")
            .addQueryParameter("action", "chapter_list")
            .build()

        val response = client.newCall(GET(ajaxUrl, headers)).execute()
        val doc = response.asJsoup()

        doc.select("div a").mapNotNull { el ->
            val name = el.select("span").text().trim()
            if (name.isEmpty()) return@mapNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(el.attr("href"))
                this.name = name
                date_upload = dateFormat.tryParse(el.select("time").attr("datetime"))
            }
        }
    }

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException()
    override fun chapterListSelector(): String = throw UnsupportedOperationException()

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        val imgs = document.select("main img, section img")
            .mapNotNull { el -> el.absUrl("src").takeIf { it.isNotBlank() } }

        return imgs.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // Status parser
    private fun parseStatus(text: String?): Int {
        val s = text.orEmpty().lowercase()
        return when {
            s.contains("ongoing") -> SManga.ONGOING
            s.contains("completed") -> SManga.COMPLETED
            s.contains("on hiatus") -> SManga.ON_HIATUS
            s.contains("canceled") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
    }
}
