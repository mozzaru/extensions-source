package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    private fun extractStatus(infoContainer: Element?): Int {
        val statusString = getInfo(infoContainer, "Status")
            ?.lowercase(Locale.ENGLISH)
            ?: return SManga.UNKNOWN
    
        return when {
            "ongoing" in statusString -> SManga.ONGOING
            "completed" in statusString -> SManga.COMPLETED
            "hiatus" in statusString -> SManga.ON_HIATUS
            "dropped" in statusString -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    /**
     * Fungsi bantuan untuk mengambil informasi dari blok detail manga.
     * `key` adalah label yang dicari, contoh: "Type", "Released", "Author".
     */
    private fun getInfo(element: Element?, key: String): String? {
        if (element == null) return null
        return element.select("div.flex").firstOrNull { block ->
            block.select("h4 span").any { it.text().contains(key, ignoreCase = true) }
        }?.selectFirst("div.inline p, div.inline a")?.text()?.trim()
    }
}
