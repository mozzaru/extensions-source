package eu.kanade.tachiyomi.extension.id.mangatale

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.time.Instant
import java.time.format.DateTimeFormatter

object IkiruUtils {

    fun findMangaId(document: Document): String? {
        val body = document.html()

        // Try to find manga_id from ajax-call URL
        Regex("""manga_id=(\d+)""").find(body)?.let { 
            return it.groupValues[1] 
        }

        // Fallback: search in hx-get attributes
        document.select("[hx-get]").forEach { element ->
            Regex("""manga_id=(\d+)""").find(element.attr("hx-get"))?.let { match ->
                return match.groupValues[1]
            }
        }

        // Fallback: search in data attributes
        document.select("[data-manga-id]").firstOrNull()?.let { element ->
            return element.attr("data-manga-id")
        }

        // Fallback: search in onclick attributes
        document.select("[onclick*='manga_id']").forEach { element ->
            Regex("""manga_id[=:](\d+)""").find(element.attr("onclick"))?.let { match ->
                return match.groupValues[1]
            }
        }
        return null
    }

    fun findChapterId(document: Document): String? {
        val body = document.html()

        // Try to find chapter_id from ajax-call URL
        Regex("""chapter_id=(\d+)""").find(body)?.let { 
            return it.groupValues[1] 
        }

        // Fallback: extract from first chapter href
        document.select("a[href*=/chapter-]").firstOrNull()?.let { element ->
            val href = element.attr("href")
            Regex("""chapter-[\d.]+\.(\d+)""").find(href)?.let { match ->
                return match.groupValues[1]
            }
        }

        // Fallback: search in data attributes
        document.select("[data-chapter-id]").firstOrNull()?.let { element ->
            return element.attr("data-chapter-id")
        }

        // Fallback: search in onclick attributes
        document.select("[onclick*='chapter_id']").forEach { element ->
            Regex("""chapter_id[=:](\d+)""").find(element.attr("onclick"))?.let { match ->
                return match.groupValues[1]
            }
        }
        return null
    }

    fun checkCloudflareBlock(response: String): Boolean {
        return response.contains("Just a moment") || 
               response.contains("Cloudflare") || 
               response.contains("cf-browser-verification") ||
               response.contains("DDoS protection by Cloudflare")
    }

    fun sanitizeTitle(title: String): String {
        return title.trim()
            .replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .ifBlank { "Tanpa Judul" }
    }

    fun extractThumbnailUrl(element: org.jsoup.nodes.Element): String {
        return element.selectFirst("img.wp-post-image, img")
            ?.let { img ->
                img.absUrl("src").ifBlank { 
                    img.absUrl("data-src").ifBlank { 
                        img.absUrl("data-original") 
                    } 
                }
            } ?: ""
    }

    fun isValidMangaUrl(url: String): Boolean {
        return url.isNotBlank() && url.contains("/manga/") && !url.contains("javascript:")
    }

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }
    
    fun parseIsoDate(isoString: String): Long {
        return try {
            isoDateFormat.parse(isoString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
