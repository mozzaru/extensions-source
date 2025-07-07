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

    override fun popularMangaParse(response: Response): MangasPage {
        return parseMangaResponse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val body = FormBody.Builder()
            .add("orderby", "updated")
            .add("page", page.toString())
            .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return parseMangaResponse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var type = ""
        var status = ""
        var genres = ""
    
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> type = filter.toUriPart()
                is StatusFilter -> status = filter.toUriPart()
                is GenreFilterList -> {
                    genres = filter.state.filter { it.state }.joinToString(",") { it.id }
                }
                else -> {}
            }
        }
    
        val formBody = FormBody.Builder()
            .add("page", page.toString())
            .add("query", query)
            .add("the_type", type)
            .add("the_status", status)
            .add("the_genre", genres)
            .add("orderby", "popular") // default sort
    
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, formBody.build())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parseMangaResponse(response)
    }

    private fun parseMangaResponse(response: Response): MangasPage {
        val raw = response.body!!.use { it.string() }
        if (raw.contains("Just a moment") || raw.contains("Cloudflare")) {
            throw Exception("Diblokir Cloudflare - Silakan coba lagi nanti")
        }
        
        val document = Jsoup.parse(raw)
        val mangas = mutableListOf<SManga>()
        
        // Cek struktur hasil yang berbeda
        val items = document.select("div.flex.rounded-lg.overflow-hidden, div.group-data-\\[mode\\=horizontal\\]\\:hidden")
        
        if (items.isEmpty()) {
            // Alternatif: Mode horizontal
            document.select("div.flex.rounded-lg.overflow-hidden").forEach { item ->
                parseMangaItem(item)?.let { mangas.add(it) }
            }
            
            // Alternatif: Mode vertikal
            if (mangas.isEmpty()) {
                document.select("div.group-data-\\[mode\\=horizontal\\]\\:hidden").forEach { item ->
                    parseMangaItem(item)?.let { mangas.add(it) }
                }
            }
        } else {
            items.forEach { item ->
                parseMangaItem(item)?.let { mangas.add(it) }
            }
        }
        
        // Alternatif terakhir: Cari semua elemen dengan gambar manga
        if (mangas.isEmpty()) {
            document.select("img.wp-post-image").forEach { img ->
                val parent = img.parents().firstOrNull { it.hasAttr("href") && it.attr("href").contains("/manga/") }
                parent?.let { link ->
                    val title = img.attr("alt").takeIf { it.isNotBlank() } ?: "Unknown"
                    mangas.add(SManga.create().apply {
                        val href = link.attr("href")
url = href.removePrefix(baseUrl).ifBlank { href }
                        this.title = title
                        thumbnail_url = img.absUrl("src")
                    })
                }
            }
        }
        
        val hasNextPage = mangas.size >= 18
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }
    
    private fun parseMangaItem(item: Element): SManga? {
        return try {
            val link = item.selectFirst("a[href^='/manga/']") ?: return null
    
            val img = item.selectFirst("img.wp-post-image, img")?.absUrl("src")
    
            // Ambil dari <h1 class="text-[15px] ..."> atau fallback ke alt img
            val titleElement = item.selectFirst("h1.text-\\[15px\\], h1")
            val titleText = titleElement?.text()?.takeIf { it.isNotBlank() }
                ?: item.selectFirst("img")?.attr("alt")
                ?: "Tanpa Judul"
    
            SManga.create().apply {
                val href = link.attr("href")
url = href.removePrefix(baseUrl).ifBlank { href }
                title = titleText
                thumbnail_url = img ?: ""
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body!!.use { it.string() })
    
        val statusText = document.selectFirst("div.post-status span")?.text().orEmpty().lowercase()
    
        return SManga.create().apply {
            title = document.selectFirst("div.post-title h1")?.text().orEmpty()
            author = document.select("div.author-content a").firstOrNull()?.text().orEmpty()
            genre = document.select("div.genres-content a").joinToString(", ") { it.text() }
    
            description = document
                .selectFirst("div.description-summary div.summary__content")
                ?.text()
                ?.ifBlank { "Tidak ada deskripsi" }
                ?: "Tidak ada deskripsi"
    
            status = when {
                "ongoing" in statusText -> SManga.ONGOING
                "completed" in statusText -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
    
            thumbnail_url = document.selectFirst("div.summary_image img")?.absUrl("src") ?: ""
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url
            .removePrefix("/manga/")
            .removePrefix("manga/")
            .removeSuffix("/")
        return GET("$baseUrl/ajax-call?action=chapter_list&slug=$slug", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document: Document = Jsoup.parse(response.body!!.use { it.string() })
        return document.select("a[href*=/chapter-]").map { element ->
            val chapterName = element.text().ifBlank { "Chapter Tidak Bernama" }
            val dateText = element.selectFirst("span")?.text()?.trim() ?: ""
            val parsedDate = parseChapterDate(dateText)

            SChapter.create().apply {
                val href = element.attr("href")
url = href.removePrefix(baseUrl).ifBlank { href }
                name = chapterName
                date_upload = parsedDate
            }
        }.reversed()
    }

    private fun parseChapterDate(dateString: String): Long {
        if (dateString.isEmpty()) return 0L
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
        val relativeFormat = SimpleDateFormat("yyyy-MM-dd", Locale("id"))
        val now = Calendar.getInstance()

        return when {
            dateString.contains("baru saja") || dateString.contains("just now") -> now.timeInMillis
            dateString.contains("menit") -> {
                val minutes = Regex("""(\d+)""").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.MINUTE, -minutes)
                now.timeInMillis
            }
            dateString.contains("jam") -> {
                val hours = Regex("""(\d+)""").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.HOUR, -hours)
                now.timeInMillis
            }
            dateString.contains("hari") || dateString.contains("days") -> {
                val days = Regex("""(\d+)""").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.DATE, -days)
                now.timeInMillis
            }
            dateString.contains("minggu") || dateString.contains("weeks") -> {
                val weeks = Regex("""(\d+)""").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.WEEK_OF_YEAR, -weeks)
                now.timeInMillis
            }
            dateString.contains("bulan") || dateString.contains("months") -> {
                val months = Regex("""(\d+)""").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.MONTH, -months)
                now.timeInMillis
            }
            else -> try {
                dateFormat.parse(dateString)?.time ?: relativeFormat.parse(dateString)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document: Document = Jsoup.parse(response.body!!.use { it.string() })
        val pages = mutableListOf<Page>()
        
        // Selektor utama untuk gambar chapter
        document.select("div.reading-content img").forEachIndexed { index, img ->
            val url = img.absUrl("src").takeIf { it.isNotBlank() } 
                ?: img.absUrl("data-src") 
                ?: ""
            
            if (url.isNotBlank()) {
                pages.add(Page(index, imageUrl = url))
            }
        }
        
        // Alternatif jika tidak ditemukan gambar
        if (pages.isEmpty()) {
            document.select("img[src*='/wp-content/'], img[src*='/images/']").forEachIndexed { index, img ->
                pages.add(Page(index, imageUrl = img.absUrl("src")))
            }
        }
        
        return pages
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun getFilterList(): FilterList {
        return getFilterListInternal()
    }
}
