package eu.kanade.tachiyomi.extension.id.mangatale

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

    fun extractThumbnailUrl(element: Element): String {
        return element.select("img").firstNotNullOfOrNull { img: Element ->
            img.absUrl("src").ifBlank {
                img.absUrl("data-src").ifBlank {
                    img.absUrl("data-original")
                }
            }
        }?.trim().orEmpty()
    }

    fun isValidMangaUrl(url: String): Boolean {
        return url.isNotBlank() && url.contains("/manga/") && !url.contains("javascript:")
    }
    
    fun extractRating(element: Element): String {
        return element.selectFirst(".numscore")?.text()?.trim() ?: "N/A"
    }
    
    fun extractTypeWithIcon(element: Element): String {
        val typeImg = element.selectFirst("span img[alt]")?.attr("alt")
        return when (typeImg?.lowercase()) {
            "manga" -> "🇯🇵 Manga"
            "manhwa" -> "🇰🇷 Manhwa"
            "manhua" -> "🇨🇳 Manhua"
            else -> "📖 Unknown"
        }
    }
    
    fun formatRelativeTime(timeStr: String): String {
        return when {
            timeStr.contains("minute") -> timeStr.replace("minutes ago", "menit lalu")
            timeStr.contains("hour") -> timeStr.replace("hours ago", "jam lalu")
            timeStr.contains("day") -> timeStr.replace("days ago", "hari lalu")
            timeStr.contains("week") -> timeStr.replace("weeks ago", "minggu lalu")
            timeStr.contains("month") -> timeStr.replace("months ago", "bulan lalu")
            else -> timeStr
        }
    }
}
