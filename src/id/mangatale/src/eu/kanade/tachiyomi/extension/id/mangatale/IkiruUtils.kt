package eu.kanade.tachiyomi.extension.id.mangatale

import org.jsoup.nodes.Document

object IkiruUtils {
    
    fun findMangaId(document: Document): String? {
        Regex("""manga_id=(\d+)""").find(document.html())?.let { return it.groupValues[1] }
        document.select("[hx-get],[data-manga-id],[onclick*='manga_id']").forEach { el ->
            Regex("""manga_id[:=](\d+)""")
                .find(el.attr("hx-get") + el.attr("data-manga-id") + el.attr("onclick"))
                ?.let { return it.groupValues[1] }
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
    
    fun findChapterId(document: Document): String {
        Regex("""chapter_id=(\d+)""").find(document.html())?.let { return it.groupValues[1] }
        document.select("a[href*=/chapter-],[data-chapter-id],[onclick*='chapter_id']").forEach { el ->
            Regex("""chapter_id[:=](\d+)""")
                .find(el.attr("href") + el.attr("data-chapter-id") + el.attr("onclick"))
                ?.let { return it.groupValues[1] }
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
        
        val script = document.selectFirst("script:containsData(chapter_id)")?.data()
        Regex("""chapter_id\s*[:=]\s*['"]?(\d+)['"]?""").find(script ?: "")?.let {
            return it.groupValues[1]
        }
        
        return "0"
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
        return url.isNotBlank() && 
               url.contains("/manga/") && 
               !url.contains("javascript:") &&
               !url.contains("data:") &&
               !url.contains("file:") &&
               url.startsWith("http")
    }
    
    private fun validateMangaUrl(url: String): String {
        require(url.isNotBlank()) { "Manga URL cannot be blank" }
        require(url.startsWith("/manga/")) { "Invalid manga URL format" }
        return url
    }
}
