package eu.kanade.tachiyomi.extension.id.mangatale

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
        
        document.select("a[href*=/chapter-], button[onclick*='location.href']").forEach { element ->
            try {
                val href = element.attr("href").ifEmpty {
                    Regex("""['"](/chapter-[^'"]+)['"]""").find(element.attr("onclick"))?.groupValues?.get(1).orEmpty()
                }
                
                if (href.isBlank() || !href.contains("/chapter-")) return@forEach
                
                val name = element.text().trim()
                if (name.isBlank()) return@forEach
                
                // Look for date in various parent elements
                val dateElement = findDateElement(element)
                val dateStr = dateElement?.attr("datetime") ?: dateElement?.text() ?: ""
                
                val uploadTime = parseChapterDate(dateStr)
                val formattedDate = formatDate(uploadTime)
                
                chapters.add(SChapter.create().apply {
                    url = href.removePrefix(baseUrl)
                    this.name = name
                    this.scanlator = formattedDate
                    this.date_upload = uploadTime
                })
            } catch (e: Exception) {
                // Skip this chapter if parsing fails
            }
        }
        
        return chapters
    }
    
    private fun findDateElement(element: org.jsoup.nodes.Element): org.jsoup.nodes.Element? {
        // Try to find date in various parent structures
        var current = element.parent()
        repeat(3) { // Check up to 3 levels up
            current?.let { parent ->
                parent.selectFirst("time[datetime]")?.let { return it }
                parent.selectFirst("[data-time]")?.let { return it }
                parent.selectFirst(".date, .time, .chapter-date")?.let { return it }
            }
            current = current?.parent()
        }
        return null
    }
    
    private fun extractChapterNumber(name: String): Float {
        return Regex("""\d+(\.\d+)?""").find(name)?.value?.toFloatOrNull() ?: 0f
    }
    
    private fun parseChapterDate(dateString: String): Long {
        if (dateString.isBlank()) return System.currentTimeMillis()
        
        val cleaned = dateString.trim()
        
        // Try ISO formats first
        listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "dd/MM/yy",
            "MM/dd/yyyy",
            "MM/dd/yy"
        ).forEach { pattern ->
            try {
                return SimpleDateFormat(pattern, Locale.ENGLISH).parse(cleaned)?.time ?: 0L
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        // Try relative time parsing
        val lowerCase = cleaned.lowercase(Locale.ENGLISH)
        val now = Calendar.getInstance()
        
        when {
            lowerCase.contains("just now") || lowerCase.contains("baru saja") -> {
                return now.timeInMillis
            }
            lowerCase.contains("min") || lowerCase.contains("menit") -> {
                val minutes = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.MINUTE, -minutes)
                return now.timeInMillis
            }
            lowerCase.contains("hour") || lowerCase.contains("jam") -> {
                val hours = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.HOUR_OF_DAY, -hours)
                return now.timeInMillis
            }
            lowerCase.contains("day") || lowerCase.contains("hari") -> {
                val days = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.DAY_OF_MONTH, -days)
                return now.timeInMillis
            }
            lowerCase.contains("week") || lowerCase.contains("minggu") -> {
                val weeks = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.WEEK_OF_YEAR, -weeks)
                return now.timeInMillis
            }
            lowerCase.contains("month") || lowerCase.contains("bulan") -> {
                val months = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.MONTH, -months)
                return now.timeInMillis
            }
            lowerCase.contains("year") || lowerCase.contains("tahun") -> {
                val years = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.YEAR, -years)
                return now.timeInMillis
            }
        }
        
        return System.currentTimeMillis()
    }
    
    private fun formatDate(timestamp: Long): String {
        if (timestamp == 0L) return "Unknown"
        
        val date = Calendar.getInstance().apply { timeInMillis = timestamp }
        val now = Calendar.getInstance()
        
        val diffInMillis = now.timeInMillis - timestamp
        val diffInDays = diffInMillis / (24 * 60 * 60 * 1000)
        
        return when {
            diffInDays == 0L -> "Hari Ini"
            diffInDays == 1L -> "Kemarin"
            diffInDays < 7 -> "${diffInDays} hari lalu"
            else -> SimpleDateFormat("dd/MM/yy", Locale.ENGLISH).format(Date(timestamp))
        }
    }
}
