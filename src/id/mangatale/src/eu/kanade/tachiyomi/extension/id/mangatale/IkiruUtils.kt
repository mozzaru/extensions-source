package eu.kanade.tachiyomi.extension.id.mangatale

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object IkiruUtils {

    fun findMangaId(document: Document): String? {
        val body = document.html()

        // Method 1: Try to find manga_id from ajax-call URL
        Regex("""manga_id[=:](\d+)""").find(body)?.let { 
            return it.groupValues[1] 
        }

        // Method 2: Search in hx-get attributes (HTMX)
        document.select("[hx-get]").forEach { element ->
            Regex("""manga_id[=:](\d+)""").find(element.attr("hx-get"))?.let { match ->
                return match.groupValues[1]
            }
        }

        // Method 3: Search in data attributes
        document.select("[data-manga-id]").firstOrNull()?.let { element ->
            element.attr("data-manga-id").takeIf { it.isNotBlank() }?.let { return it }
        }

        // Method 4: Search in onclick attributes
        document.select("[onclick*='manga_id']").forEach { element ->
            Regex("""manga_id[=:](\d+)""").find(element.attr("onclick"))?.let { match ->
                return match.groupValues[1]
            }
        }

        // Method 5: Search in form inputs
        document.select("input[name='manga_id'], input[id='manga_id']").firstOrNull()?.let { input ->
            input.attr("value").takeIf { it.isNotBlank() }?.let { return it }
        }

        // Method 6: Extract from URL pattern
        document.select("a[href*='/manga/']").firstOrNull()?.let { link ->
            val href = link.attr("href")
            Regex("""/manga/[^/]+/?(\d+)""").find(href)?.let { match ->
                return match.groupValues[1]
            }
        }

        // Method 7: Search in meta tags
        document.select("meta[name*='manga'], meta[property*='manga']").forEach { meta ->
            Regex("""(\d+)""").find(meta.attr("content"))?.let { match ->
                return match.groupValues[1]
            }
        }

        return null
    }

    fun findChapterId(document: Document): String? {
        // Method 1: From data attributes
        document.selectFirst("[data-chapter-id]")?.attr("data-chapter-id")?.takeIf { it.isNotBlank() }?.let {
            return it
        }

        // Method 2: From script variables
        val scriptData = document.select("script").find { 
            it.html().contains("chapter_id") 
        }?.html()

        scriptData?.let { script ->
            Regex("""chapter_id\s*[:=]\s*['"]?(\d+)""").find(script)?.let {
                return it.groupValues[1]
            }
        }

        // Method 3: From first chapter link
        document.selectFirst("a[href*='/chapter-']")?.attr("href")?.let { href ->
            // Pattern: /manga/title/chapter-123.456789
            Regex("""chapter-[\d.]+\.(\d+)""").find(href)?.let {
                return it.groupValues[1]
            }
            // Alternative pattern: /chapter-123/456789
            Regex("""/chapter-\d+/(\d+)""").find(href)?.let {
                return it.groupValues[1]
            }
        }

        // Method 4: From current URL if this is a chapter page
        val currentUrl = document.location()
        if (currentUrl.contains("/chapter-")) {
            Regex("""chapter-[\d.]+\.(\d+)""").find(currentUrl)?.let {
                return it.groupValues[1]
            }
        }

        // Method 5: Default fallback
        return "1"
    }

    fun checkCloudflareBlock(response: String): Boolean {
        return response.contains("Just a moment", ignoreCase = true) || 
               response.contains("Cloudflare", ignoreCase = true) || 
               response.contains("cf-browser-verification") ||
               response.contains("DDoS protection by Cloudflare") ||
               response.contains("checking your browser", ignoreCase = true) ||
               response.contains("ray id", ignoreCase = true) ||
               response.length < 500 // Very short responses might indicate blocking
    }

    fun extractThumbnailUrl(element: Element): String {
        // Try multiple image selectors in order of preference
        val selectors = listOf(
            "img.wp-post-image",
            "img.thumbnail", 
            "img.object-cover",
            "img[src*='/covers/']",
            "img[src*='/uploads/']",
            "img.manga-cover",
            "img[class*='cover']",
            "img[class*='thumb']",
            "img[alt*='cover']",
            "img", // Fallback to any img
        )

        for (selector in selectors) {
            element.selectFirst(selector)?.let { img ->
                val url = extractImageSrc(img)
                if (url.isNotBlank() && isValidImageUrl(url)) {
                    return url
                }
            }
        }

        return ""
    }

    private fun extractImageSrc(img: Element): String {
        return listOf("src", "data-src", "data-original", "data-lazy-src", "data-srcset")
            .firstNotNullOfOrNull { attr ->
                img.absUrl(attr).takeIf { it.isNotBlank() }
            } ?: ""
    }

    private fun isValidImageUrl(url: String): Boolean {
        return url.startsWith("http") && 
               (url.contains(".jpg") || url.contains(".png") || url.contains(".webp") || 
                url.contains(".jpeg") || url.contains(".gif") || url.contains("imagedelivery"))
    }

    fun sanitizeTitle(title: String): String {
        return title.trim()
            .replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$"""), "") // Unicode-aware cleanup
            .ifBlank { "Tanpa Judul" }
    }

    fun isValidMangaUrl(url: String): Boolean {
        return url.isNotBlank() && 
               (url.contains("/manga/") || 
                url.contains("/manqa/") || 
                url.contains("/komik/")) && 
               !url.contains("javascript:") &&
               !url.contains("#") &&
               !url.endsWith("/#")
    }

    fun extractImageUrl(element: Element): String {
        var src = extractImageSrc(element)
        
        if (src.isEmpty()) return ""

        // Handle Cloudflare Image Delivery
        if (src.contains("/cdn-cgi/imagedelivery/")) {
            return src
        }

        val token = extractCloudflareToken(element.ownerDocument())

        if (src.contains("/uploads/") && token != null) {
            src = src.replace("/uploads/", "/cdn-cgi/imagedelivery/$token/")
        }

        return src
    }

    fun extractCloudflareToken(document: Document?): String? {
        if (document == null) return null

        // Method 1: From existing CDN URLs
        document.select("img[src*='/cdn-cgi/imagedelivery/']").firstOrNull()?.let { img ->
            Regex("""/cdn-cgi/imagedelivery/([^/]+)/""").find(img.attr("src"))?.let {
                return it.groupValues[1]
            }
        }

        // Method 2: From script content
        document.select("script").forEach { script ->
            val content = script.html()
            Regex("""cdn-cgi/imagedelivery/(\w+)""").find(content)?.let {
                return it.groupValues[1]
            }
            // Alternative patterns
            Regex("""imagedelivery['"]\s*:\s*['"](\w+)['"]""").find(content)?.let {
                return it.groupValues[1]
            }
        }

        // Method 3: From data attributes
        document.select("[data-cf-token]").firstOrNull()?.attr("data-cf-token")?.let {
            if (it.isNotBlank()) return it
        }

        // Method 4: From meta tags
        document.select("meta[name='cf-token'], meta[property='cf-token']").firstOrNull()?.let { meta ->
            meta.attr("content").takeIf { it.isNotBlank() }?.let { return it }
        }

        return null
    }

    /**
     * Clean and normalize chapter names
     */
    fun normalizeChapterName(name: String): String {
        return name.trim()
            .replace(Regex("""^Ch\.?\s*""", RegexOption.IGNORE_CASE), "Chapter ")
            .replace(Regex("""^Chapter\s*(\d+)$"""), "Chapter $1")
            .replace(Regex("""\s+"""), " ")
    }

    /**
     * Extract chapter number for sorting
     */
    fun extractChapterNumber(name: String): Float {
        val patterns = listOf(
            Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""Ch\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+(?:\.\d+)?)""")
        )

        for (pattern in patterns) {
            pattern.find(name)?.let { match ->
                match.groups[1]?.value?.toFloatOrNull()?.let { return it }
            }
        }

        return 0f
    }
}
