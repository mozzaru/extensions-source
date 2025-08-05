package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.util.Locale

class IkiruMangaParser {

    fun parseMangaDetails(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1[itemprop=name]")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("div[itemprop=image] img")?.absUrl("src") ?: ""
            description = buildDescription(document)

            // Menggunakan fungsi bantuan untuk mengambil info
            val infoContainer = document.selectFirst("div.space-y-2")

            author = getInfo(infoContainer, "Author") ?: "Tidak diketahui"
            genre = extractGenres(document, infoContainer)
            status = extractStatus(infoContainer)
        }
    }

    private fun buildDescription(document: Document): String {
        val altTitle = getInfo(document.selectFirst("div.flex.w-full.flex-col"), "Alternative")
            ?: document.selectFirst("div.block.text-sm.text-text.line-clamp-1")?.text()?.trim()

        val desc = document.select("div[itemprop=description]")
            .firstOrNull()?.text()?.trim()
            ?: "Tidak ada deskripsi."

        return buildString {
            append(desc)
            if (!altTitle.isNullOrEmpty()) {
                append("\n\nNama Alternatif: $altTitle")
            }
        }
    }

    private fun extractGenres(document: Document, infoContainer: org.jsoup.nodes.Element?): String {
        val genres = document.select("a[href*='/genre/']")
            .map { it.text().trim() }
            .toMutableList()

        // Menambahkan 'Type' (Manhwa/Manhua/Manga) ke dalam daftar genre
        getInfo(infoContainer, "Type")?.let { type ->
            if (type.isNotEmpty() && !genres.contains(type)) {
                genres.add(0, type)
            }
        }

        return genres.joinToString()
    }

    private fun extractStatus(infoContainer: org.jsoup.nodes.Element?): Int {
        // Mencari status di dua tempat yang memungkinkan
        val statusString = getInfo(infoContainer, "Status")
            ?: infoContainer?.parent()?.select("small:contains(Favorites)")?.firstOrNull()
                ?.parent()?.parent()?.select("span.font-bold")?.lastOrNull()?.text()
            ?: ""

        return when {
            statusString.equals("Ongoing", true) -> SManga.ONGOING
            statusString.equals("Completed", true) -> SManga.COMPLETED
            statusString.equals("Hiatus", true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    /**
     * Fungsi bantuan untuk mengambil informasi dari blok detail manga.
     * `key` adalah label yang dicari, contoh: "Type", "Released", "Author".
     */
    private fun getInfo(element: org.jsoup.nodes.Element?, key: String): String? {
        if (element == null) return null
        return element.select("div.flex:has(h4 > span:contains($key))")
            .firstOrNull()
            ?.select("div.inline p, a") // Bisa berupa <p> atau <a>
            ?.text()?.trim()
    }
}
