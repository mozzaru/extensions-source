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
            author = extractAuthor(document)
            genre = extractGenres(document)
            status = extractStatus(document)
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

    fun extractAuthor(document: Document): String {
        return document.selectFirst("div:has(h4:contains(Author)) > div > p")
            ?.text()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("[itemprop=author]")?.text()
            ?: "Tidak diketahui"
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

    fun extractStatus(document: Document): Int {
        val statusElement = document.selectFirst("""
            div:contains(Status):has(+ div),
            div:contains(Status) + div,
            [itemprop=status]
        """.trimIndent())

        val rawStatus = statusElement?.text()?.trim()?.lowercase(Locale.ROOT).orEmpty()

        return when {
            rawStatus.contains("ongoing") || rawStatus.contains("berlanjut") -> SManga.ONGOING
            rawStatus.contains("completed") || rawStatus.contains("tamat") || rawStatus.contains("selesai") -> SManga.COMPLETED
            rawStatus.contains("hiatus") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
}
