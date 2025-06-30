package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Ikiru : ParsedHttpSource() {

    override val name = "Ikiru"
    override val baseUrl = "https://id.ikiru.wtf"
    override val lang = "id"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Tachiyomi Extension)")

    // === Fetch nonce ===
    private fun getNonce(): String {
        val response = client.newCall(GET("$baseUrl/ajax-call?type=search_form&action=get_nonce", headers)).execute()
        return response.body.string().trim('"')
    }

    // === POST builder for popular, latest, search ===
    private fun advancedSearchRequest(page: Int, order: String, query: String? = null): Request {
        val nonce = getNonce()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("undefined", "[\"$order\"]")
            .addFormDataPart("nonce", nonce)
            .addFormDataPart("page", page.toString())
            .apply {
                if (!query.isNullOrBlank()) {
                    addFormDataPart("keyword", query)
                }
            }
            .build()

        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    // === POPULAR ===
    override fun popularMangaRequest(page: Int): Request = advancedSearchRequest(page, "popular")

    override fun popularMangaSelector(): String = "div.relative.flex.flex-col"

    override fun popularMangaFromElement(element: Element): SManga = elementToManga(element)

    override fun popularMangaNextPageSelector(): String? = "button[aria-label=Next]"

    // === LATEST ===
    override fun latestUpdatesRequest(page: Int): Request = advancedSearchRequest(page, "recent")

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = elementToManga(element)

    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    // === SEARCH ===
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        advancedSearchRequest(page, "recent", query)

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = elementToManga(element)

    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    // === MANGA DETAILS ===
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("div.post-title h1")?.text().orEmpty()
        manga.description = document.selectFirst("div.summary__content")?.text()
        manga.thumbnail_url = document.selectFirst("div.summary_image img")?.absUrl("src")
        return manga
    }

    // === CHAPTER LIST ===
    override fun chapterListSelector(): String = "li.wp-manga-chapter a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("href"))
        chapter.name = element.text().trim()
        return chapter
    }

    // === PAGES ===
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.page-break img").mapIndexed { index, img ->
            val imageUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            Page(index, "", imageUrl)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // === Helper ===
    private fun elementToManga(element: Element): SManga {
        val manga = SManga.create()
        val a = element.selectFirst("a")
        if (a != null) {
            manga.setUrlWithoutDomain(a.attr("href"))
            manga.title = element.selectFirst("h3")?.text().orEmpty()
            manga.thumbnail_url = element.selectFirst("img")?.absUrl("data-src")
                ?: element.selectFirst("img")?.absUrl("src")
        }
        return manga
    }
}
