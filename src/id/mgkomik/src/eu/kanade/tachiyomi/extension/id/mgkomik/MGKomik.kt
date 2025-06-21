package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element

class MGKomik : MangaThemesia(
    name = "MGKomik",
    baseUrl = "https://id.mgkomik.cc",
    lang = "id",
    mangaUrlDirectory = "/komik"
) {

    override val hasProjectPage = false

    // ==== POPULER ====

    override fun popularMangaSelector() = "div.tab-summary"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        val link = element.selectFirst(".summary_image a")!!
        val img = link.selectFirst("img")!!
        val title = img.attr("alt").ifBlank {
            element.selectFirst(".rate-title")?.text()?.trim() ?: "No Title"
        }

        manga.title = title
        manga.setUrlWithoutDomain(link.attr("href"))
        manga.thumbnail_url = img.attr("src")

        return manga
    }

    override fun popularMangaNextPageSelector() = "a.nextpostslink"

    // ==== TERBARU (LATEST) ====

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ==== PENCARIAN ====

    override fun searchMangaSelector() = "div.tab-summary"

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
}