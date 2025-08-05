package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.time.Instant

class Ikiru : HttpSource() {
    override val name = "Ikiru"
    override val baseUrl = "https://01.ikiru.wtf"
    override val lang = "id"
    override val supportsLatest = true
    override val id = 1532456597012176985

    override val client = network.cloudflareClient

    private val ajaxHandler by lazy { IkiruAjax(client, baseUrl, headers) }
    private val mangaParser by lazy { IkiruMangaParser() }
    private val ajaxHelper by lazy { IkiruAjax(client, baseUrl, headers) }

    override fun headersBuilder() =
        super.headersBuilder()
            .add("Referer", baseUrl)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
    
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            // 1) Load halaman manga
            val resp = client.newCall(GET(baseUrl + manga.url, headers)).execute()
            val doc  = Jsoup.parse(resp.body!!.string())

            // 2) Ambil mangaId & chapterId
            val mangaId   = IkiruUtils.findMangaId(doc)
                ?: throw IOException("Gagal ekstrak manga_id")
            val chapterId = IkiruUtils.findChapterId(doc)

            // 3) Ambil URL AJAX dari hx-get di #chapter-list
            val rawPath = doc.selectFirst("#chapter-list[hx-get]")
                ?.attr("hx-get")?.trim()
                ?: throw IOException("Tidak dapat menemukan URL AJAX chapter-list")
            val ajaxUrl = if (rawPath.startsWith("http")) rawPath else baseUrl + rawPath

            // 4) Panggil endpoint AJAX
            val ajaxResp = client.newCall(GET(ajaxUrl, headers)).execute()
            if (!ajaxResp.isSuccessful) throw IOException("Gagal load AJAX: ${ajaxResp.code}")
            val ajaxDoc = Jsoup.parse(ajaxResp.body!!.string())

            // 5) Parsing chapter list
            val list = ajaxDoc.select("a[href*=\"/chapter-\"]").map { el ->
                SChapter.create().apply {
                    url         = el.attr("href").removePrefix(baseUrl)
                    name        = el.selectFirst("p.inline-block")?.text().orEmpty().ifBlank { el.text().trim() }
                    date_upload = el.selectFirst("time[datetime]")
                        ?.attr("datetime")?.let { Instant.parse(it).toEpochMilli() } ?: 0L
                }
            }.toMutableList()

            // 6) Fallback head/footer bila list kosong
            if (list.isEmpty()) {
                list += ajaxHelper.getChapterList(mangaId, chapterId)
            }

            // 7) Dedup & kembalikan
            list.distinctBy { it.url }
        }
    }

    // Stub abstract
    override fun chapterListRequest(manga: SManga): Request =
        throw UnsupportedOperationException("Unused; use fetchChapterList()")
    override fun chapterListParse(response: okhttp3.Response): List<SChapter> =
        throw UnsupportedOperationException("Unused; use fetchChapterList()")

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
        val timestamp = System.currentTimeMillis().toString()
    
        val body = FormBody.Builder()
            .add("orderby", "updated")
            .add("page", page.toString())
            .add("t", timestamp) // tambahan timestamp agar fresh
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
                    if (type.isNotEmpty()) {
                        formBody.add("type", type)
                    }
                }
                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotEmpty()) {
                        formBody.add("status", status)
                    }
                }
                is GenreFilterList -> {
                    val genres = filter.state
                        .filter { it.state }
                        .joinToString(",") { it.id }
                    if (genres.isNotEmpty()) {
                        formBody.add("genres", genres)
                    }
                }
                else -> { /* Handle other filter types or ignore */ }
            }
        }
        
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, formBody.build())
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaResponse(response)

    // Common manga parsing
    private fun parseMangaResponse(response: Response): MangasPage {
        val raw = response.body?.string() ?: throw IOException("Empty response body")
        
        if (IkiruUtils.checkCloudflareBlock(raw)) {
            throw IOException("Diblokir Cloudflare - Silakan coba lagi nanti")
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
                    })
                }
            }
        }

        val hasNextPage = mangas.size >= 18
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$baseUrl${manga.url}?t=${System.currentTimeMillis()}"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        return mangaParser.parseMangaDetails(document)
    }

    // Selector untuk detail per chapter dari respons AJAX
    private fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            name = element.selectFirst("span.chapternum")!!.text()
            date_upload = parseChapterDate(element.selectFirst("span.chapterdate")?.text())
        }
    }

    // Fungsi untuk parsing tanggal, pastikan formatnya sesuai
    private fun parseChapterDate(date: String?): Long {
        if (date == null) return 0L
        return try {
            // Contoh format: "August 5, 2025". Sesuaikan jika berbeda.
            // Anda mungkin perlu menambahkan logika untuk menangani "X hours ago" atau "Kemarin"
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).parse(date)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }

    // Page List
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
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
    
    override fun getFilterList(): FilterList = getFilterListInternal()
}
