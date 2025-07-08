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
        val raw = response.body!!.use { it.string() }
        if (raw.contains("Just a moment") || raw.contains("Cloudflare")) {
            throw Exception("Diblokir Cloudflare - Silakan coba lagi nanti")
        }

        val document = Jsoup.parse(raw)
        val mangas = mutableListOf<SManga>()

        fun parseMangaItem(item: Element): SManga? {
            return try {
                val link = item.selectFirst("a[href^='/manga/']") ?: return null
                val img = item.selectFirst("img.wp-post-image, img")?.absUrl("src")

                val titleElement = item.selectFirst("h1.text-\\[15px\\], a.text-base, div.text-base")
                val titleText = titleElement?.text()?.trim() ?: item.selectFirst("img")?.attr("alt") ?: "Tanpa Judul"

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

        val items = document.select("div.flex.rounded-lg.overflow-hidden, div.group-data-\\[mode\\=horizontal\\]\\:hidden")

        if (items.isEmpty()) {
            document.select("div.flex.rounded-lg.overflow-hidden").forEach { item ->
                parseMangaItem(item)?.let { mangas.add(it) }
            }
        }

        if (mangas.isEmpty()) {
            document.select("div.group-data-\\[mode\\=horizontal\\]\\:hidden").forEach { item ->
                parseMangaItem(item)?.let { mangas.add(it) }
            }
        } else {
            items.forEach { item ->
                parseMangaItem(item)?.let { mangas.add(it) }
            }
        }

        if (mangas.isEmpty()) {
            document.select("img.wp-post-image").forEach { img ->
                val parent = img.parents().firstOrNull {
                    it.hasAttr("href") && it.attr("href").contains("/manga/")
                }
                parent?.let { link ->
                    val title = img.attr("alt").takeIf { it.isNotBlank() } ?: "Unknown"
                    mangas.add(
                        SManga.create().apply {
                            val href = link.attr("href")
                            url = href.removePrefix(baseUrl).ifBlank { href }
                            this.title = title
                            thumbnail_url = img.absUrl("src")
                        },
                    )
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
            status = SManga.UNKNOWN
            author = "Tidak diketahui"

            val infoBlocks = document.select("div.space-y-2 > div.flex")
            val type = infoBlocks.find { it.selectFirst("h4")?.text()?.contains("Type") == true }
                ?.selectFirst("p")?.text()
            genre = type
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body!!.string())
        val mangaId = document.selectFirst("#chapter-list")?.attr("hx-get")?.substringAfter("manga_id=")?.substringBefore("&") 
            ?: return emptyList()
        
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val ajaxResponse = try {
                client.newCall(
                    GET("$baseUrl/ajax-call?action=chapter_list&manga_id=$mangaId&page=$page", headers),
                ).execute()
            } catch (e: Exception) {
                break
            }
    
            val ajaxBody = ajaxResponse.use { it.body?.string() }
            if (ajaxBody.isNullOrEmpty()) {
                break
            }
    
            val ajaxDocument = Jsoup.parse(ajaxBody)
            val pageChapters = ajaxDocument.select("a[href*=/chapter/]").mapNotNull { element ->
                val href = element.attr("href")
                if (href.isBlank()) return@mapNotNull null
    
                SChapter.create().apply {
                    url = href.removePrefix(baseUrl).ifBlank { href }
                    name = element.selectFirst(".truncate, .chapter-title")?.text()?.trim()
                        ?: element.ownText().trim()
                    date_upload = element.selectFirst("time, .chapter-date")?.text()?.let {
                        parseChapterDate(it)
                    } ?: 0L
                }
            }
    
            if (pageChapters.isEmpty()) break
            chapters.addAll(pageChapters)
            page++
        }
        return chapters.reversed()
    }

    private fun parseChapterDate(dateString: String): Long {
        if (dateString.isEmpty()) return 0L
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
        val now = Calendar.getInstance()

        return when {
            dateString.contains("baru saja") || dateString.contains("just now") -> now.timeInMillis
            dateString.contains("menit") || dateString.contains("minutes ago") -> {
                val minutes = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.MINUTE, -minutes)
                now.timeInMillis
            }
            dateString.contains("jam") || dateString.contains("hours ago") -> {
                val hours = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.HOUR, -hours)
                now.timeInMillis
            }
            dateString.contains("hari") || dateString.contains("days") -> {
                val days = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.DATE, -days)
                now.timeInMillis
            }
            dateString.contains("minggu") || dateString.contains("weeks") -> {
                val weeks = Regex("(\\d+)").find(dateString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                now.add(Calendar.WEEK_OF_YEAR, -weeks)
                now.timeInMillis
            }
            dateString.contains("bulan") || dateString.contains("months") -> {
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
        val images = document.select("section.mx-auto section img[src]")

        if (images.isNotEmpty()) {
            return images.mapIndexed { index, img ->
                val imageUrl = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
                Page(index, imageUrl = imageUrl)
            }
        }

        return document.select("div.reading-content img, img[src*='/wp-content/']").mapIndexed { index, img ->
            val imageUrl = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun getFilterList(): FilterList = FilterList()
}
