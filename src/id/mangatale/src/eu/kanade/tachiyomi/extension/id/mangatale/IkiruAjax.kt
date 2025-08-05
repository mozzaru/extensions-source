package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern


class IkiruAjax(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val headers: okhttp3.Headers,
) {
    private val jakartaTimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    fun getChapterList(mangaId: String, chapterId: String): List<SChapter> {
        val chapters = mutableSetOf<SChapter>()
        val timestamp = System.currentTimeMillis()
    
        // 1. AJAX chapter_list
        var page = 1
        while (true) {
            val url = "$baseUrl/ajax-call?action=chapter_list&manga_id=$mangaId&page=$page&t=$timestamp"
            val resp = try {
                client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            } catch (_: Exception) { break }
            if (!resp.isSuccessful) break
            val body = resp.body?.string().orEmpty()
            if (body.contains("Tidak ada chapter", true)) break
            val parsed = parseChaptersFromAjax(resp.asJsoup())
            if (parsed.isEmpty()) break
            chapters += parsed
            page++
        }
    
        // 2. Fallback chapter_selects
        if (chapters.isEmpty()) {
            listOf("head", "footer").forEach { loc ->
                val url = "$baseUrl/ajax-call?action=chapter_selects&manga_id=$mangaId&chapter_id=$chapterId&loc=$loc&t=$timestamp"
                val resp = try {
                    client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
                } catch (_: Exception) { return@forEach }
                if (!resp.isSuccessful) return@forEach
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@forEach
                chapters += parseChaptersFromAjax(resp.asJsoup())
            }
        }
    
        // 3. Crawl reader next
        val visited = mutableSetOf<String>()
        val already = chapters.map { it.url }.toMutableSet()
        var nextUrl = "$baseUrl/chapter-$chapterId"
    
        while (true) {
            if (!visited.add(nextUrl)) break
            val doc = try {
                client.newCall(Request.Builder().url(nextUrl).headers(headers).build()).execute().asJsoup()
            } catch (_: Exception) { break }
    
            // Deteksi halaman chapter via URL canonical
            val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href") ?: doc.location()
            if (canonical.contains("/chapter-")) {
                val chapterHref = canonical.removePrefix(baseUrl)
                if (chapterHref !in already) {
                    // Parse nama
                    val rawName = doc.selectFirst("div.font-semibold.text-gray-50.text-sm")?.text().orEmpty()
                    val chapterName = cleanChapterName(rawName)
                    // Parse tanggal
                    val dateEl = doc.selectFirst("time[itemprop=dateCreated], time[itemprop=datePublished]")
                    val dateUpload = dateEl?.let { parseDateFromDatetime(it) } ?: System.currentTimeMillis()
                    chapters += SChapter.create().apply {
                        url = chapterHref
                        name = chapterName
                        date_upload = dateUpload
                    }
                    already += chapterHref
                }
            }
    
            // Tombol Next
            val nextBtn = doc.selectFirst(
                "a[aria-label=\"Next\"]," +
                "a:has(span[data-lucide=chevron-right])"
            ) ?: break
            nextUrl = nextBtn.absUrl("href")
        }
    
        return chapters
            .distinctBy { it.url }
            .sortedByDescending { extractChapterNumber(it.name) }
    }

    private fun parseChaptersFromAjax(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        document.select("a[href*=/chapter-], div.chapter-item, .chapter-list-item, .chapter-entry, tr").forEach {
            parseChapter(it)?.let { chapters.add(it) }
        }

        return chapters
    }

    private fun parseChapter(element: Element): SChapter? {
        val chapterLink = element.selectFirst("a") ?: return null
        val href = chapterLink.absUrl("href")
        val (uploadTime, displayDate) = extractUploadDateFromElement(element)

        return SChapter.create().apply {
            url = href.removePrefix(baseUrl) // Ganti dengan baseUrl yang kamu punya
            name = cleanChapterName(chapterLink.text())
            date_upload = uploadTime // Ini sudah dalam millis
        }
    }

    private fun extractUploadDateFromElement(element: Element): Pair<Long, String> {
        val dateText = element.selectFirst(".chapter-date")?.text()?.trim() ?: return 0L to ""
        val timestamp = parseDateText(dateText)
        val displayDate = formatDateForDisplay(timestamp)
        return timestamp to displayDate
    }

    private fun isDateText(text: String): Boolean {
        val lower = text.lowercase(Locale.ENGLISH)
        return lower.contains("hari ini") || lower.contains("kemarin") ||
            lower.contains("baru saja") || lower.contains("just now") ||
            lower.matches(Regex(""".*\d+\s+(menit|jam|hari|minggu|bulan|tahun)(\s+yang)?\s+lalu.*""")) ||
            lower.matches(Regex(""".*\d+\s+(minute|hour|day|week|month|year)s?\s+ago.*""")) ||
            text.matches(Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}""")) ||
            text.matches(Regex("""\d{4}-\d{2}-\d{2}"""))
    }

    private fun parseChapterDate(text: String): Long {
        val lower = text.lowercase(Locale.ENGLISH)
        val now = Calendar.getInstance(jakartaTimeZone)
    
        return when {
            lower.contains("hari ini") -> now.timeInMillis
            lower.contains("kemarin") -> {
                now.add(Calendar.DAY_OF_MONTH, -1)
                now.timeInMillis
            }
            lower.contains("baru saja") || lower.contains("just now") -> now.timeInMillis
            lower.matches(Regex(""".*\d+\s+menit.*lalu.*""")) -> {
                val minutes = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: return getDefaultTimestamp()
                now.add(Calendar.MINUTE, -minutes)
                now.timeInMillis
            }
            lower.matches(Regex(""".*\d+\s+jam.*lalu.*""")) -> {
                val hours = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: return getDefaultTimestamp()
                now.add(Calendar.HOUR_OF_DAY, -hours)
                now.timeInMillis
            }
            lower.matches(Regex(""".*\d+\s+hari.*lalu.*""")) -> {
                val days = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: return getDefaultTimestamp()
                now.add(Calendar.DAY_OF_MONTH, -days)
                now.timeInMillis
            }
            lower.matches(Regex(""".*\d+\s+minggu.*lalu.*""")) -> {
                val weeks = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: return getDefaultTimestamp()
                now.add(Calendar.WEEK_OF_YEAR, -weeks)
                now.timeInMillis
            }
            lower.matches(Regex(""".*\d+\s+bulan.*lalu.*""")) -> {
                val months = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: return getDefaultTimestamp()
                now.add(Calendar.MONTH, -months)
                now.timeInMillis
            }
            lower.matches(Regex(""".*\d+\s+tahun.*lalu.*""")) -> {
                val years = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: return getDefaultTimestamp()
                now.add(Calendar.YEAR, -years)
                now.timeInMillis
            }
            else -> getDefaultTimestamp()
        }
    }

    fun formatDateForDisplay(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diffDays = TimeUnit.MILLISECONDS.toDays(now - timestamp).toInt()

        return when (diffDays) {
            0 -> "Hari Ini"
            1 -> "1 hari yang lalu"
            in 2..6 -> "$diffDays hari yang lalu"
            else -> SimpleDateFormat("dd/MM/yy", Locale("id")).format(Date(timestamp))
        }
    }

    private fun parseAbsoluteDate(dateString: String): Date? {
        val patterns = listOf(
            "dd/MM/yy" to Regex("""\d{1,2}/\d{1,2}/\d{2}"""),
            "dd/MM/yyyy" to Regex("""\d{1,2}/\d{1,2}/\d{4}"""),
            "dd-MM-yy" to Regex("""\d{1,2}-\d{1,2}-\d{2}"""),
            "dd-MM-yyyy" to Regex("""\d{1,2}-\d{1,2}-\d{4}"""),
            "yyyy-MM-dd" to Regex("""\d{4}-\d{2}-\d{2}"""),
            // Tambahkan format baru untuk format tanpa garis miring
            "ddMMyy" to Regex("""\d{6}""")
        )
    
        for ((pattern, regex) in patterns) {
            if (regex.containsMatchIn(dateString)) {
                try {
                    return SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                        timeZone = jakartaTimeZone
                        // Handle format tanpa separator
                        if (pattern == "ddMMyy") {
                            val cleaned = dateString.take(6)
                            val day = cleaned.substring(0, 2).toInt()
                            val month = cleaned.substring(2, 4).toInt() - 1
                            val year = cleaned.substring(4, 6).toInt() + 2000
                            return Calendar.getInstance(jakartaTimeZone).apply {
                                set(year, month, day)
                            }.time
                        }
                    }.parse(dateString)
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun extractChapterNumber(name: String): Float {
        val match = Regex("""chapter\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(name)
        return match?.let { it.groups[1]?.value?.toFloatOrNull() ?: it.groups[2]?.value?.toFloatOrNull() } ?: 0f
    }

    private fun cleanChapterName(name: String): String {
        return name.trim().replace(Regex("""^\s*chapter\s*""", RegexOption.IGNORE_CASE), "Chapter ")
    }

    private fun getDefaultTimestamp(): Long {
        return Calendar.getInstance(jakartaTimeZone).apply {
            add(Calendar.DAY_OF_MONTH, -7)
        }.timeInMillis
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(now: Calendar, date: Calendar): Boolean {
        return isSameDay(Calendar.getInstance(jakartaTimeZone).apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_MONTH, -1)
        }, date)
    }
    
    private fun parseDateFromDatetime(el: Element): Long {
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)
                .parse(el.attr("datetime"))!!.time
        }.getOrDefault(System.currentTimeMillis())
    }
    
    private fun parseDateText(dateText: String): Long {
        val now = System.currentTimeMillis()
        return when {
            dateText.contains("hari yang lalu") -> {
                val days = dateText.split(" ")[0].toIntOrNull() ?: 0
                now - TimeUnit.DAYS.toMillis(days.toLong())
            }
            dateText.equals("Hari Ini", ignoreCase = true) -> now
            else -> {
                try {
                    val format = SimpleDateFormat("dd/MM/yy", Locale("id"))
                    format.parse(dateText)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
        }
    }
    
    private fun parseFriendlyDate(text: String): Long {
        val now = Calendar.getInstance(jakartaTimeZone)
        val lower = text.lowercase(Locale("id"))
        return when {
            "hari ini" in lower -> now.timeInMillis
            "kemarin" in lower -> now.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
            lower.matches(Regex(".*\\d+\\s+hari\\s+lalu.*")) -> {
                val days = Regex("\\d+").find(lower)?.value?.toLongOrNull() ?: 0L
                now.apply { add(Calendar.DAY_OF_MONTH, -days.toInt()) }.timeInMillis
            }
            else -> runCatching {
                SimpleDateFormat("dd/MM/yy", Locale("id")).parse(text)!!.time
            }.getOrDefault(now.timeInMillis)
        }
    }
}
