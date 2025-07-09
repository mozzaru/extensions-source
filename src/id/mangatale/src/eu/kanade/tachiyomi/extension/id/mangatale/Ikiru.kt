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

    override fun headersBuilder() =
        super
            .headersBuilder()
            .add("Referer", baseUrl)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            )

    override fun popularMangaRequest(page: Int): Request {
        val body =
            FormBody
                .Builder()
                .add("orderby", "popular")
                .add("page", page.toString())
                .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaResponse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val body =
            FormBody
                .Builder()
                .add("orderby", "updated")
                .add("page", page.toString())
                .build()
        return POST("$baseUrl/ajax-call?action=advanced_search", headers, body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaResponse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val formBody =
            FormBody
                .Builder()
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

                mangas.add(
                    SManga.create().apply {
                        url = link.attr("href").removePrefix(baseUrl)
                        this.title = title
                        thumbnail_url = img ?: ""
                    },
                )
            }
        }

        if (mangas.isEmpty()) {
            document.select("img.wp-post-image").forEach { img ->
                img.parents().firstOrNull { it.attr("href")?.contains("/manga/") == true }?.let { link ->
                    mangas.add(
                        SManga.create().apply {
                            url = link.attr("href").removePrefix(baseUrl)
                            title = img.attr("alt").ifBlank { "Unknown" }
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
    
        // Ambil alternatif judul (alias) jika ada
        val altTitle = document.selectFirst("div.block.text-sm.text-text.line-clamp-1")
            ?.text()?.trim()
    
        // Ambil deskripsi lengkap dari [data-show=false] atau fallback
        val desc = document.select("div[itemprop=description][data-show=false]")
            .joinToString("\n") { it.text().trim() }
            .ifBlank {
                document.select("div[itemprop=description]")
                    .joinToString("\n") { it.text().trim() }
            }.ifBlank { "Tidak ada deskripsi." }
    
        return SManga.create().apply {
            title = document.selectFirst("h1[itemprop=name]")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("div[itemprop=image] img")?.absUrl("src") ?: ""
    
            description = buildString {
                if (!altTitle.isNullOrEmpty()) {
                    append("Nama Alternatif: $altTitle\n\n")
                }
                append(desc)
            }
    
            // Author
            author = document.selectFirst("div:has(h4:contains(Author)) > div > p")
                ?.text()?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("[itemprop=author]")?.text()
                ?: "Tidak diketahui"
    
            // Genre + Type (manhwa/manhua/manga)
            val genres = document.select("a[href*='/genre/']").map { it.text().trim() }.toMutableList()
    
            document.selectFirst("div:has(h4:contains(Type)) > div > p")?.text()?.trim()?.let { type ->
                if (type.isNotEmpty() && !genres.contains(type)) {
                    genres.add(0, type) // tambahkan type di depan jika belum ada
                }
            }
    
            genre = genres.joinToString()
    
            // Status
            val rawStatus = document.selectFirst("div:has(h4:contains(Status)) > div > p")
                ?.text()?.trim()?.lowercase(Locale.ROOT) ?: ""
    
            status = when {
                rawStatus.matches(Regex(".*(berlanjut|ongoing).*")) -> SManga.ONGOING
                rawStatus.matches(Regex(".*(tamat|selesai|completed).*")) -> SManga.COMPLETED
                rawStatus.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body!!.string())
        val body = document.html()
    
        val mangaId = findMangaId(document, body) ?: throw Exception("Manga ID tidak ditemukan")
        val chapterId = findChapterId(document, body) ?: throw Exception("Chapter ID tidak ditemukan")
    
        val chapters = mutableListOf<SChapter>()
    
        listOf("head", "footer").forEach { loc ->
            val ajaxUrl = "$baseUrl/ajax-call?action=chapter_selects&manga_id=$mangaId&chapter_id=$chapterId&loc=$loc"
            val ajaxRes = client.newCall(GET(ajaxUrl, headers)).execute()
            val ajaxBody = ajaxRes.body!!.string()
            val ajaxDoc = Jsoup.parse(ajaxBody)
    
            ajaxDoc.select("a[href*=/chapter-], button[onclick*='location.href']").forEach { el ->
                val href = el.attr("href").ifEmpty {
                    // untuk button: onclick="location.href='/chapter-12.123456'"
                    Regex("""['"](/chapter-[^'"]+)['"]""").find(el.attr("onclick"))?.groupValues?.get(1).orEmpty()
                }
    
                if (href.isBlank() || !href.contains("/chapter-")) return@forEach
                val name = el.text().trim()
                val dateStr = el.selectFirst("time")?.text()?.trim().orEmpty()
    
                chapters.add(
                    SChapter.create().apply {
                        url = href.removePrefix(baseUrl)
                        this.name = name
                        date_upload = parseChapterDate(dateStr)
                    }
                )
            }
        }
    
        return chapters
            .distinctBy { it.url }
            .sortedByDescending {
                Regex("""\d+(\.\d+)?""").find(it.name)?.value?.toFloatOrNull() ?: 0f
            }
    }

    private fun findMangaId(document: Document, body: String): String? {
        // 1. Coba langsung dari URL ajax-call
        Regex("""manga_id=(\d+)""").find(body)?.let { return it.groupValues[1] }
    
        // 2. Fallback dari hx-get
        document.select("[hx-get]").forEach {
            Regex("""manga_id=(\d+)""").find(it.attr("hx-get"))?.let { match ->
                return match.groupValues[1]
            }
        }
    
        // 3. Fallback dari data attribute
        document.select("[data-manga-id]").firstOrNull()?.attr("data-manga-id")?.let {
            return it
        }
    
        return null
    }
    
    private fun findChapterId(document: Document, body: String): String? {
        // 1. Cari dari URL ajax-call
        Regex("""chapter_id=(\d+)""").find(body)?.let { return it.groupValues[1] }
    
        // 2. Fallback dari href tombol chapter
        document.select("a[href*=/chapter-]").firstOrNull()?.attr("href")?.let { href ->
            Regex("""chapter-\d+\.(\d+)""").find(href)?.let { return it.groupValues[1] }
        }
    
        return null
    }

    private fun parseChapterDate(dateString: String): Long {
        val lc = dateString.lowercase(Locale.ENGLISH).trim()
    
        return when {
            lc.contains("just now") || lc.contains("baru saja") -> Calendar.getInstance().timeInMillis
    
            lc.contains("min") || lc.contains("menit") -> {
                val minutes = Regex("""(\d+)""").find(lc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                Calendar.getInstance().apply {
                    add(Calendar.MINUTE, -minutes)
                }.timeInMillis
            }
    
            lc.contains("hour") || lc.contains("jam") -> {
                val hours = Regex("""(\d+)""").find(lc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                Calendar.getInstance().apply {
                    add(Calendar.HOUR, -hours)
                }.timeInMillis
            }
    
            lc.contains("day") || lc.contains("hari") -> {
                val days = Regex("""(\d+)""").find(lc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                Calendar.getInstance().apply {
                    add(Calendar.DATE, -days)
                }.timeInMillis
            }
    
            lc.contains("week") || lc.contains("minggu") -> {
                val weeks = Regex("""(\d+)""").find(lc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                Calendar.getInstance().apply {
                    add(Calendar.WEEK_OF_YEAR, -weeks)
                }.timeInMillis
            }
    
            lc.contains("month") || lc.contains("bulan") -> {
                val months = Regex("""(\d+)""").find(lc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                Calendar.getInstance().apply {
                    add(Calendar.MONTH, -months)
                }.timeInMillis
            }
    
            else -> {
                try {
                    // Misal: "July 1, 2024"
                    SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).parse(dateString)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body!!.string())
        return document
            .select(
                """
                section.mx-auto img, 
                section.w-full img, 
                div.reading-content img,
                img[src*='/wp-content/uploads/']
                """.trimIndent(),
            ).mapIndexed { index, img ->
                Page(index, "", img.absUrl("src").ifBlank { img.absUrl("data-src") })
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun getFilterList(): FilterList = FilterList()
}
