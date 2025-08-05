package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class IkiruAjax(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val headers: okhttp3.Headers
) {
    private val jakartaTimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    fun getChapterList(mangaId: String, chapterId: String): List<SChapter> {
        val chapters = mutableSetOf<SChapter>()
        val timestamp = System.currentTimeMillis()

        // 1. AJAX chapter_list
        var page = 1
        while (true) {
            val url = "$baseUrl/ajax-call?action=chapter_list&manga_id=$mangaId&page=$page&t=$timestamp"
            val resp = try { client.newCall(Request.Builder().url(url).headers(headers).build()).execute() } catch (_: Exception) { break }
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
                val resp = try { client.newCall(Request.Builder().url(url).headers(headers).build()).execute() } catch (_: Exception) { return@forEach }
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
            val doc = try { client.newCall(Request.Builder().url(nextUrl).headers(headers).build()).execute().asJsoup() } catch (_: Exception) { break }

            // Deteksi halaman chapter via URL canonical
            val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href") ?: doc.location()
            if (canonical.contains("/chapter-")) {
                val chapterHref = canonical.removePrefix(baseUrl)
                if (chapterHref !in already) {
                    val rawName = doc.selectFirst("div.font-semibold.text-gray-50.text-sm")?.text().orEmpty()
                    val chapterName = cleanChapterName(rawName)
                    val dateEl = doc.selectFirst("time[itemprop=dateCreated], time[itemprop=datePublished]")
                    val dateUpload = dateEl?.let { parseDateFromDatetime(it) } ?: System.currentTimeMillis()
                    chapters += SChapter.create().apply {
                        url = chapterHref
                        name = chapterName
                        date_upload = dateUpload
                        scanlator = formatDateDateOnly(dateUpload)
                    }
                    already += chapterHref
                }
            }

            // Tombol Next
            val nextBtn = doc.selectFirst(
                "a[aria-label=\"Next\"],a:has(span[data-lucide=chevron-right])"
            ) ?: break
            nextUrl = nextBtn.absUrl("href")
        }

        return chapters
            .distinctBy { it.url }
            .sortedByDescending { extractChapterNumber(it.name) }
    }

    private fun parseChaptersFromAjax(document: Document): List<SChapter> {
        return document.select("a[href*=/chapter-]").mapNotNull { el ->
            el.takeIf { it.hasAttr("href") }?.let {
                val href = it.absUrl("href").removePrefix(baseUrl)
                val chapterName = cleanChapterName(it.text())
                val dateText = it.parent()?.selectFirst(".chapter-date")?.text().orEmpty()
                val dateUpload = parseFriendlyDate(dateText)
                SChapter.create().apply {
                    url = href
                    name = chapterName
                    date_upload = dateUpload
                    scanlator = formatDateDateOnly(dateUpload)
                }
            }
        }
    }

    private fun parseDateFromDatetime(el: Element): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)
            .parse(el.attr("datetime"))!!.time
    }.getOrDefault(System.currentTimeMillis())

    private fun parseFriendlyDate(text: String): Long {
        val now = Calendar.getInstance(jakartaTimeZone)
        val lower = text.lowercase(Locale("id"))
        return when {
            "hari ini" in lower   -> now.timeInMillis
            "kemarin" in lower    -> now.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
            lower.matches(Regex(".*\\d+\\s+hari\\s+lalu.*")) -> {
                val days = Regex("\\d+").find(lower)?.value?.toLongOrNull() ?: 0L
                now.apply { add(Calendar.DAY_OF_MONTH, -days.toInt()) }.timeInMillis
            }
            else -> runCatching {
                SimpleDateFormat("dd/MM/yy", Locale("id")).parse(text)!!.time
            }.getOrDefault(now.timeInMillis)
        }
    }

    private fun formatDateDateOnly(timestamp: Long): String =
        SimpleDateFormat("dd/MM/yy", Locale("id")).apply {
            timeZone = jakartaTimeZone
        }.format(Date(timestamp))

    private fun extractChapterNumber(name: String): Float =
        Regex("chapter\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(name)?.groups?.get(1)?.value?.toFloatOrNull() ?: 0f

    private fun cleanChapterName(name: String): String =
        name.trim().replace(Regex("(?i)^chapter\\s*"), "Chapter ")
}
