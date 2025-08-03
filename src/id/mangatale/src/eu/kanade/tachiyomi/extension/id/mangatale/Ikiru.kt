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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest-update/?the_page=$page", headers)
    }

    // Di file Ikiru.kt, dalam fungsi latestUpdatesParse
    override fun latestUpdatesParse(response: Response): MangasPage {
        val raw = response.body!!.string()
        
        if (IkiruUtils.checkCloudflareBlock(raw)) {
            throw Exception("Diblokir Cloudflare - Silakan coba lagi nanti")
        }
        
        val document = Jsoup.parse(raw)
        val mangas = mutableListOf<SManga>()
    
        // Selector yang benar berdasarkan HTML struktur
        document.select("#search-results > div").forEach { item ->
            try {
                // Cari link manga dalam struktur yang ada
                val mangaLink = item.selectFirst("a[href*='/manga/']")
                val mangaUrl = mangaLink?.attr("href")?.let { url ->
                    // Pastikan URL relatif
                    if (url.startsWith("http")) {
                        url.substringAfter(baseUrl)
                    } else {
                        url
                    }
                } ?: return@forEach
    
                // Ambil thumbnail dari img dengan prioritas
                val thumbnail = item.selectFirst("img.wp-post-image")?.let { img ->
                    img.attr("src").ifBlank { 
                        img.attr("data-src") 
                    }
                } ?: item.selectFirst("img")?.attr("src") ?: ""
    
                // Ambil title dengan prioritas dari berbagai elemen
                val title = item.selectFirst("h1")?.text()?.trim()
                    ?: item.selectFirst("a[href*='/manga/']")?.text()?.trim()
                    ?: item.selectFirst("img")?.attr("alt")?.trim()
                    ?: "Unknown Title"
    
                // Skip jika title kosong atau hanya whitespace
                if (title.isBlank() || title == "Unknown Title") return@forEach
    
                // Buat manga object
                val manga = SManga.create().apply {
                    url = mangaUrl
                    this.title = IkiruUtils.sanitizeTitle(title)
                    thumbnail_url = if (thumbnail.isNotBlank()) {
                        if (thumbnail.startsWith("http")) thumbnail else baseUrl + thumbnail
                    } else null
                    initialized = true
                }
    
                mangas.add(manga)
                
            } catch (e: Exception) {
                // Log error tapi lanjutkan parsing item lain
                e.printStackTrace()
            }
        }
    
        // Handle pagination - cek struktur pagination yang benar
        val currentPage = response.request.url.queryParameter("the_page")?.toIntOrNull() ?: 1
        
        // Cari tombol next dengan berbagai kemungkinan selector
        val hasNextPage = document.selectFirst(
            "a[href*='the_page=${currentPage + 1}'], " +
            "a:contains(Next), " +
            "a:contains(»), " +
            ".pagination a:last-child:not(.current)"
        ) != null
    
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

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

    // Common manga parsing
    private fun parseMangaResponse(response: Response): MangasPage {
        val raw = response.body!!.string()
    
        if (IkiruUtils.checkCloudflareBlock(raw)) {
            throw Exception("Diblokir Cloudflare - Silakan coba lagi nanti")
        }
    
        val document = Jsoup.parse(raw)
        val mangas = mutableListOf<SManga>()
    
        // Parse manga items
        document.select("div.flex.rounded-lg.overflow-hidden, div.group-data-\\[mode\\=horizontal\\]\\:hidden").forEach { item ->
            item.selectFirst("a[href^='/manga/']")?.let { link ->
                val href = link.attr("href")
                if (!IkiruUtils.isValidMangaUrl(href)) return@forEach
    
                val title = item.selectFirst("h1, h2, h3, div.text-base")?.text()
                    ?: item.selectFirst("img")?.attr("alt")
                    ?: ""
    
                val thumbnailUrl = IkiruUtils.extractThumbnailUrl(item)
                mangas.add(SManga.create().apply {
                    url = href.removePrefix(baseUrl)
                    this.title = IkiruUtils.sanitizeTitle(title)
                    thumbnail_url = thumbnailUrl
                }.apply {
                    thumbnail_url = if (thumbnail_url.isNullOrBlank()) null else thumbnail_url
                })
            }
        }
    
        // Fallback parsing if no results
        if (mangas.isEmpty()) {
            document.select("img.wp-post-image").forEach { img ->
                img.parents().firstOrNull { parent ->
                    val href = parent.attr("href")
                    href.contains("/manga/") && IkiruUtils.isValidMangaUrl(href)
                }?.let { link ->
                    mangas.add(SManga.create().apply {
                        url = link.attr("href").removePrefix(baseUrl)
                        title = IkiruUtils.sanitizeTitle(img.attr("alt"))
                        thumbnail_url = IkiruUtils.extractThumbnailUrl(img.parent()!!)
                    }.apply {
                        thumbnail_url = if (thumbnail_url.isNullOrBlank()) null else thumbnail_url
                    })
                }
            }
        }
    
        val hasNextPage = mangas.size >= 18
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body!!.string())
        return mangaParser.parseMangaDetails(document)
    }

    // Chapter List
    override fun chapterListRequest(manga: SManga): Request {
        // Langsung ambil halaman detail, ID diambil nanti dari isinya
        return GET(baseUrl + manga.url, headers)
    }

    // Di Ikiru.kt
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body!!.string())

        val mangaId = IkiruUtils.findMangaId(document)
            ?: throw Exception("Manga ID tidak ditemukan")
        val chapterId = IkiruUtils.findChapterId(document)
            ?: throw Exception("Chapter ID tidak ditemukan")

        // Gunakan ajaxHandler yang sudah memiliki getChapterList optimized
        return ajaxHandler.getChapterList(mangaId, chapterId)
    }

    // Page List
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("""
            section.mx-auto img, 
            section.w-full img, 
            div.reading-content img,
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
    
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }
    
    fun parseIsoDate(isoString: String): Long {
        return try {
            isoDateFormat.parse(isoString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
