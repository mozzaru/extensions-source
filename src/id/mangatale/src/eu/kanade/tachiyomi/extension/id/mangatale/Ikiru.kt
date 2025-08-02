package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class Ikiru : HttpSource() {
    override val name = "Ikiru"
    override val baseUrl = "https://01.ikiru.wtf"
    override val lang = "id"
    override val supportsLatest = true
    override val id = 1532456597012176985

    override val client = network.cloudflareClient

    private val ajaxHandler by lazy { IkiruAjax(client, baseUrl, headers) }
    private val mangaParser by lazy { IkiruMangaParser() }

    override fun headersBuilder() =
        super.headersBuilder()
            .add("Referer", baseUrl)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        val body = FormBody.Builder()
            .add("orderby", "popular")
            .add("page", page.toString())
            .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaResponse(response)

    // Latest Updates - Updated to use new URL structure
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest-update/?the_page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaResponse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBody = FormBody.Builder()
            .add("page", page.toString())
            .add("query", query)
            .add("orderby", "popular")
            .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, formBody)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaResponse(response)

    // Common manga parsing with improved selectors
    private fun parseMangaResponse(response: Response): MangasPage {
        val raw = response.body!!.string()

        if (IkiruUtils.checkCloudflareBlock(raw)) {
            throw Exception("Diblokir Cloudflare - Silakan coba lagi nanti")
        }

        val document = Jsoup.parse(raw)
        val mangas = mutableListOf<SManga>()

        // Populer & Terbaru
        document.select("div.swiper-slide a[href^='/manga/']").forEach { link ->
            val href = link.attr("href")
            if (!IkiruUtils.isValidMangaUrl(href)) return@forEach
    
            val title = link.selectFirst("h2")?.text()
                ?: link.selectFirst("img")?.attr("alt")
                ?: "Tanpa Judul"
    
            val thumbnail = link.selectFirst("img")?.absUrl("src").orEmpty()
    
            mangas.add(SManga.create().apply {
                url = href.removePrefix(baseUrl)
                this.title = IkiruUtils.sanitizeTitle(title)
                thumbnail_url = thumbnail
            })
        }

        val hasNextPage = document.select("a:contains(Next), .pagination-next, [rel=next]").isNotEmpty()
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body!!.string())
        return mangaParser.parseMangaDetails(document)
    }

    // Chapter List
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body!!.string())

        val mangaId = IkiruUtils.findMangaId(document) 
            ?: throw Exception("Manga ID tidak ditemukan")
        val chapterId = IkiruUtils.findChapterId(document) 
            ?: mangaId // Fallback to mangaId if chapterId not found

        return ajaxHandler.getChapterList(mangaId, chapterId)
    }

    // Page List
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("""
            div.reading-content img,
            section.reading-content img,
            img[src*='/wp-content/uploads/']
        """.trimIndent()).mapIndexed { index, img ->
            val imageUrl = img.absUrl("src").ifBlank { 
                img.absUrl("data-src").ifBlank { 
                    img.absUrl("data-original") 
                } 
            }
            Page(index, "", imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")
    override fun getFilterList(): FilterList = FilterList()
}
