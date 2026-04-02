package eu.kanade.tachiyomi.extension.id.inazumanga

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document

class ReYume : ZeistManga("ReYume", "https://www.re-yume.my.id", "id") {

    // Popular
    override val popularMangaSelector = "div.PopularPosts .group"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector).map { element ->
            SManga.create().apply {
                val thumbnailElement = element.selectFirst("a[style*='background-image']")
                thumbnail_url = thumbnailElement?.attr("style")?.let {
                    it.substringAfter("url(").substringBefore(")").trim('"', '\'')
                }
                title = element.selectFirst("h3")?.text() ?: ""
                setUrlWithoutDomain(element.selectFirst("a[href]")?.attr("href") ?: "")
            }
        }
        return MangasPage(mangas, false)
    }

    // Details
    override val mangaDetailsSelector = "#main"
    override val mangaDetailsSelectorDescription = "#syn_bod"
    override val mangaDetailsSelectorGenres = "a[rel=tag]"
    override val mangaDetailsSelectorAuthor = "span#tauther"
    override val mangaDetailsSelectorArtist = "span#tartist"
    override val mangaDetailsSelectorAltName = "span#talternative"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst(mangaDetailsSelectorDescription)?.text()
            genre = document.select(mangaDetailsSelectorGenres).joinToString { it.text() }
            author = document.selectFirst(mangaDetailsSelectorAuthor)?.text()
            artist = document.selectFirst(mangaDetailsSelectorArtist)?.text()

            val altName = document.selectFirst(mangaDetailsSelectorAltName)?.text()
            if (!altName.isNullOrBlank()) {
                description = (description ?: "") + "\n\nAlternative name(s): $altName"
            }

            val statusText = document.selectFirst(".capitalize")?.text() ?: ""
            status = parseStatus(statusText)

            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    // Chapters
    override fun getChapterFeedUrl(doc: Document): String {
        val label = doc.selectFirst(".chapter_get")?.attr("data-labelchapter")
            ?: throw Exception("Failed to find chapter label")

        return apiUrl(label)
            .addQueryParameter("max-results", MAX_CHAPTER_RESULTS.toString())
            .build().toString()
    }

    override val pageListSelector = ".post-body img"
}
