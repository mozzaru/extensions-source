package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import java.util.Locale

class IkiruMangaParser {

    private fun parseJsonMetadata(document: Document): SManga? {
        val jsonElement = document.selectFirst("script[type=application/ld+json]")?.data()
            ?: return null

        return try {
            val json = Json.parseToJsonElement(jsonElement).jsonObject
            SManga.create().apply {
                title = json["name"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = json["image"]?.jsonPrimitive?.content ?: ""
                author = json["author"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Tidak diketahui"
                description = json["description"]?.jsonPrimitive?.content ?: ""
                genre = json["genre"]?.jsonPrimitive?.content ?: ""
                status = parseStatus(json["status"]?.jsonPrimitive?.content ?: "")
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseMangaDetails(document: Document): SManga {

        val jsonLd = parseJsonMetadata(document)
        if (jsonLd != null) return jsonLd

        parseJsonMetadata(document)?.let { 
            return it 
        }

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

    private fun extractStatus(document: Document): Int {
        val rawStatus = document.selectFirst("div:has(> h4:contains(Status))")?.selectFirst("div, p, span")
            ?.text()?.trim()?.lowercase(Locale.ROOT)
            ?: document.selectFirst("div.detail-info:has(span.label:contains(Status)) span.value")
                ?.text()?.trim()?.lowercase(Locale.ROOT)
            ?: document.selectFirst("[itemprop=status]")?.text()?.lowercase(Locale.ROOT)
            ?: ""

        return when {
            rawStatus.contains("ongoing") || 
            rawStatus.contains("berlanjut") || 
            rawStatus.contains("berjalan") || 
            rawStatus.contains("lanjut") -> SManga.ONGOING

            rawStatus.contains("completed") || 
            rawStatus.contains("selesai") || 
            rawStatus.contains("tamat") || 
            rawStatus.contains("complete") -> SManga.COMPLETED

            rawStatus.contains("hiatus") || 
            rawStatus.contains("jeda") -> SManga.ON_HIATUS

            rawStatus.contains("cancel") || 
            rawStatus.contains("batal") || 
            rawStatus.contains("dihentikan") -> SManga.CANCELLED

            else -> SManga.UNKNOWN
        }
    }

    private fun parseStatus(rawStatus: String): Int {
        return when {
            rawStatus.contains("Ongoing", true) -> SManga.ONGOING
            rawStatus.contains("Completed", true) -> SManga.COMPLETED
            rawStatus.contains("Hiatus", true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
}
