package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.util.Locale

class IkiruMangaParser {

    fun parseMangaDetails(document: Document): SManga {
        return SManga.create().apply {
            title = IkiruUtils.sanitizeTitle(
                document.selectFirst("h1[itemprop=name]")?.text().orEmpty()
            )
            thumbnail_url = document.selectFirst("div[itemprop=image] img")?.absUrl("src") ?: ""
    
            description = buildDescription(document)
            author = extractAuthor(document)
            genre = extractGenres(document)
            status = extractStatus(document)
    
            // Normalisasi thumbnail_url
            thumbnail_url = if (thumbnail_url.isNullOrBlank()) null else thumbnail_url
        }
    }

    private fun buildDescription(document: Document): String {
        val altTitle = document.selectFirst("div.block.text-sm.text-text.line-clamp-1")
            ?.text()?.trim()

        val desc = document.select("div[itemprop=description][data-show=false]")
            .joinToString("\n") { it.text().trim() }
            .ifBlank {
                document.select("div[itemprop=description]")
                    .joinToString("\n") { it.text().trim() }
            }.ifBlank { "Tidak ada deskripsi." }

        return buildString {
            append(desc)
            if (!altTitle.isNullOrEmpty()) {
                append("\n\nNama Alternatif: $altTitle")
            }
        }
    }

    private fun extractAuthor(document: Document): String {
        val label = document.select("div").firstOrNull {
            it.ownText().trim().equals("author", ignoreCase = true) ||
            it.ownText().trim().equals("penulis", ignoreCase = true)
        }?.parent() ?: return "Unknown"
            
        val authorText = label.selectFirst("p, span, a")?.text()?.trim().orEmpty()
        return if (authorText.isNotEmpty()) authorText else "Unknown"
    }

    private fun extractGenres(document: Document): String {
        val genres = document.select("a[href*='/genre/']")
            .map { it.text().trim() }
            .toMutableList()

        // Add type (manhwa/manhua/manga) to genres
        document.selectFirst("div:has(h4:contains(Type)) > div > p")
            ?.text()?.trim()?.let { type ->
                if (type.isNotEmpty() && !genres.contains(type)) {
                    genres.add(0, type)
                }
            }

        return genres.joinToString()
    }

    private fun extractStatus(document: Document): Int {
        val label = document.selectFirst("div:matchesOwn(?i)^status$")?.parent()
            ?: document.selectFirst("div:matchesOwn(?i)^status manga$")?.parent()
            ?: return SManga.UNKNOWN
    
        val statusText = label.selectFirst("p, span, div")?.text()?.trim()?.lowercase(Locale.ROOT).orEmpty()
    
        return when {
            statusText.contains("ongoing") || statusText.contains("berlanjut") -> SManga.ONGOING
            statusText.contains("completed") || statusText.contains("selesai") || statusText.contains("tamat") -> SManga.COMPLETED
            statusText.contains("hiatus") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
}
