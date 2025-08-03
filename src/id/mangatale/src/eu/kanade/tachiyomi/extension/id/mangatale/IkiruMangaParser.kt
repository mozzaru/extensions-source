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
        // Cari elemen dengan label "author" atau "penulis"
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
        // JSoup tidak mendukung regex selector seperti :matchesOwn(?i)
        // Gunakan pendekatan yang lebih sederhana dan reliable
        
        // Method 1: Cari berdasarkan text content yang mengandung "status"
        val statusElement = document.select("div").firstOrNull { element ->
            val text = element.ownText().trim().lowercase(Locale.ROOT)
            text == "status" || text == "status manga" || text.contains("status")
        }?.parent()
        
        if (statusElement != null) {
            val statusText = statusElement.selectFirst("p, span, div")?.text()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return parseStatusText(statusText)
        }
        
        // Method 2: Cari berdasarkan class atau struktur HTML yang umum
        val statusFromClass = document.selectFirst(".status, .manga-status")?.text()?.trim()?.lowercase(Locale.ROOT)
        if (!statusFromClass.isNullOrEmpty()) {
            return parseStatusText(statusFromClass)
        }
        
        // Method 3: Cari dalam metadata atau structured data
        val statusFromMeta = document.selectFirst("meta[property*='status'], meta[name*='status']")
            ?.attr("content")?.trim()?.lowercase(Locale.ROOT)
        if (!statusFromMeta.isNullOrEmpty()) {
            return parseStatusText(statusFromMeta)
        }
        
        // Method 4: Cari berdasarkan label-value pair pattern
        document.select("div").forEach { div ->
            val label = div.selectFirst("h4, h5, strong, label")?.text()?.trim()?.lowercase(Locale.ROOT)
            if (label == "status" || label == "status manga") {
                val statusText = div.selectFirst("p, span, div:not(:has(h4,h5,strong,label))")
                    ?.text()?.trim()?.lowercase(Locale.ROOT)
                if (!statusText.isNullOrEmpty()) {
                    return parseStatusText(statusText)
                }
            }
        }
        
        // Method 5: Fallback - cari berdasarkan pattern umum di website manga
        val allText = document.text().lowercase(Locale.ROOT)
        return when {
            allText.contains("status: ongoing") || allText.contains("status:ongoing") -> SManga.ONGOING
            allText.contains("status: completed") || allText.contains("status:completed") -> SManga.COMPLETED
            allText.contains("status: hiatus") || allText.contains("status:hiatus") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
    
    private fun parseStatusText(statusText: String): Int {
        return when {
            statusText.contains("ongoing") || 
            statusText.contains("berlanjut") || 
            statusText.contains("continuing") ||
            statusText.contains("publishing") -> SManga.ONGOING
            
            statusText.contains("completed") || 
            statusText.contains("selesai") || 
            statusText.contains("tamat") ||
            statusText.contains("finished") ||
            statusText.contains("end") -> SManga.COMPLETED
            
            statusText.contains("hiatus") ||
            statusText.contains("pause") ||
            statusText.contains("discontinued") -> SManga.ON_HIATUS
            
            else -> SManga.UNKNOWN
        }
    }
}
