package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MGKomik : ParsedHttpSource() {

    override val name = "MGKomik"
    override val baseUrl = "https://id.mgkomik.cc"
    override val lang = "id"
    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    // Cloudflare handling
    private val cloudflareClient by lazy {
        network.client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .rateLimit(2)
            .addInterceptor(CloudflareInterceptor())
            .build()
    }

    override val client: OkHttpClient = cloudflareClient

    private class CloudflareInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
                .newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            
            val response = chain.proceed(request)
            
            if (response.code == 503 && response.header("Server")?.contains("cloudflare") == true) {
                response.close()
                throw Exception("Cloudflare challenge detected. Please try again later.")
            }
            
            return response
        }
    }

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/?order=popular&page=$page", headers)
    }

    override fun popularMangaSelector() = "div.bs div.bsx"
    
    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
            title = element.selectFirst(".tt")?.text()?.trim() ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("src")?.let { 
                if (it.startsWith("http")) it else baseUrl + it
            } ?: ""
        }
    }
    
    override fun popularMangaNextPageSelector() = "a.next"

    // Latest Manga
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/?order=update&page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    
    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }
    
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search Manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            GET("$baseUrl/?s=${query.encodeURL()}&post_type=manga&page=$page", headers)
        } else {
            val params = filters.map { filter ->
                when (filter) {
                    is OrderFilter -> "order=${filter.values[filter.state].second}"
                    is StatusFilter -> "status=${filter.values[filter.state].second}"
                    is TypeFilter -> "type=${filter.values[filter.state].second}"
                    is GenreFilter -> filter.state
                        .filter { it.isChecked }
                        .joinToString("&") { "genre[]=${it.value}" }
                    else -> ""
                }
            }.filter { it.isNotEmpty() }.joinToString("&")

            GET("$baseUrl/manga/?page=$page${if (params.isNotEmpty()) "&$params" else ""}", headers)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()
    
    override fun searchMangaFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }
    
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga Details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst(".entry-title")?.text() ?: ""
            thumbnail_url = document.selectFirst(".thumb img")?.attr("src") ?: ""
            description = document.selectFirst(".entry-content[itemprop=description]")?.text()?.trim()
            
            document.select(".spe .content").forEach { element ->
                val label = element.previousElementSibling()?.text()?.lowercase(Locale.US) ?: return@forEach
                val value = element.text().trim()
                
                when {
                    label.contains("author") -> author = value
                    label.contains("artist") -> artist = value
                    label.contains("status") -> status = when {
                        value.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
                        value.contains("completed", ignoreCase = true) -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                    label.contains("genre") -> genre = value.split(",").map { it.trim() }.joinToString(", ")
                }
            }
        }
    }

    // Chapter List
    override fun chapterListSelector() = ".eplister li"
    
    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
            name = element.selectFirst(".chapternum")?.text()?.trim() ?: ""
            date_upload = element.selectFirst(".chapterdate")?.text()?.let {
                try {
                    dateFormat.parse(it)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            } ?: 0L
        }
    }

    // Page List
    override fun pageListParse(document: Document): List<Page> {
        return document.select(".reader-area img").mapIndexed { index, element ->
            Page(index, "", element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(document: Document): String = ""    

    private fun String.encodeURL(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}