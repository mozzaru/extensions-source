package eu.kanade.tachiyomi.extension.id.mangatale

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Ikiru : ParsedHttpSource() {
    override val name = "Ikiru"
    override val baseUrl = "https://id.ikiru.wtf"
    override val lang = "id"
    override val supportsLatest = true
    override val id = 1532456597012176985
    
    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

    // Fix popular manga request - use GET instead of POST
    override fun popularMangaRequest(page: Int): Request {
        Log.d("IkiruId", "Requesting popular manga page: $page")
        return GET("$baseUrl/popular/page/$page", headers)
    }

    // Fix latest updates request
    override fun latestUpdatesRequest(page: Int): Request {
        Log.d("IkiruId", "Requesting latest updates page: $page")
        return GET("$baseUrl/latest/page/$page", headers)
    }

    // Fix search request
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        Log.d("IkiruId", "Searching manga: query='$query', page=$page")
        return GET("$baseUrl/search?q=$query&page=$page", headers)
    }

    // Update selectors to match the website structure
    override fun popularMangaSelector() = "div.manga-item, div.grid-item, article.manga"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = mangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = mangaFromElement(element)
    override fun searchMangaFromElement(element: Element): SManga = mangaFromElement(element)

    private fun mangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        
        // Try multiple ways to find the manga link
        val linkElement = element.selectFirst("a[href]") ?: element
        manga.url = linkElement.attr("href").let { href ->
            if (href.startsWith("http")) href else baseUrl + href.removePrefix("/")
        }
        
        // Try multiple ways to find the title
        manga.title = element.selectFirst("h3, h2, h1, .title, .manga-title")?.text()?.trim()
            ?: element.selectFirst("a")?.attr("title")?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: "Tanpa Judul"
        
        // Try multiple ways to find the thumbnail
        manga.thumbnail_url = element.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifBlank { 
                img.attr("data-lazy-src").ifBlank { 
                    img.attr("src") 
                }
            }
            when {
                src.startsWith("http") -> src
                src.startsWith("/") -> baseUrl + src
                src.isNotBlank() -> "$baseUrl/$src"
                else -> null
            }
        }
        
        Log.d("IkiruId", "Parsed manga: title='${manga.title}', url='${manga.url}'")
        return manga
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        
        // Try multiple selectors for title
        manga.title = document.selectFirst("h1.entry-title, h1.manga-title, h1")?.text()?.trim()
            ?: document.title()
        
        // Try multiple selectors for thumbnail
        manga.thumbnail_url = document.selectFirst("div.thumb img, .manga-cover img, .cover img, img.wp-post-image")?.let { img ->
            val src = img.attr("data-src").ifBlank { 
                img.attr("data-lazy-src").ifBlank { 
                    img.attr("src") 
                }
            }
            when {
                src.startsWith("http") -> src
                src.startsWith("/") -> baseUrl + src
                src.isNotBlank() -> "$baseUrl/$src"
                else -> null
            }
        }
        
        // Try multiple selectors for description
        manga.description = document.selectFirst("div.entry-content, .summary, .description, [itemprop=description]")
            ?.text()?.trim()
        
        // Try multiple selectors for genre
        manga.genre = document.select("a[href*=genre], .genre a, .genres a")
            .joinToString { it.text().trim() }
        
        // Try to determine status
        val statusText = document.selectFirst(".status, .manga-status")?.text()?.lowercase()
        manga.status = when {
            statusText?.contains("ongoing") == true -> SManga.ONGOING
            statusText?.contains("completed") == true -> SManga.COMPLETED
            statusText?.contains("hiatus") == true -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        
        return manga
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        Log.d("IkiruId", "Requesting chapter list → $url")
        return GET(url, headers)
    }

    // Update chapter selectors
    override fun chapterListSelector() = "div.chapter-list a, .chapter-item a, li.chapter a, .episodelist a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.url = element.attr("href").let { href ->
            if (href.startsWith("http")) href else baseUrl + href.removePrefix("/")
        }
        
        // Try multiple ways to get chapter name
        chapter.name = element.selectFirst(".chapter-title, .chapter-name")?.text()?.trim()
            ?: element.text().trim()
            ?: element.attr("title").trim()
            ?: "Chapter"
        
        // Try to get date
        val dateText = element.selectFirst(".chapter-date, .date")?.text()?.trim()
        if (!dateText.isNullOrBlank()) {
            try {
                // You might need to implement date parsing based on the site's format
                // chapter.date_upload = parseDate(dateText)
            } catch (e: Exception) {
                Log.e("IkiruId", "Error parsing date: $dateText", e)
            }
        }
        
        Log.d("IkiruId", "Parsed chapter: name='${chapter.name}', url='${chapter.url}'")
        return chapter
    }

    override fun pageListParse(document: Document): List<Page> {
        // Try multiple selectors for images
        val images = document.select("div.reader-area img, .chapter-content img, .entry-content img, #chapter-images img")
        
        val pages = images.mapIndexedNotNull { index, img ->
            val imgUrl = img.attr("data-src").ifBlank { 
                img.attr("data-lazy-src").ifBlank { 
                    img.attr("src") 
                }
            }
            
            val fullUrl = when {
                imgUrl.startsWith("http") -> imgUrl
                imgUrl.startsWith("/") -> baseUrl + imgUrl
                imgUrl.isNotBlank() -> "$baseUrl/$imgUrl"
                else -> null
            }
            
            if (fullUrl != null) {
                Log.d("IkiruId", "Parsed page[$index] = $fullUrl")
                Page(index, "", fullUrl)
            } else {
                Log.w("IkiruId", "Skipping empty image at index $index")
                null
            }
        }
        
        return pages
    }

    override fun imageUrlParse(document: Document): String = ""

    override fun getFilterList(): FilterList = FilterList()

    // Update pagination selectors
    override fun popularMangaNextPageSelector(): String? = "a.next, .next-page, .pagination a[rel=next]"
    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()
}