package eu.kanade.tachiyomi.extension.id.mangatale

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class IkiruAjax(private val client: OkHttpClient, private val baseUrl: String, private val headers: okhttp3.Headers) {
    
    private val jakartaTimeZone = TimeZone.getTimeZone("Asia/Jakarta")
    
    fun getChapterList(mangaId: String, chapterId: String): List<SChapter> {
        val chapters = mutableSetOf<SChapter>() // pakai Set untuk menghindari duplikat
    
        // 1. Coba endpoint cepat dulu
        var page = 1
        var found = false
        while (true) {
            val fastUrl = "$baseUrl/ajax-call?action=chapter_list&manga_id=$mangaId&page=$page"
            try {
                val request = Request.Builder().url(fastUrl).headers(headers).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank() || body.contains("Tidak ada chapter", true)) return@use
    
                    val doc = Jsoup.parse(body)
                    val parsed = parseChaptersFromAjax(doc)
                    if (parsed.isEmpty()) return@use
                    chapters += parsed
                    page++
                    found = true
                }
            } catch (e: Exception) {
                break
            }
        }
    
        // 2. Fallback kalau chapter_list kosong: pakai head/footer
        if (!found || chapters.isEmpty()) {
            listOf("head", "footer").forEach { loc ->
                val slowUrl = "$baseUrl/ajax-call?action=chapter_selects&manga_id=$mangaId&chapter_id=$chapterId&loc=$loc"
                try {
                    val request = Request.Builder().url(slowUrl).headers(headers).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) return@use
                        val doc = Jsoup.parse(body)
                        chapters += parseChaptersFromAjax(doc)
                    }
                } catch (_: Exception) {}
            }
        }
    
        return chapters
            .distinctBy { it.url }
            .sortedByDescending { extractChapterNumber(it.name) }
    }
    
    private fun parseChapters(doc: Document): List<SChapter> =
    doc.select("a[href*=/chapter-]").mapNotNull { el ->
        val href = el.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val raw = el.selectFirst("p.inline-block, .chapternum, .chapter-title")
            ?.text()?.trim() ?: el.text().trim()
        val name = cleanName(raw).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val date = el.selectFirst("time[datetime]")
            ?.attr("datetime")?.let(::parseIso) ?: System.currentTimeMillis()

        SChapter.create().apply {
            url = href.removePrefix(baseUrl)
            this.name = name
            date_upload = date
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
            
            val name = cleanChapterName(chapterLink.text())
            if (name.isBlank()) return null
            
            val dateElement = findDateElement(chapterLink)
            val dateStr = dateElement?.text()?.trim() ?: ""
            val uploadTime = parseChapterDate(dateStr)
            
            return SChapter.create().apply {
                url = href.removePrefix(baseUrl)
                this.name = name
                this.scanlator = null
                this.date_upload = uploadTime
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseChapterFromDiv(chapterDiv: Element): SChapter? {
        try {
            val chapterLink = chapterDiv.select("a[href*=/chapter-]").firstOrNull() ?: return null
            val href = chapterLink.attr("href")
            if (href.isBlank()) return null
            
            val name = cleanChapterName(chapterLink.text())
            if (name.isBlank()) return null
            
            // Look for date in the same div or nearby elements
            val dateElement = chapterDiv.select(".chapter-date, .date, .time, .upload-date").firstOrNull()
                ?: findDateElement(chapterDiv)
            val dateStr = dateElement?.text()?.trim() ?: ""
            val uploadTime = parseChapterDate(dateStr)
            
            return SChapter.create().apply {
                url = href.removePrefix(baseUrl)
                this.name = name
                this.scanlator = null
                this.date_upload = uploadTime
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
            
            val name = cleanChapterName(button.text())
            if (name.isBlank()) return null
            
            val dateElement = findDateElement(button)
            val dateStr = dateElement?.text()?.trim() ?: ""
            val uploadTime = parseChapterDate(dateStr)
            
            return SChapter.create().apply {
                url = href.removePrefix(baseUrl)
                this.name = name
                this.scanlator = null
                this.date_upload = uploadTime
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseChapterFromTableRow(row: Element): SChapter? {
        try {
            val chapterLink = row.select("a[href*=/chapter-]").firstOrNull() ?: return null
            val href = chapterLink.attr("href")
            if (href.isBlank()) return null
            
            val name = cleanChapterName(chapterLink.text())
            if (name.isBlank()) return null
            
            // In table rows, date is often in a separate cell
            val dateCell = row.select("td").find { cell ->
                isDateText(cell.text())
            }
            val dateStr = dateCell?.text()?.trim() ?: ""
            val uploadTime = parseChapterDate(dateStr)
            
            return SChapter.create().apply {
                url = href.removePrefix(baseUrl)
                this.name = name
                this.scanlator = null
                this.date_upload = uploadTime
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun cleanChapterName(name: String): String {
        return name.trim()
            .replace(Regex("""^\s*chapter\s*""", RegexOption.IGNORE_CASE), "Chapter ")
            .replace(Regex("""\s+"""), " ")
    }
    
    private fun findDateElement(element: Element): Element? {
        // Check immediate siblings first
        var sibling = element.nextElementSibling()
        while (sibling != null) {
            if (isDateText(sibling.text())) {
                return sibling
            }
            sibling = sibling.nextElementSibling()
        }
        
        sibling = element.previousElementSibling()
        while (sibling != null) {
            if (isDateText(sibling.text())) {
                return sibling
            }
            sibling = sibling.previousElementSibling()
        }
        
        // Check parent's other children
        element.parent()?.let { parent ->
            parent.children().forEach { child ->
                if (child != element && isDateText(child.text())) {
                    return child
                }
            }
            
            // Check parent's siblings
            var parentSibling = parent.nextElementSibling()
            while (parentSibling != null) {
                if (isDateText(parentSibling.text())) {
                    return parentSibling
                }
                parentSibling = parentSibling.nextElementSibling()
            }
        }
        
        // Check descendants with date-related classes
        element.parent()?.select(".date, .time, .upload-date, .chapter-date, .published")?.firstOrNull { dateEl ->
            isDateText(dateEl.text())
        }?.let { return it }
        
        return null
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
            return getDefaultTimestamp()
        }
        
        val cleaned = dateString.trim()
        val lowerCase = cleaned.lowercase(Locale.ENGLISH)
        val now = Calendar.getInstance(jakartaTimeZone)

        // Try to parse absolute date first
        val absoluteDate = parseAbsoluteDate(cleaned)
        if (absoluteDate != null) {
            return absoluteDate.time
        }

        // Handle relative terms
        when {
            lowerCase.contains("hari ini") || lowerCase.contains("today") -> {
                return now.timeInMillis
            }
            lowerCase.contains("kemarin") || lowerCase.contains("yesterday") -> {
                now.add(Calendar.DAY_OF_MONTH, -1)
                return now.timeInMillis
            }
            lowerCase.contains("baru saja") || lowerCase.contains("just now") -> {
                return now.timeInMillis
            }
            lowerCase.matches(Regex(""".*\d+\s+menit.*lalu.*""")) || 
            lowerCase.matches(Regex(""".*\d+\s+minute.*ago.*""")) -> {
                val minutes = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.MINUTE, -minutes)
                return now.timeInMillis
            }
            lowerCase.matches(Regex(""".*\d+\s+jam.*lalu.*""")) || 
            lowerCase.matches(Regex(""".*\d+\s+hour.*ago.*""")) -> {
                val hours = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.HOUR_OF_DAY, -hours)
                return now.timeInMillis
            }
            lowerCase.matches(Regex(""".*\d+\s+hari.*lalu.*""")) || 
            lowerCase.matches(Regex(""".*\d+\s+day.*ago.*""")) -> {
                val days = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.DAY_OF_MONTH, -days)
                return now.timeInMillis
            }
            lowerCase.matches(Regex(""".*\d+\s+minggu.*lalu.*""")) || 
            lowerCase.matches(Regex(""".*\d+\s+week.*ago.*""")) -> {
                val weeks = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.WEEK_OF_YEAR, -weeks)
                return now.timeInMillis
            }
            lowerCase.matches(Regex(""".*\d+\s+bulan.*lalu.*""")) || 
            lowerCase.matches(Regex(""".*\d+\s+month.*ago.*""")) -> {
                val months = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.MONTH, -months)
                return now.timeInMillis
            }
            lowerCase.matches(Regex(""".*\d+\s+tahun.*lalu.*""")) || 
            lowerCase.matches(Regex(""".*\d+\s+year.*ago.*""")) -> {
                val years = Regex("""\d+""").find(lowerCase)?.value?.toIntOrNull() ?: 0
                now.add(Calendar.YEAR, -years)
                return now.timeInMillis
            }
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
    
    private fun formatDateForDisplay(timestamp: Long, originalDateString: String): String {
        if (timestamp == 0L) return "Unknown"
    
        val now = Calendar.getInstance(jakartaTimeZone)
        val date = Calendar.getInstance(jakartaTimeZone).apply { timeInMillis = timestamp }
    
        return when {
            isSameDay(now, date) -> "Hari Ini"
            isYesterday(now, date) -> "Kemarin"
            else -> {
                val sdf = SimpleDateFormat("dd/MM/yy", Locale.ENGLISH)
                sdf.timeZone = jakartaTimeZone
                sdf.format(Date(timestamp))
            }
        }
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
    
    private fun extractNum(name: String): Float {
        val numberMatch = Regex("""(\d+(?:\.\d+)?)""").find(name)
        return numberMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }
    
    private fun cleanName(name: String): String {
        return name.trim()
            .replace(Regex("""^\s*chapter\s*""", RegexOption.IGNORE_CASE), "Chapter ")
            .replace(Regex("""\s+"""), " ")
    }
    
    private fun parseIso(datetime: String): Long {
        return try {
            java.time.Instant.parse(datetime).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
