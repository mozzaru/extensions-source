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
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class Ikiru : HttpSource() {
    override val name = "Ikiru"
    override val baseUrl = "https://01.ikiru.wtf"
    override val lang = "id"
    override val supportsLatest = true
    override val id = 1532456597012176985

    private val rateLimitInterceptor = Interceptor { chain ->
        // 2 requests per second
        Thread.sleep(500)
        chain.proceed(chain.request())
    }

    override val client = network.cloudflareClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(rateLimitInterceptor)
        .build()

    private val ajaxHandler by lazy { IkiruAjax(client, baseUrl, headers) }
    private val mangaParser by lazy { IkiruMangaParser() }

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("DNT", "1")
        .add("Sec-GPC", "1")
        .add("Upgrade-Insecure-Requests", "1")

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        val body = FormBody.Builder()
            .add("orderby", "popular")
            .add("page", page.toString())
            .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaResponse(response)

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request {
        val body = FormBody.Builder()
            .add("orderby", "updated")
            .add("page", page.toString())
            .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaResponse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBody = FormBody.Builder()
            .add("page", page.toString())
            .add("query", query)
            .add("orderby", "popular")

        // Process filters
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    val type = filter.toUriPart()
                    if (type.isNotEmpty()) formBody.add("type", type)
                }
                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotEmpty()) formBody.add("status", status)
                }
                is GenreFilterList -> {
                    // Fixed genre processing
                    val selectedGenres = filter.state
                        .filter { it.state }
                        .mapNotNull { (it as? Genre)?.id }
                    
                    selectedGenres.forEach { genreId ->
                        formBody.add("genre[]", genreId)
                    }
                }
                else -> {
                    // Handle other filter types if needed
                }
            }
        }

        return POST("$baseUrl/ajax-call?action=advanced_search", headers, formBody.build())
    }

    override fun getFilterList() = getFilterListInternal()

    override fun searchMangaParse(response: Response): MangasPage = parseMangaResponse(response)

    // Common manga parsing - FIXED
    private fun parseMangaResponse(response: Response): MangasPage {
        val raw = response.body!!.string()
    
        if (IkiruUtils.checkCloudflareBlock(raw)) {
            throw Exception("Diblokir Cloudflare - Silakan coba lagi nanti")
        }
    
        val document = Jsoup.parse(raw)
        val mangas = mutableListOf<SManga>()
    
        // Universal manga detection - handles multiple layout types
        val mangaContainers = document.select("""
            [class*='item'], 
            [class*='card'], 
            [class*='box'], 
            [class*='manga'], 
            [class*='manqa'], 
            [class*='comic'],
            article,
            section,
            div.grid > div,
            div.flex > div
        """.trimIndent())
    
        mangaContainers.forEach { container ->
            container.selectFirst("a[href*='/manga/'], a[href*='/manqa/'], a[href*='/komik/']")?.let { link ->
                val href = link.attr("href")
                if (!IkiruUtils.isValidMangaUrl(href)) return@forEach
    
                val title = extractTitleFromItem(container, link)
                val thumbnailUrl = IkiruUtils.extractThumbnailUrl(container)
    
                if (title.isNotBlank()) {
                    mangas.add(SManga.create().apply {
                        url = href.removePrefix(baseUrl)
                        this.title = IkiruUtils.sanitizeTitle(title)
                        thumbnail_url = thumbnailUrl
                    })
                }
            }
        }
    
        // Direct link fallback
        if (mangas.isEmpty()) {
            document.select("a[href*='/manga/'], a[href*='/manqa/'], a[href*='/komik/']").forEach { link ->
                val href = link.attr("href")
                if (!IkiruUtils.isValidMangaUrl(href)) return@forEach
    
                val title = extractTitleFromItem(link, link)
                val thumbnailUrl = IkiruUtils.extractThumbnailUrl(link)
    
                if (title.isNotBlank() && mangas.none { it.url == href.removePrefix(baseUrl) }) {
                    mangas.add(SManga.create().apply {
                        url = href.removePrefix(baseUrl)
                        this.title = IkiruUtils.sanitizeTitle(title)
                        thumbnail_url = thumbnailUrl
                    })
                }
            }
        }
    
        // Image-based fallback
        if (mangas.isEmpty()) {
            document.select("img[src*='/covers/'], img[src*='/thumb/'], img[src*='/uploads/']").forEach { img ->
                img.parents().firstOrNull { it.tagName() == "a" }?.let { link ->
                    val href = link.attr("href")
                    if (!IkiruUtils.isValidMangaUrl(href)) return@forEach
    
                    val title = extractTitleFromItem(link, link)
                    val thumbnailUrl = img.absUrl("src") ?: img.absUrl("data-src")
    
                    if (title.isNotBlank() && mangas.none { it.url == href.removePrefix(baseUrl) }) {
                        mangas.add(SManga.create().apply {
                            url = href.removePrefix(baseUrl)
                            this.title = IkiruUtils.sanitizeTitle(title)
                            thumbnail_url = thumbnailUrl
                        })
                    }
                }
            }
        }
    
        val hasNextPage = determineHasNextPage(document, mangas.size)
    
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    private fun extractTitleFromItem(item: Element, link: Element): String {
        // Try various title locations
        return listOf(
            item.selectFirst("h1, h2, h3, h4, h5, h6"),
            item.selectFirst("[class*='title']"),
            item.selectFirst("[class*='name']"),
            item.selectFirst(".text-lg, .text-xl, .text-2xl"),
            link.attr("title"),
            link.selectFirst("img")?.attr("alt"),
            link.text().takeIf { it.isNotBlank() }
        ).firstNotNullOfOrNull { 
            it?.text()?.trim() ?: (it as? Attribute)?.value?.trim()
        } ?: ""
    }

    private fun determineHasNextPage(document: org.jsoup.nodes.Document, mangaCount: Int): Boolean {
        return when {
            mangaCount >= 20 -> true
            document.select("a.next, a:contains(Next), button:contains(Lanjut), button:contains(Next)").isNotEmpty() -> true
            document.select("div.pagination, .pagination").isNotEmpty() -> true
            document.select("button:contains(Load More), button[class*='load']").isNotEmpty() -> true
            document.select("[data-page], [data-next-page]").isNotEmpty() -> true
            else -> false
        }
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
            ?: throw Exception("Chapter ID tidak ditemukan")

        return ajaxHandler.getChapterList(mangaId, chapterId)
    }

    // Page List
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body!!.string())
        val cloudflareToken = IkiruUtils.extractCloudflareToken(document)

        return document.select("""
            section.mx-auto img, 
            section.w-full img, 
            div.reading-content img,
            div[class*="reader"] img,
            div[class*="chapter"] img,
            img[src*='/wp-content/uploads/'],
            img[src*='/uploads/'],
            img[class*="page"],
            img[id*="image"]
        """.trimIndent()).mapIndexed { index, img ->
            val imageUrl = if (cloudflareToken != null) {
                var src = img.absUrl("src").ifBlank { 
                    img.absUrl("data-src").ifBlank { 
                        img.absUrl("data-original") 
                    } 
                }
                if (src.contains("/uploads/")) {
                    src = src.replace("/uploads/", "/cdn-cgi/imagedelivery/$cloudflareToken/")
                }
                src
            } else {
                IkiruUtils.extractImageUrl(img)
            }
            Page(index, "", imageUrl)
        }.filter { it.imageUrl!!.isNotBlank() }
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headers.newBuilder()
            .removeAll("Accept")
            .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .removeAll("Referer")
            .add("Referer", "$baseUrl/")
            .build()
        )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")
}
