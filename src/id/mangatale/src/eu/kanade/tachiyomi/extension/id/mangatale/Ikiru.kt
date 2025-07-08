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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Ikiru : HttpSource() {
    override val name = "Ikiru"
    override val baseUrl = "https://id.ikiru.wtf"
    override val lang = "id"
    override val supportsLatest = true
    override val id = 1532456597012176985

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")

    override fun popularMangaRequest(page: Int): Request {
        val body = FormBody.Builder()
            .add("orderby", "popular")
            .add("page", page.toString())
            .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaResponse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val body = FormBody.Builder()
            .add("orderby", "updated")
            .add("page", page.toString())
            .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaResponse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBody = FormBody.Builder()
            .add("page", page.toString())
            .add("query", query)
            .add("orderby", "popular")
            .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, formBody)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaResponse(response)

    private fun parseMangaResponse(response: Response): MangasPage {
        val raw = response.body!!.string()
        if (raw.contains("Just a moment") || raw.contains("Cloudflare")) {
            throw Exception("Diblokir Cloudflare - Silakan coba lagi nanti")
        }

        val document = Jsoup.parse(raw)
        val mangas = mutableListOf<SManga>()

        document.select("div.flex.rounded-lg.overflow-hidden, div.group-data-\\[mode\\=horizontal\\]\\:hidden").forEach { item ->
            item.selectFirst("a[href^='/manga/']")?.let { link ->
                val img = item.selectFirst("img.wp-post-image, img")?.absUrl("src")
                val titleElement = item.selectFirst("h1, h2, h3, div.text-base")
                val title = titleElement?.text()?.trim() ?: item.selectFirst("img")?.attr("alt") ?: "Tanpa Judul"
                
                mangas.add(SManga.create().apply {
                    url = link.attr("href").removePrefix(baseUrl)
                    this.title = title
                    thumbnail_url = img ?: ""
                })
            }
        }

        if (mangas.isEmpty()) {
            document.select("img.wp-post-image").forEach { img ->
                img.parents().firstOrNull { it.attr("href")?.contains("/manga/") == true }?.let { link ->
                    mangas.add(SManga.create().apply {
                        url = link.attr("href").removePrefix(baseUrl)
                        title = img.attr("alt").ifBlank { "Unknown" }
                        thumbnail_url = img.absUrl("src")
                    })
                }
            }
        }

        val hasNextPage = mangas.size >= 18
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body!!.string())
    
        return SManga.create().apply {
            title = document.selectFirst("h1[itemprop=name]")?.text() ?: "Judul tidak ditemukan"
            thumbnail_url = document.selectFirst("div[itemprop=image] img")?.absUrl("src") ?: ""
            description = document.selectFirst("div[itemprop=description]")?.text()?.trim() ?: "Tidak ada deskripsi."
            author = "Tidak diketahui"
    
            val infoBlocks = document.select("div.space-y-2 > div.flex")
            val type = infoBlocks.find { it.selectFirst("h4")?.text()?.contains("Type") == true }
                ?.selectFirst("p")?.text()
            genre = type
    
            status = when {
                document.select("button:contains(Completed)").isNotEmpty() -> SManga.COMPLETED
                document.select("button:contains(Ongoing)").isNotEmpty() -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val raw = response.body!!.string()
        if (raw.contains("Cloudflare")) throw Exception("Diblokir Cloudflare")
        
        val document = Jsoup.parse(raw)
        
        // First check for "no chapters" message
        if (document.select("div:contains(0 bab), div:contains(Tidak ada bab)").isNotEmpty()) {
            return emptyList()
        }
        
        val mangaId = findMangaId(document, raw)
    ?: throw Exception("Manga ID tidak ditemukan")

        println("DEBUG: mangaId ditemukan = $mangaId")
        
        val ajaxResponse = client.newCall(
            GET("$baseUrl/ajax-call?action=chapter_selects&manga_id=$mangaId", headers)
        ).execute()
        
        val ajaxBody = ajaxResponse.body!!.string()
        if (ajaxBody.contains("Cloudflare")) throw Exception("Diblokir Cloudflare pada AJAX")
        
        val ajaxDoc = Jsoup.parse(ajaxBody)
        return ajaxDoc.select("a[href]").mapNotNull { element ->
            element.attr("href").takeIf { it.contains("/chapter/") }?.let { href ->
                SChapter.create().apply {
                    url = href.removePrefix(baseUrl)
                    name = element.selectFirst(".text-sm, .truncate")?.text()?.trim() ?: "Chapter"
                    date_upload = element.selectFirst("time")?.text()?.let {
                        parseChapterDate(it)
                    } ?: 0L
                }
            }
        }.reversed()
    }
    
    private fun findMangaId(document: Document, body: String): String? {
        // 1. Cari dari atribut hx-get
        document.select("[hx-get]").forEach {
            Regex("""manga_id=(\d+)""").find(it.attr("hx-get"))?.let { match ->
                return match.groupValues[1]
            }
        }
    
        // 2. Cari dari JS fetch seperti: manga_id=731207&chapter_id=731211
        Regex("""manga_id=(\d+)&chapter_id=\d+""").find(body)?.let {
            return it.groupValues[1]
        }
    
        // 3. Cari dari image path storage
        document.select("img[src*='/storage/']").firstOrNull()?.attr("src")?.let { src ->
            Regex("""/(\d+)/[^/]+\.(jpg|png|webp)""").find(src)?.let { match ->
                return match.groupValues[1]
            }
        }
    
        // 4. Cari dari data attribute
        document.select("[data-manga-id]").firstOrNull()?.attr("data-manga-id")?.let {
            return it
        }
    
        // 5. Cari dari isi <script> (beberapa pola umum)
        val scriptPatterns = listOf(
            """manga_id['"]?\s*[:=]\s*['"]?(\d+)""",
            """data-manga-id=['"](\d+)['"]""",
            """ajax-call\?.*manga_id=(\d+)""",
            """manga_id\s*=\s*['"]?(\d+)""",
            """"manga_id":\s*(\d+)"""
        )
    
        scriptPatterns.forEach { pattern ->
            Regex(pattern).find(body)?.let { match ->
                return match.groupValues[1]
            }
        }
    
        // 6. Cari dari URL
        Regex("""/manga/[^/]+/(\d+)""").find(document.location())?.let {
            return it.groupValues[1]
        }
    
        return null
    }

    private fun parseChapterDate(dateString: String): Long {
        if (dateString.isEmpty()) return 0L
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
        val now = Calendar.getInstance()

        return when {
            dateString.contains("baru saja") -> now.timeInMillis
            dateString.contains("menit") -> {
                val minutes = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.MINUTE, -minutes)
                now.timeInMillis
            }
            dateString.contains("jam") -> {
                val hours = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.HOUR, -hours)
                now.timeInMillis
            }
            dateString.contains("hari") -> {
                val days = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.DATE, -days)
                now.timeInMillis
            }
            dateString.contains("minggu") -> {
                val weeks = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.WEEK_OF_YEAR, -weeks)
                now.timeInMillis
            }
            dateString.contains("bulan") -> {
                val months = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.MONTH, -months)
                now.timeInMillis
            }
            else -> try {
                dateFormat.parse(dateString)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("""
            section.mx-auto img, 
            section.w-full img, 
            div.reading-content img,
            img[src*='/wp-content/uploads/']
        """.trimIndent()).mapIndexed { index, img ->
            Page(index, "", img.absUrl("src").ifBlank { img.absUrl("data-src") })
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun getFilterList(): FilterList = FilterList()
}
