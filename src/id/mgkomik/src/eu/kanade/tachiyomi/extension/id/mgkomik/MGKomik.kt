package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import org.jsoup.Jsoup
import eu.kanade.tachiyomi.util.ScraperProvider

class MGKomikWebViewSource : HttpSource() {
    override val name = "MGKomik (WebView Mode)"
    override val baseUrl = "https://id.mgkomik.cc"
    override val lang = "id"
    override val supportsLatest = true

    fun fetchPopularManga(page: Int, callback: (MangasPage) -> Unit) {
        val url = "$baseUrl/manga/?page=$page"
        ScraperProvider.webViewScraper.getPageHtml(url) { html ->
            val document = Jsoup.parse(html)
            val mangas = document.select(".post-title a").map { element ->
                SManga.create().apply {
                    title = element.text()
                    setUrlWithoutDomain(element.attr("href"))
                    thumbnail_url = element.parents().select(".img-thumbnail").attr("src")
                }
            }
            val hasNextPage = document.select(".nav-previous, .nav-next").isNotEmpty()
            callback(MangasPage(mangas, hasNextPage))
        }
    }
    // Tambahkan method serupa untuk search, detail, dst.
}

class MGKomikFactory : SourceFactory {
    override fun createSources() = listOf(MGKomikWebViewSource())
}
