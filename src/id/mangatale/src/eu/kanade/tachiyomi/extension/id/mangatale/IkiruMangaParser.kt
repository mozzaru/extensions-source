// Di file IkiruMangaParser.kt, ganti seluruh isinya dengan ini
package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

class IkiruMangaParser {

    fun parseMangaDetails(document: Document): SManga {
        return SManga.create().apply {
            title = IkiruUtils.sanitizeTitle(
                document.selectFirst("h1.text-2xl, h1[itemprop=name], .post-title")?.text().orEmpty()
            )
            thumbnail_url = document.selectFirst("div.summary_image img, div[itemprop=image] img")?.absUrl("src")

            description = buildDescription(document)
            author = extractDetail(document, setOf("author", "penulis"))
            artist = extractDetail(document, setOf("artist", "artis"))
            genre = extractGenres(document)
            status = extractStatus(document)
        }
    }

    private fun buildDescription(document: Document): String {
        val stringBuilder = StringBuilder()
        
        val synopsis = document.select("div[itemprop=description], .description-summary, .summary__content").firstOrNull()?.text()?.trim()
        if (!synopsis.isNullOrBlank()) {
            stringBuilder.append(synopsis)
        } else {
            stringBuilder.append("Tidak ada deskripsi.")
        }
        
        val altTitle = extractDetail(document, setOf("alternative", "judul alternatif"))
        if (altTitle != "Unknown") {
            stringBuilder.append("\n\nNama Alternatif: $altTitle")
        }
        
        return stringBuilder.toString()
    }

    private fun extractDetail(document: Document, keys: Set<String>): String {
        document.select(".tsinfo .imptdt, .infox .fmed, .spe span").forEach { el ->
            val labelElement = el.select("h4, h5, b, strong").first()
            if (labelElement != null && keys.contains(labelElement.text().trim().lowercase(Locale.ROOT))) {
                return el.select("span, a, p").first()?.text()?.trim() ?: "Unknown"
            }
        }
        document.select(".post-content_item").forEach { el ->
            val labelElement = el.select(".summary-heading h5").first()
            if (labelElement != null && keys.contains(labelElement.text().trim().lowercase(Locale.ROOT))) {
                return el.select(".summary-content").first()?.text()?.trim() ?: "Unknown"
            }
        }
        return "Unknown"
    }

    private fun extractGenres(document: Document): String {
        val genres = document.select("a[href*='/genre/'], .mgen a, .genres-container a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
            
        // Tambahkan tipe (Manga/Manhwa/Manhua) sebagai genre
        val type = extractDetail(document, setOf("type", "tipe"))
        if (type != "Unknown") {
            genres.add(0, type)
        }
        
        return genres.joinToString()
    }

    private fun extractStatus(document: Document): Int {
        val statusText = extractDetail(document, setOf("status")).lowercase(Locale.ROOT)
        return when {
            statusText.contains("ongoing") || statusText.contains("berlanjut") -> SManga.ONGOING
            statusText.contains("completed") || statusText.contains("selesai") || statusText.contains("tamat") -> SManga.COMPLETED
            statusText.contains("hiatus") || statusText.contains("on hold") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
}