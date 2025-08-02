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

            val rawName = cleanChapterName(chapterLink.text())
            if (rawName.isBlank()) return null

            val timeAttr = chapterLink.selectFirst("time")?.attr("datetime")
            val dateElement = findDateElement(chapterLink)
            val dateStr = timeAttr ?: dateElement?.text()?.trim().orEmpty()
            val uploadTime = if (timeAttr != null) parseIsoDate(timeAttr) else parseChapterDate(dateStr)

            return SChapter.create().apply {
                url = if (href.startsWith("http")) href.removePrefix(baseUrl) else href
                name = rawName
                scanlator   = null
                date_upload = uploadTime
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseChapterFromDiv(chapterDiv: Element): SChapter? {
        try {
            val chapterLink = chapterDiv.selectFirst("a[href*=/chapter-]") ?: return null
            val href = chapterLink.attr("href")
            if (href.isBlank()) return null
    
            val rawName = cleanChapterName(chapterLink.text())
            if (rawName.isBlank()) return null
    
            val timeAttr = chapterDiv.selectFirst("time")?.attr("datetime")
            val dateElement = chapterDiv.selectFirst(".chapter-date, .date, .time, .upload-date")
                ?: findDateElement(chapterDiv)
            val dateStr = timeAttr ?: dateElement?.text()?.trim().orEmpty()
            val uploadTime = if (timeAttr != null) parseIsoDate(timeAttr) else parseChapterDate(dateStr)
    
            return SChapter.create().apply {
                url = if (href.startsWith("http")) href.removePrefix(baseUrl) else href
                name = rawName
                scanlator = null
                date_upload = uploadTime
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
            val dateElement = findDateElement(button)
            val dateStr = timeAttr ?: dateElement?.text()?.trim().orEmpty()
            val uploadTime = if (timeAttr != null) parseIsoDate(timeAttr) else parseChapterDate(dateStr)
    
            return SChapter.create().apply {
                url = if (href.startsWith("http")) href.removePrefix(baseUrl) else href
                name = rawName
                scanlator = null
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
                url = if (href.startsWith("http")) href.removePrefix(baseUrl) else href
                name = rawName
                scanlator = null
                date_upload = uploadTime
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
            0L
        }
    }
}
