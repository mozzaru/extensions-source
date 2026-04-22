package eu.kanade.tachiyomi.extension.id.inazumanga

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document

class ReYume : ZeistManga("ReYume", "https://www.re-yume.my.id", "id") {

    override val popularMangaSelector = "#Side .group"
    override val popularMangaSelectorTitle = "h3"
    override val popularMangaSelectorUrl = "a:has(h3)"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector).map { element ->
            SManga.create().apply {
                val thumbnailElement = element.selectFirst("a[style*=background-image]")
                thumbnail_url = thumbnailElement?.let {
                    val style = it.attr("style")
                    val url = style.substringAfter("url(").substringBefore(")").trim('"', '\'')
                    if (url.startsWith("//")) "https:$url" else url
                } ?: element.selectFirst("img")?.attr("abs:src")

                title = element.selectFirst(popularMangaSelectorTitle)?.text() ?: ""
                element.selectFirst(popularMangaSelectorUrl)?.attr("href")?.let {
                    setUrlWithoutDomain(it)
                }
            }
        }
        return MangasPage(mangas, false)
    }

    override val mangaDetailsSelector = "#main"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("#post-title")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("#syn_bod")?.text()

            val excludedGenres = listOf("Series", "Ongoing", "Completed", "Manga", "Manhua", "Manhwa", "Project")
            genre = document.select("a[rel=tag]")
                .map { it.text() }
                .filterNot { g ->
                    excludedGenres.any { it.equals(g, true) } || g.equals(title, true) || g.toDoubleOrNull() != null
                }
                .distinct()
                .joinToString { it }

            author = document.selectFirst("#tauthers, #tauther")?.text()
            artist = document.selectFirst("#tartists, #tartist")?.text()

            val altName = document.selectFirst("#talternatives, #talternative")?.text()
            if (!altName.isNullOrBlank()) {
                description = "Alternative: $altName\n\n$description"
            }

            val statusText = document.select(".capitalize").firstOrNull {
                val text = it.text().lowercase()
                statusOnGoingList.contains(text) || statusCompletedList.contains(text)
            }?.text()
            status = parseStatus(statusText ?: "")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = super.chapterListParse(response)
        val mangaTitle = response.asJsoup().selectFirst("#post-title")?.text() ?: ""
        if (mangaTitle.isBlank()) return chapters

        return chapters.map { chapter ->
            chapter.apply {
                if (name.startsWith(mangaTitle, ignoreCase = true)) {
                    name = name.substring(mangaTitle.length).trim()
                }
            }
        }
    }

    override fun getChapterFeedUrl(doc: Document): String {
        val label = doc.selectFirst(".chapter_get")?.attr("data-labelchapter")
        if (label != null) {
            return apiUrl("Chapter")
                .addPathSegment(label)
                .build().toString()
        }
        return super.getChapterFeedUrl(doc)
    }

    override val pageListSelector = ".i_img img"
}
