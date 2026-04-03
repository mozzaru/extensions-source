package eu.kanade.tachiyomi.extension.id.inazumanga

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistMangaDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class ReYume : ZeistManga("ReYume", "https://www.re-yume.my.id", "id") {

    // ===== POPULAR =====
    // Feeds API label Manga — semua series, 20/page, ada next page
    override fun popularMangaRequest(page: Int): Request {
        val startIndex = MAX_MANGA_RESULTS * (page - 1) + 1
        val url = apiUrl(mangaCategory)
            .addQueryParameter("max-results", (MAX_MANGA_RESULTS + 1).toString())
            .addQueryParameter("start-index", startIndex.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<ZeistMangaDto>(response.body.string())
        val entries = result.feed?.entry.orEmpty()
            .filter { it.category.orEmpty().any { cat -> cat.term == mangaCategory } }

        val mangas = entries.take(MAX_MANGA_RESULTS).map { it.toSManga(baseUrl) }
        return MangasPage(mangas, entries.size > MAX_MANGA_RESULTS)
    }

    override val mangaCategory: String = "Manga"

    // ===== LATEST =====
    // Feeds API label Chapter — deduplicate by manga URL
    override fun latestUpdatesRequest(page: Int): Request {
        val startIndex = MAX_LATEST_RESULTS * (page - 1) + 1
        val url = apiUrl(chapterCategory)
            .addQueryParameter("orderby", "published")
            .addQueryParameter("max-results", (MAX_LATEST_RESULTS + 1).toString())
            .addQueryParameter("start-index", startIndex.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = json.decodeFromString<ZeistMangaDto>(response.body.string())
        val entries = result.feed?.entry.orEmpty()
            .filter { it.category.orEmpty().any { cat -> cat.term == chapterCategory } }

        // Deduplicate: ambil manga unik dari chapter entries
        // Chapter URL: /2024/01/judul-chapter-12.html
        // Manga URL:   /2024/01/judul-manga.html
        // Kita pakai title tanpa "Chapter X" sebagai key dedup
        val seen = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (entry in entries.take(MAX_LATEST_RESULTS)) {
            // Strip "Chapter X" dari title untuk dapat nama manga
            val rawTitle = entry.title?.t ?: continue
            val mangaTitle = rawTitle
                .replace(Regex("""(?i)\s*chapter\s*[\d.]+.*"""), "")
                .trim()

            if (mangaTitle.isEmpty()) continue
            if (!seen.add(mangaTitle)) continue // skip duplikat

            // Cari entry series yang cocok untuk dapat URL & thumbnail manga
            // Fallback: pakai URL chapter sebagai URL manga (tidak ideal tapi tidak crash)
            mangas.add(
                SManga.create().apply {
                    title = mangaTitle
                    // URL chapter dipakai sementara — detail page akan redirect ke manga
                    setUrlWithoutDomain(
                        entry.url?.firstOrNull { it.rel == "alternate" }?.href ?: continue,
                    )
                    thumbnail_url = entry.thumbnail?.url
                        ?: entry.content?.t?.let { html ->
                            org.jsoup.Jsoup.parse(html).selectFirst("img")?.attr("src")
                        }
                },
            )
        }

        val hasNextPage = entries.size > MAX_LATEST_RESULTS
        return MangasPage(mangas, hasNextPage)
    }

    // ===== DETAILS =====
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

    // ===== CHAPTERS =====
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val url = getChapterFeedUrl(document)
        val res = client.newCall(GET(url, headers)).execute()

        val result = json.decodeFromString<ZeistMangaDto>(res.body.string())
        return result.feed?.entry
            ?.filter { it.category.orEmpty().any { cat -> cat.term == chapterCategory } }
            ?.map {
                it.toSChapter(baseUrl).apply {
                    // "Judul Manga Chapter 12" → "Chapter 12"
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

    override val pageListSelector = ".post-body img"

    companion object {
        private const val MAX_MANGA_RESULTS = 20
        private const val MAX_LATEST_RESULTS = 40 // lebih banyak agar setelah dedup tetap 20+
    }
}
