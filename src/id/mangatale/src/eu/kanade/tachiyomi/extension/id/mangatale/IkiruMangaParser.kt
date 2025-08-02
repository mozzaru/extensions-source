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

    private fun extractAuthor(document: Document): String {
        // Cari blok dengan <h4> atau <h5> yang mengandung kata 'Author'
        val authorBlock = document.selectFirst(
            "div:has(h4:contains(Author)), div:has(h5:contains(Author))"
        ) ?: return "Unknown"
        // Ambil semua teks <a> atau <p> di dalamnya
        val names = authorBlock.select("a, p").map { it.text().trim() }
            .filter { it.isNotEmpty() }
        return if (names.isNotEmpty()) names.joinToString(", ") else "Unknown"
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
        val rawStatus = document.selectFirst(
            "div:has(h4:matches(Status)), div:has(h5:matches(Status))"
        )
        ?.selectFirst("div, p, span")
        ?.text()?.trim()?.lowercase(Locale.ROOT)
        ?: ""
        return when {
            rawStatus.contains("ongoing") || rawStatus.contains("berlanjut") ->
                SManga.ONGOING
            rawStatus.contains("completed") || rawStatus.contains("selesai") || rawStatus.contains("tamat") ->
                SManga.COMPLETED
            rawStatus.contains("hiatus") ->
                SManga.ON_HIATUS
            else ->
                SManga.UNKNOWN
        }
    }
}
