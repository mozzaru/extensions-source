package eu.kanade.tachiyomi.extension.id.inazumanga

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistMangaDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import okhttp3.Response
import org.jsoup.nodes.Document

class ReYume : ZeistManga("ReYume", "https://www.re-yume.my.id", "id") {

    override val mangaCategory: String = "Series"

    // Details
    override val mangaDetailsSelector = "#main"
    override val mangaDetailsSelectorDescription = "#syn_bod"
    override val mangaDetailsSelectorAuthor = "span#tauther"
    override val mangaDetailsSelectorArtist = "span#tartist"
    override val mangaDetailsSelectorAltName = "span#talternative"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst(mangaDetailsSelectorDescription)?.text()
            author = document.selectFirst(mangaDetailsSelectorAuthor)?.text()
            artist = document.selectFirst(mangaDetailsSelectorArtist)?.text()

            val script = document.select("script").joinToString("") { it.html() }
            val genreMatch = Regex("""filterGenre\s*=\s*\[(.*?)]""").find(script)
            genre = genreMatch?.groupValues?.get(1)
                ?.split(",")
                ?.map { it.trim().trim('\'', '"') }
                ?.filter { it.isNotBlank() }
                ?.joinToString()

            val altName = document.selectFirst(mangaDetailsSelectorAltName)?.text()
            if (!altName.isNullOrBlank()) {
                description = (description ?: "") + "\n\nAlternative name(s): $altName"
            }

            val statusText = document.selectFirst(".capitalize")?.text() ?: ""
            status = parseStatus(statusText)

            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val url = getChapterFeedUrl(document)
        val res = client.newCall(GET(url, headers)).execute()

        val result = json.decodeFromString<ZeistMangaDto>(res.body.string())
        return result.feed?.entry
            ?.filter { it.category.orEmpty().any { cat -> cat.term == chapterCategory } }
            ?.map {
                it.toSChapter(baseUrl).apply {
                    val match = Regex("""(?i)(chapter\s*[\d.]+.*)""").find(name)
                    if (match != null) {
                        name = match.groupValues[1].trim()
                            .replaceFirst(Regex("(?i)^chapter"), "Chapter")
                    }
                }
            }
            ?: throw Exception("Failed to parse from chapter API")
    }

    override fun getChapterFeedUrl(doc: Document): String {
        val label = doc.selectFirst(".chapter_get")?.attr("data-labelchapter")
            ?: throw Exception("Failed to find chapter label")

        return apiUrl(label)
            .addQueryParameter("max-results", MAX_CHAPTER_RESULTS.toString())
            .build().toString()
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div.separator a")
            .mapIndexed { i, a ->
                Page(i, "", a.attr("abs:href"))
            }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
