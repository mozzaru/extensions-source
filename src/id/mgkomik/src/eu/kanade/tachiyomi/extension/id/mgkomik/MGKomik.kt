package eu.kanade.tachiyomi.extension.id.mgkomik

import android.util.Log
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class MGKomik : HttpSource() {

    override val name = "MGKomik"
    override val baseUrl = "https://id.mgkomik.cc"
    override val lang = "id"
    override val supportsLatest = true

    companion object {
        private const val TAG = "MGKomikLog"
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android)")

    override fun popularMangaRequest(page: Int): Request {
        Log.d(TAG, "Requesting popular manga, page=$page")
        return Request.Builder()
            .url("$baseUrl/manga/?page=$page")
            .headers(headers)
            .build()
    }

    override fun popularMangaParse(response: Response): MangasPage {
        Log.d(TAG, "Parsing popular manga (url=${response.request.url})")
        val body = response.body?.string()
        val document = Jsoup.parse(body)
        val mangas = document.select(".post-title a").map { element ->
            SManga.create().apply {
                title = element.text()
                setUrlWithoutDomain(element.attr("href"))
                thumbnail_url = element.parents().select(".img-thumbnail").attr("src")
            }
        }
        Log.d(TAG, "Parsed ${mangas.size} popular manga")
        val hasNextPage = document.select(".nav-previous, .nav-next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        Log.d(TAG, "Requesting latest updates, page=$page")
        return Request.Builder()
            .url("$baseUrl/manga/?page=$page&order=update")
            .headers(headers)
            .build()
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        Log.d(TAG, "Parsing latest updates (url=${response.request.url})")
        return popularMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        Log.d(TAG, "Searching manga: query=\"$query\", page=$page")
        return Request.Builder()
            .url("$baseUrl/?s=$query&page=$page")
            .headers(headers)
            .build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        Log.d(TAG, "Parsing search results (url=${response.request.url})")
        return popularMangaParse(response)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        Log.d(TAG, "Requesting manga details: url=${manga.url}")
        return Request.Builder()
            .url(baseUrl + manga.url)
            .headers(headers)
            .build()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        Log.d(TAG, "Parsing manga details (url=${response.request.url})")
        val document = Jsoup.parse(response.body?.string())
        val manga = SManga.create().apply {
            title = document.selectFirst(".post-title")?.text() ?: ""
            author = document.selectFirst(".author-content")?.text()
            artist = author
            genre = document.select(".genres-content a").joinToString { it.text() }
            description = document.selectFirst(".summary__content")?.text()
            status = SManga.ONGOING
            thumbnail_url = document.selectFirst(".summary_image img")?.attr("src")
        }
        Log.d(TAG, "Manga details: title=${manga.title}, author=${manga.author}")
        return manga
    }

    override fun chapterListRequest(manga: SManga): Request {
        Log.d(TAG, "Requesting chapter list: url=${manga.url}")
        return Request.Builder()
            .url(baseUrl + manga.url)
            .headers(headers)
            .build()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        Log.d(TAG, "Parsing chapter list (url=${response.request.url})")
        val document = Jsoup.parse(response.body?.string())
        val chapters = document.select(".wp-manga-chapter a").map { el ->
            SChapter.create().apply {
                setUrlWithoutDomain(el.attr("href"))
                name = el.text()
            }
        }.reversed()
        Log.d(TAG, "Parsed ${chapters.size} chapters")
        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        Log.d(TAG, "Requesting page list: url=${chapter.url}")
        return Request.Builder()
            .url(baseUrl + chapter.url)
            .headers(headers)
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {
        Log.d(TAG, "Parsing page list (url=${response.request.url})")
        val document = Jsoup.parse(response.body?.string())
        val pages = document.select(".reading-content img").mapIndexed { i, el ->
            Page(i, "", el.attr("data-src") ?: el.attr("src"))
        }
        Log.d(TAG, "Parsed ${pages.size} pages")
        return pages
    }

    override fun imageRequest(page: Page): Request {
        Log.d(TAG, "Requesting image: url=${page.imageUrl}")
        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(headers)
            .build()
    }
    
    override fun imageUrlParse(response: Response): String {
        return response.request.url.toString()
    }
}

class MGKomikFactory : SourceFactory {
    override fun createSources() = listOf(MGKomik())
}
