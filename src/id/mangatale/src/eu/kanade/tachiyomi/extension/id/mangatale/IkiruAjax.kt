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
    
        // 1. Ambil semua dari paginasi chapter_list AJAX
        var page = 1
        while (true) {
            val url = "$baseUrl/ajax-call?action=chapter_list&manga_id=$mangaId&page=$page&t=$timestamp"
            val resp = try {
                client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            } catch (_: Exception) { break }
    
            if (!resp.isSuccessful) break
            val body = resp.body?.string().orEmpty()
            if (body.contains("Tidak ada chapter", true)) break
    
            val parsed = parseChaptersFromAjax(Jsoup.parse(body))
            if (parsed.isEmpty()) break
    
            chapters += parsed
            page++
        }
    
        // 2. Ambil dari reader navigation (next chapter) jika masih ada yang terlewat
        val visited = mutableSetOf<String>()
        val already = chapters.map { it.url }.toMutableSet()
        var nextUrl = "$baseUrl/chapter-$chapterId"
    
        while (true) {
            if (nextUrl in visited) break
            visited += nextUrl
    
            val doc = try {
                val resp = client.newCall(Request.Builder().url(nextUrl).headers(headers).build()).execute()
                if (!resp.isSuccessful) break
                Jsoup.parse(resp.body?.string().orEmpty())
            } catch (_: Exception) { break }
    
            // Ambil link chapter saat ini dari URL halaman
            val canonicalUrl = doc.selectFirst("link[rel=canonical]")?.attr("href")
            if (canonicalUrl != null && canonicalUrl.contains("/chapter-")) {
                val chapterHref = canonicalUrl.removePrefix(baseUrl)
                if (chapterHref !in already) {
                    val chapterName = doc.selectFirst("button > span")?.text()?.takeIf { it.contains("Chapter") }
                        ?: "Chapter ${extractChapterNumber(chapterHref)}"
                    chapters += SChapter.create().apply {
                        url = chapterHref
                        name = cleanChapterName(chapterName)
                        date_upload = System.currentTimeMillis()
                    }
                    already += chapterHref
                }
            }
    
            // Ambil tombol next
            val next = doc.selectFirst("a[aria-label='Next'], a:containsOwn(Next)")?.absUrl("href") ?: break
            nextUrl = next
        }
    
        return chapters.distinctBy { it.url }.sortedByDescending { extractChapterNumber(it.name) }
    }

    private fun parseChaptersFromAjax(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        document.select("a[href*=/chapter-], div.chapter-item, .chapter-list-item, .chapter-entry, tr").forEach {
            parseChapter(it)?.let { chapters.add(it) }
        }

        return chapters
    }

    private fun parseChapter(element: Element): SChapter? {
        val chapterLink = element.select("a[href*=/chapter-]").firstOrNull() ?: return null
        val href = chapterLink.attr("href").takeIf { it.isNotBlank() } ?: return null
        val name = cleanChapterName(chapterLink.text()).takeIf { it.isNotBlank() } ?: return null
        val (uploadTime, _) = extractUploadDateFromElement(element) // displayDate diabaikan
    
        return SChapter.create().apply {
            url = href.removePrefix(baseUrl)
            this.name = name
            this.date_upload = uploadTime
        }
    }

    private fun extractUploadDateFromElement(root: Element): Pair<Long, String> {
        val allText = root.select("*").map { it.text().trim() }
        val absolute = allText.mapNotNull { parseAbsoluteDate(it) }.firstOrNull()
        val relative = allText.find { isDateText(it) } ?: ""
        val timestamp = absolute?.time ?: parseChapterDate(relative)
        return timestamp to formatDateForDisplay(timestamp)
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

    private fun formatDateForDisplay(timestamp: Long): String {
        val now = Calendar.getInstance(jakartaTimeZone)
        val date = Calendar.getInstance(jakartaTimeZone).apply { timeInMillis = timestamp }
    
        val diffInMillis = now.timeInMillis - date.timeInMillis
        val daysDiff = (diffInMillis / (1000 * 60 * 60 * 24)).toInt()
    
        return when {
            daysDiff == 0 -> "Hari Ini"
            daysDiff == 1 -> "1 hari yang lalu"
            daysDiff in 2..6 -> "$daysDiff hari yang lalu"
            else -> SimpleDateFormat("dd/MM/yy", Locale.ENGLISH).apply {
                timeZone = jakartaTimeZone
            }.format(Date(timestamp))
        }
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
            val match = regex.find(dateString) ?: continue
            try {
                return SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    timeZone = jakartaTimeZone
                }.parse(match.value)
            } catch (_: Exception) {}
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
}
