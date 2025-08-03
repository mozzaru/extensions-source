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
import java.util.TimeZone
import java.time.Instant
import java.time.format.DateTimeFormatter

class IkiruAjax(private val client: OkHttpClient, private val baseUrl: String, private val headers: okhttp3.Headers) {

    private val jakartaTimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    fun getChapterList(mangaId: String, @Suppress("UNUSED_PARAMETER") chapterId: String): List<SChapter> {
        val ajaxUrl = "$baseUrl/ajax-call?action=chapter_list&manga_id=$mangaId&page=1"
        return try {
            val request = Request.Builder()
                .url(ajaxUrl)
                .headers(headers)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                val doc = Jsoup.parse(body)
                // Reuse parseChaptersFromAjax untuk parsing full list
                parseChaptersFromAjax(doc)
                    .distinctBy { it.url }
                    .sortedByDescending { extractChapterNumber(it.name) }
            }
        } catch (e: Exception) {
            println("Error fetching chapter_list: ${e.message}")
            emptyList()
        }
    }

    private fun parseChaptersFromAjax(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        // Method 1: Look for direct chapter links with ikiru.wtf structure
        document.select("a[href*=/chapter-]").forEach { chapterLink ->
            parseChapterFromLink(chapterLink)?.let { chapters.add(it) }
        }

        // Method 2: Look for chapter entries in list format (common in ikiru.wtf)
        document.select("div.chapter-item, .chapter-list-item, .chapter-entry").forEach { chapterDiv ->
            parseChapterFromDiv(chapterDiv)?.let { chapters.add(it) }
        }

        // Method 3: Look for button elements with onclick navigation
        document.select("button[onclick*='location.href'], button[onclick*='window.location']").forEach { button ->
            parseChapterFromButton(button)?.let { chapters.add(it) }
        }

        // Method 4: Look for table rows (alternative layout)
        document.select("tr").forEach { row ->
            parseChapterFromTableRow(row)?.let { chapters.add(it) }
        }
        return chapters
    }

    private fun parseChapterFromLink(chapterLink: Element): SChapter? {
        try {
            val href = chapterLink.attr("href")
            if (href.isBlank() || !href.contains("/chapter-")) return null
    
            // DAPATKAN NAMA dari elemen <p> di dalam link
            var rawName = chapterLink.selectFirst("p.inline-block")?.text()?.trim()
                ?: chapterLink.text() // Fallback ke teks link biasa
    
            // Jika nama masih berantakan (misal dari fallback), coba ekstrak dari URL
            if (rawName.isNullOrBlank() || rawName.contains("ago")) {
                 val chapterNumberFromUrl = Regex("""chapter-(\d+(?:\.\d+)?)""").find(href)?.groupValues?.get(1)
                 if (chapterNumberFromUrl != null) {
                     rawName = "Chapter $chapterNumberFromUrl"
                 }
            }
            
            rawName = cleanChapterName(rawName!!) // Bersihkan nama chapter
            if (rawName.isBlank()) return null
    
            // PRIORITASKAN tanggal dari elemen <time>
            val timeElement = chapterLink.selectFirst("time")
            val datetimeAttr = timeElement?.attr("datetime")
            
            val uploadTime = if (!datetimeAttr.isNullOrBlank()) {
                 parseIsoDate(datetimeAttr)
            } else {
                // Fallback jika <time> tidak ada, cari teks tanggal di sekitar
                val dateText = findDateInElement(chapterLink)
                parseChapterDate(dateText)
            }
    
            return SChapter.create().apply {
                this.url = href.removePrefix(baseUrl)
                name = rawName
                date_upload = uploadTime
                // Gunakan formatDateForDisplay untuk scanlator agar tampilan konsisten
                scanlator = formatDateForDisplay(uploadTime)
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    // Ganti juga fungsi parseChapterFromDiv dengan logika yang sama
    private fun parseChapterFromDiv(chapterDiv: Element): SChapter? {
        try {
            val chapterLink = chapterDiv.selectFirst("a[href*=/chapter-]") ?: return null
            val href = chapterLink.attr("href")
            if (href.isBlank()) return null
    
            // DAPATKAN NAMA dari elemen <p> di dalam link
            var rawName = chapterLink.selectFirst("p.inline-block")?.text()?.trim()
                ?: chapterDiv.selectFirst(".item-center p")?.text()?.trim() // Alternatif selector
                ?: chapterLink.text() // Fallback
    
            if (rawName.isNullOrBlank() || rawName.contains("ago")) {
                 val chapterNumberFromUrl = Regex("""chapter-(\d+(?:\.\d+)?)""").find(href)?.groupValues?.get(1)
                 if (chapterNumberFromUrl != null) {
                     rawName = "Chapter $chapterNumberFromUrl"
                 }
            }
    
            rawName = cleanChapterName(rawName!!)
            if (rawName.isBlank()) return null
    
            val timeElement = chapterDiv.selectFirst("time")
            val datetimeAttr = timeElement?.attr("datetime")
            
            val uploadTime = if (!datetimeAttr.isNullOrBlank()) {
                parseIsoDate(datetimeAttr)
            } else {
                val dateText = findDateInElement(chapterDiv)
                parseChapterDate(dateText)
            }
    
            return SChapter.create().apply {
                this.url = href.removePrefix(baseUrl)
                name = rawName
                date_upload = uploadTime
                scanlator = formatDateForDisplay(uploadTime)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseChapterFromButton(button: Element): SChapter? {
        try {
            val onclick = button.attr("onclick")
            val hrefMatch = Regex("""(?:location\.href|window\.location(?:\.href)?)\s*=\s*['"]([^'"]+)['"]""").find(onclick)
            val href = hrefMatch?.groups?.get(1)?.value ?: return null
            if (!href.contains("/chapter-")) return null
    
            val rawName = cleanChapterName(button.text())
            if (rawName.isBlank()) return null
    
            val timeAttr = button.selectFirst("time")?.attr("datetime")
            val dateText = findDateInElement(button)
            val uploadTime = if (timeAttr != null) {
                parseIsoDate(timeAttr)
            } else {
                parseChapterDate(dateText)
            }
    
            return SChapter.create().apply {
                this.url = href.removePrefix(baseUrl)
                name = rawName
                scanlator = formatDateForDisplay(uploadTime)
                date_upload = uploadTime
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseChapterFromTableRow(row: Element): SChapter? {
        try {
            val chapterLink = row.selectFirst("a[href*=/chapter-]") ?: return null
            val href = chapterLink.attr("href")
            if (href.isBlank()) return null
    
            val rawName = cleanChapterName(chapterLink.text())
            if (rawName.isBlank()) return null
    
            val timeAttr = row.selectFirst("time")?.attr("datetime")
            val dateCell = row.select("td").find { isDateText(it.text()) }
            val dateStr = timeAttr ?: dateCell?.text()?.trim().orEmpty()
            val uploadTime = if (timeAttr != null) parseIsoDate(timeAttr) else parseChapterDate(dateStr)
    
            return SChapter.create().apply {
                this.url = href.removePrefix(baseUrl)
                name = rawName
                scanlator = formatDateForDisplay(uploadTime)
                date_upload = uploadTime
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun cleanChapterName(name: String): String {
        // Fungsi pembersihan yang lebih agresif untuk menghapus info tanggal dari nama
        return name.replace(Regex("""\s+\d+\s+(minutes?|hours?|days?|weeks?|months?|years?)\s+ago.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\d+\s+(menit|jam|hari|minggu|bulan|tahun)\s+(yang\s+)?lalu.*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun findDateInElement(element: Element): String {
        // Check time element in current element
        element.selectFirst("time")?.attr("datetime")?.let { return it }
        
        // Check parent for date info
        val parent = element.parent()
        parent?.selectFirst("time")?.attr("datetime")?.let { return it }
        
        // Look for date text patterns in parent
        val parentText = parent?.text() ?: ""
        val datePatterns = listOf(
            Regex("""\d+\s+minutes?\s+ago"""),
            Regex("""\d+\s+hours?\s+ago"""),
            Regex("""\d+\s+days?\s+ago"""),
            Regex("""\d+\s+weeks?\s+ago"""),
            Regex("""\d+\s+months?\s+ago"""),
            Regex("""\d+\s+menit.*lalu"""),
            Regex("""\d+\s+jam.*lalu"""),
            Regex("""\d+\s+hari.*lalu"""),
            Regex("""\d+\s+minggu.*lalu"""),
            Regex("""\d+\s+bulan.*lalu"""),
            Regex("""hari ini""", RegexOption.IGNORE_CASE),
            Regex("""kemarin""", RegexOption.IGNORE_CASE),
            Regex("""today""", RegexOption.IGNORE_CASE),
            Regex("""yesterday""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in datePatterns) {
            pattern.find(parentText.lowercase())?.let { match ->
                return match.value
            }
        }
        
        // Check siblings for date info
        parent?.children()?.forEach { sibling ->
            val timeAttr = sibling.selectFirst("time")?.attr("datetime")
            if (timeAttr != null) {
                return timeAttr
            }
            val siblingText = sibling.text().trim()
            if (isDateText(siblingText)) {
                return siblingText
            }
        }
        
        return ""
    }

    private fun isDateText(text: String): Boolean {
        if (text.isBlank()) return false

        val cleanText = text.trim()
        val lowerText = cleanText.lowercase(Locale.ENGLISH)

        // Check for Indonesian date patterns
        return lowerText.contains("hari ini") ||
               lowerText.contains("kemarin") ||
               lowerText.contains("baru saja") ||
               lowerText.contains("just now") ||
               lowerText.matches(Regex(""".*\d+\s+(menit|jam|hari|minggu|bulan|tahun)(\s+yang)?\s+lalu.*""")) ||
               lowerText.matches(Regex(""".*\d+\s+(minute|hour|day|week|month|year)s?\s+ago.*""")) ||
               cleanText.matches(Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}""")) ||
               cleanText.matches(Regex("""\d{4}-\d{2}-\d{2}""")) ||
               cleanText.matches(Regex("""\d{1,2}:\d{2}(:\d{2})?"""))
    }

    private fun extractChapterNumber(name: String): Float {
        val numberMatch = Regex("""chapter\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(name)
        return numberMatch?.let { 
            it.groups[1]?.value?.toFloatOrNull() ?: it.groups[2]?.value?.toFloatOrNull() 
        } ?: 0f
    }

    private fun parseChapterDate(dateString: String): Long {
        if (dateString.isBlank()) {
            return System.currentTimeMillis()
        }
    
        val cleaned = dateString.trim().lowercase(Locale.ROOT)
        val now = Calendar.getInstance(jakartaTimeZone)
    
        // Handle Indonesian relative terms
        when {
            cleaned.contains("baru saja") || cleaned.contains("just now") -> return now.timeInMillis
            cleaned.contains("hari ini") || cleaned.contains("today") -> return now.timeInMillis
            cleaned.contains("kemarin") || cleaned.contains("yesterday") -> {
                now.add(Calendar.DAY_OF_MONTH, -1)
                return now.timeInMillis
            }
        }
    
        // Parse numeric relative time - IMPROVED VERSION
        val patterns = mapOf(
            Regex("""(\d+)\s*menit.*lalu""") to Calendar.MINUTE,
            Regex("""(\d+)\s*minutes?\s+ago""") to Calendar.MINUTE,
            Regex("""(\d+)\s*jam.*lalu""") to Calendar.HOUR_OF_DAY,
            Regex("""(\d+)\s*hours?\s+ago""") to Calendar.HOUR_OF_DAY,
            Regex("""(\d+)\s*hari.*lalu""") to Calendar.DAY_OF_MONTH,
            Regex("""(\d+)\s*days?\s+ago""") to Calendar.DAY_OF_MONTH,
            Regex("""(\d+)\s*minggu.*lalu""") to Calendar.WEEK_OF_YEAR,
            Regex("""(\d+)\s*weeks?\s+ago""") to Calendar.WEEK_OF_YEAR,
            Regex("""(\d+)\s*bulan.*lalu""") to Calendar.MONTH,
            Regex("""(\d+)\s*months?\s+ago""") to Calendar.MONTH
        )
    
        for ((pattern, unit) in patterns) {
            pattern.find(cleaned)?.let { match ->
                val amount = match.groupValues[1].toIntOrNull() ?: 0
                now.add(unit, -amount)
                return now.timeInMillis
            }
        }
    
        // Try parsing absolute date
        parseAbsoluteDate(dateString)?.let { date ->
            return date.time
        }
    
        return getDefaultTimestamp()
    }

    private fun parseAbsoluteDate(dateString: String): Date? {
        val patterns = listOf(
            "dd/MM/yy" to Regex("""\d{1,2}/\d{1,2}/\d{2}"""),
            "dd/MM/yyyy" to Regex("""\d{1,2}/\d{1,2}/\d{4}"""),
            "dd-MM-yy" to Regex("""\d{1,2}-\d{1,2}-\d{2}"""),
            "dd-MM-yyyy" to Regex("""\d{1,2}-\d{1,2}-\d{4}"""),
            "yyyy-MM-dd" to Regex("""\d{4}-\d{2}-\d{2}""")
        )

        for ((pattern, regex) in patterns) {
            val match = regex.find(dateString)
            if (match != null) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.ENGLISH)
                    sdf.timeZone = jakartaTimeZone
                    return sdf.parse(match.value)
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return null
    }

    private fun getDefaultTimestamp(): Long {
        val defaultTime = Calendar.getInstance(jakartaTimeZone)
        defaultTime.add(Calendar.DAY_OF_MONTH, -7)
        return defaultTime.timeInMillis
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(now: Calendar, date: Calendar): Boolean {
        val yesterday = Calendar.getInstance(now.timeZone).apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_MONTH, -1)
        }
        return isSameDay(yesterday, date)
    }

    fun parseIsoDate(datetime: String): Long {
        return try {
            Instant.parse(datetime).toEpochMilli()
        } catch (e: Exception) {
            // Fallback parsing for different ISO formats
            try {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
                Instant.from(formatter.parse(datetime)).toEpochMilli()
            } catch (e2: Exception) {
                // If all parsing fails, return current time
                System.currentTimeMillis()
            }
        }
    }
    
    private fun formatDateForDisplay(timestamp: Long): String {
        if (timestamp == 0L) return "Unknown"
    
        val now = Calendar.getInstance(jakartaTimeZone)
        val date = Calendar.getInstance(jakartaTimeZone).apply {
            timeInMillis = timestamp
        }
    
        val diffMillis = now.timeInMillis - date.timeInMillis
        val diffMinutes = (diffMillis / (1000 * 60)).toInt()
        val diffHours = (diffMillis / (1000 * 60 * 60)).toInt()
        val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
    
        return when {
            diffMinutes < 5 -> "Baru saja"
            diffMinutes < 60 -> "$diffMinutes menit yang lalu"
            diffHours < 24 && isSameDay(now, date) -> "Hari Ini"
            diffDays == 1 -> "1 hari yang lalu"
            diffDays in 2..6 -> "$diffDays hari yang lalu"
            diffDays in 7..13 -> "1 minggu yang lalu"
            diffDays in 14..29 -> "${diffDays / 7} minggu yang lalu"
            diffDays in 30..59 -> "1 bulan yang lalu"
            diffDays < 365 -> "${diffDays / 30} bulan yang lalu"
            else -> "${diffDays / 365} tahun yang lalu"
        }
    }
}
