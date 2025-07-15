package eu.kanade.tachiyomi.extension.id.mangatale

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import eu.kanade.tachiyomi.source.model.SChapter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class IkiruAjax(private val client: OkHttpClient, private val baseUrl: String, private val headers: okhttp3.Headers) {
    
    fun getChapterList(mangaId: String, chapterId: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        
        listOf("head", "footer").forEach { loc ->
            val ajaxUrl = "$baseUrl/ajax-call?action=chapter_selects&manga_id=$mangaId&chapter_id=$chapterId&loc=$loc"
            try {
                val response = client.newCall(Request.Builder().url(ajaxUrl).headers(headers).build()).execute()
                if (response.isSuccessful) {
                    val ajaxDoc = Jsoup.parse(response.body!!.string())
                    chapters.addAll(parseChaptersFromAjax(ajaxDoc))
                }
                response.close()
            } catch (e: Exception) {
                // Continue to next location if one fails
            }
        }
        
        return chapters
            .distinctBy { it.url }
            .sortedByDescending { extractChapterNumber(it.name) }
    }
    
    private fun parseChaptersFromAjax(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        
        // ikiru.wtf specific structure - look for chapter links and their date elements
        document.select("a[href*=/chapter-]").forEach { chapterLink ->
            try {
                val href = chapterLink.attr("href")
                if (href.isBlank() || !href.contains("/chapter-")) return@forEach
                
                val name = chapterLink.text().trim()
                if (name.isBlank()) return@forEach
                
                // Find date element - ikiru.wtf puts date after the chapter link
                val dateElement = findDateElement(chapterLink)
                val dateStr = dateElement?.text()?.trim() ?: ""
                
                val uploadTime = parseChapterDate(dateStr)
                
                chapters.add(SChapter.create().apply {
                    url = href.removePrefix(baseUrl)
                    this.name = name
                    this.scanlator = formatDateForDisplay(uploadTime, dateStr)
                    this.date_upload = uploadTime
                })
            } catch (e: Exception) {
                // Skip this chapter if parsing fails
            }
        }
        
        // Alternative parsing - look for button elements with onclick
        document.select("button[onclick*='location.href']").forEach { button ->
            try {
                val onclick = button.attr("onclick")
                val hrefMatch = Regex("""location\.href\s*=\s*['"]([^'"]+)['"]""").find(onclick)
                val href = hrefMatch?.groups?.get(1)?.value ?: return@forEach
                
                if (!href.contains("/chapter-")) return@forEach
                
                val name = button.text().trim()
                if (name.isBlank()) return@forEach
                
                val dateElement = findDateElement(button)
                val dateStr = dateElement?.text()?.trim() ?: ""
                
                val uploadTime = parseChapterDate(dateStr)
                
                chapters.add(SChapter.create().apply {
                    url = href.removePrefix(baseUrl)
                    this.name = name
                    this.scanlator = formatDateForDisplay(uploadTime, dateStr)
                    this.date_upload = uploadTime
                })
            } catch (e: Exception) {
                // Skip this chapter if parsing fails
            }
        }
        
        return chapters
    }
    
    private fun findDateElement(element: Element): Element? {
        // Check next sibling elements for date
        var sibling = element.nextElementSibling()
        while (sibling != null) {
            val text = sibling.text().trim()
            if (isDateText(text)) {
                return sibling
            }
            sibling = sibling.nextElementSibling()
        }
        
        // Check parent and its children
        val parent = element.parent()
        parent?.let { p ->
            p.children().forEach { child ->
                val text = child.text().trim()
                if (isDateText(text) && child != element) {
                    return child
                }
            }
            
            // Check parent's next siblings
            var parentSibling = p.nextElementSibling()
            while (parentSibling != null) {
                val text = parentSibling.text().trim()
                if (isDateText(text)) {
                    return parentSibling
                }
                parentSibling = parentSibling.nextElementSibling()
            }
        }
        
        return null
    }
    
    private fun isDateText(text: String): Boolean {
        if (text.isBlank()) return false
        
        val lowerText = text.lowercase(Locale.ENGLISH)
        
        // Check for Indonesian date patterns
        return lowerText.contains("hari ini") ||
               lowerText.contains("kemarin") ||
               lowerText.contains("ago") ||
               lowerText.contains("menit") ||
               lowerText.contains("jam") ||
               lowerText.contains("hari") ||
               lowerText.contains("minggu") ||
               lowerText.contains("bulan") ||
               lowerText.contains("tahun") ||
               text.matches(Regex("""\d{1,2}/\d{1,2}/\d{2,4}""")) ||
               text.matches(Regex("""\d{1,2}-\d{1,2}-\d{2,4}""")) ||
               text.matches(Regex("""\d+\s+(minute|hour|day|week|month|year)s?\s+ago"""))
    }
    
    private fun extractChapterNumber(name: String): Float {
        return Regex("""\d+(\.\d+)?""").find(name)?.value?.toFloatOrNull() ?: 0f
    }
    
    private fun parseChapterDate(dateString: String): Long {
        if (dateString.isBlank()) return System.currentTimeMillis()
        
        val cleaned = dateString.trim()
        val lowerCase = cleaned.lowercase(Locale.ENGLISH)
        
        // Handle Indonesian "Hari Ini" (Today) - should return current time
        if (lowerCase.contains("hari ini") || lowerCase.contains("today")) {
            return System.currentTimeMillis()
        }
        
        // Handle Indonesian "Kemarin" (Yesterday)
        if (lowerCase.contains("kemarin") || lowerCase.contains("yesterday")) {
            val yesterday = Calendar.getInstance()
            yesterday.add(Calendar.DAY_OF_MONTH, -1)
            return yesterday.timeInMillis
        }
        
        // Try date formats - ikiru.wtf uses dd/MM/yy format
        val dateFormats = listOf(
            "dd/MM/yy",
            "dd/MM/yyyy",
            "dd-MM-yy",
            "dd-MM-yyyy",
            "MM/dd/yy",
            "MM/dd/yyyy",
            "yyyy-MM-dd",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
        
        for (pattern in dateFormats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ENGLISH)
                val date = sdf.parse(cleaned)
                if (date != null) {
                    return date.time
                }
            } catch (e: Exception) {
                // Continue to next format
            }
        }
        
        // Handle relative time formats
        val now = Calendar.getInstance()
        
        when {
            lowerCase.contains("just now") || lowerCase.contains("baru saja") -> {
                return now.timeInMillis
            }
            lowerCase.contains("menit") || lowerCase.contains("minute") -> {
                val minutes = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.MINUTE, -minutes)
                return now.timeInMillis
            }
            lowerCase.contains("jam") || lowerCase.contains("hour") -> {
                val hours = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.HOUR_OF_DAY, -hours)
                return now.timeInMillis
            }
            lowerCase.contains("hari") && !lowerCase.contains("hari ini") -> {
                val days = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.DAY_OF_MONTH, -days)
                return now.timeInMillis
            }
            lowerCase.contains("minggu") || lowerCase.contains("week") -> {
                val weeks = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.WEEK_OF_YEAR, -weeks)
                return now.timeInMillis
            }
            lowerCase.contains("bulan") || lowerCase.contains("month") -> {
                val months = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.MONTH, -months)
                return now.timeInMillis
            }
            lowerCase.contains("tahun") || lowerCase.contains("year") -> {
                val years = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.YEAR, -years)
                return now.timeInMillis
            }
        }
        
        return System.currentTimeMillis()
    }
    
    private fun formatDateForDisplay(timestamp: Long, originalDateString: String): String {
        if (timestamp == 0L) return "Unknown"
        
        val lowerOriginal = originalDateString.lowercase(Locale.ENGLISH)
        
        // If original was "Hari Ini", show it as "Hari Ini"
        if (lowerOriginal.contains("hari ini")) {
            return "Hari Ini"
        }
        
        // If original was "Kemarin", show it as "Kemarin"  
        if (lowerOriginal.contains("kemarin")) {
            return "Kemarin"
        }
        
        // For actual dates, format as dd/MM/yy (like ikiru.wtf does)
        return SimpleDateFormat("dd/MM/yy", Locale.ENGLISH).format(Date(timestamp))
    }
    
    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
