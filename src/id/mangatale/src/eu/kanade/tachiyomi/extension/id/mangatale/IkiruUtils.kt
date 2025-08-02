package eu.kanade.tachiyomi.extension.id.mangatale

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object IkiruUtils {

    fun findMangaId(document: Document): String? {
        // First: Try to find from data attributes
        document.selectFirst("#manga-data[data-manga-id]")?.attr("data-manga-id")?.let { 
            return it 
        }

        // Second: Try to extract from URL
        document.baseUri()?.let { url ->
            extractMangaIdFromUrl(url)?.let { return it }
        }

        // Third: Search in scripts
        document.select("script").forEach { script ->
            Regex("""manga_id\s*[:=]\s*['"]?(\d+)""").find(script.data())?.let {
                return it.groupValues[1]
            }
        }

        // Fourth: Search in ajax-call URLs
        document.select("[hx-get*='manga_id']").forEach { element ->
            Regex("""manga_id=(\d+)""").find(element.attr("hx-get"))?.let {
                return it.groupValues[1]
            }
        }

        return null
    }

    fun findChapterId(document: Document): String? {
        // First: Try to find from data attributes
        document.selectFirst("[data-chapter-id]")?.attr("data-chapter-id")?.let { 
            return it 
        }

        // Second: Try to extract from URL
        document.baseUri()?.let { url ->
            extractChapterIdFromUrl(url)?.let { return it }
        }

        // Third: Search in scripts
        document.select("script").forEach { script ->
            Regex("""chapter_id\s*[:=]\s*['"]?(\d+)""").find(script.data())?.let {
                return it.groupValues[1]
            }
        }

        return null
    }

    fun extractChapterIdFromUrl(url: String): String? {
        // Regex format:
        // - chapter-1.686558 → 686558
        // - chapter-220.758389 → 758389
        // - chapter-502.5.758446 → 758446
        return Regex("""/chapter-[^/]+\.(\d{6,})/?$""")
            .find(url)?.groups?.get(1)?.value
    }

    fun extractMangaIdFromUrl(url: String): String? {
        // Handles manga URLs with or without trailing ID:
        // /manga/the-man/ → returns null (ID not in URL)
        // /manga/the-man/758389/ → returns "758389"
        return Regex("""/manga/[^/]+/(?:chapter-[\d.]+\.)?(\d+)/?$""")
            .find(url)?.groupValues?.get(1)
    }

    fun checkCloudflareBlock(response: String): Boolean {
        return response.contains("Just a moment") || 
               response.contains("Cloudflare") || 
               response.contains("cf-browser-verification") ||
               response.contains("DDoS protection by Cloudflare") ||
               response.contains("Checking your browser before accessing")
    }

    fun sanitizeTitle(title: String): String {
        return title.trim()
            .replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^[^a-zA-Z0-9]+"""), "") // Remove leading special chars
            .ifBlank { "Tanpa Judul" }
    }

    fun extractThumbnailUrl(element: Element): String {
        return element.selectFirst("img")?.let { img ->
            img.absUrl("src").ifBlank { 
                img.absUrl("data-src").ifBlank { 
                    img.absUrl("data-original") 
                } 
            }
        } ?: ""
    }

    fun isValidMangaUrl(url: String): Boolean {
        return url.startsWith("/manga/") &&
            !url.contains("javascript:") &&
            url.matches(Regex("""^/manga/[^/]+(/[\w.-]+)?/?$"""))
    }

    fun convertDateToRelative(date: Date): String {
        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        val inputCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        inputCal.time = date

        val diffMillis = now.timeInMillis - inputCal.timeInMillis
        val days = TimeUnit.MILLISECONDS.toDays(diffMillis)

        return when {
            days < 1 -> "hari ini"
            days < 7 -> "${days} hari yang lalu"
            else -> SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).format(date)
        }
    }

    fun parseDateStringToDate(text: String): Date? {
        return try {
            SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).parse(text)
        } catch (e: Exception) {
            null
        }
    }
}
