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

class IkiruAjax(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val headers: okhttp3.Headers
) {
    private val jakartaTimeZone = TimeZone.getTimeZone("Asia/Jakarta")
    private val dateFormatter = SimpleDateFormat("dd/MM/yy", Locale("id")).apply {
        timeZone = jakartaTimeZone
    }

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
            if (!resp.isSuccessful || resp.body?.string().orEmpty().contains("Tidak ada chapter", true)) break
    
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
                if (!resp.isSuccessful || resp.body?.string().isNullOrBlank()) return@forEach
                chapters += parseChaptersFromAjax(resp.asJsoup())
            }
        }
    
        // 3. Coba crawl dari halaman detail
        val already = chapters.map { it.url }.toMutableSet()
        val detailDoc = try {
            client.newCall(Request.Builder().url("$baseUrl/manga/$mangaId").headers(headers).build()).execute().asJsoup()
        } catch (_: Exception) { null }
        detailDoc?.select("a[href*=/chapter-]")?.forEach { el ->
            val href = el.absUrl("href").removePrefix(baseUrl)
            if (href !in already) {
                val name = cleanChapterName(el.text())
                val dateText = el.parent()?.selectFirst(".chapter-date")?.text().orEmpty()
                val dateUpload = parseAbsoluteDate(dateText) ?: defaultTimestamp()
                chapters += SChapter.create().apply {
                    url = href
                    this.name = name
                    this.date_upload = dateUpload
                }
                already += href
            }
        }
    
        // 4. Crawl mode reader: dari halaman chapter-XX -> tombol next (>)
        val visited = mutableSetOf<String>()
        var nextUrl = "$baseUrl/chapter-$chapterId"
    
        while (true) {
            if (!visited.add(nextUrl)) break
    
            val doc = try {
                client.newCall(Request.Builder().url(nextUrl).headers(headers).build()).execute().asJsoup()
            } catch (_: Exception) { break }
    
            val chapterHref = nextUrl.removePrefix(baseUrl)
            if (chapterHref !in already) {
                val rawName = doc.selectFirst("div.font-semibold.text-gray-50.text-sm")?.text().orEmpty()
                val chapterName = cleanChapterName(rawName)
                val dateEl = doc.selectFirst("time[itemprop=dateCreated], time[itemprop=datePublished]")
                val dateUpload = parseDateFromDatetime(dateEl) ?: defaultTimestamp()
                chapters += SChapter.create().apply {
                    url = chapterHref
                    name = chapterName
                    date_upload = dateUpload
                }
                already += chapterHref
            }
    
            // Selector tombol next (reader navigation >)
            val nextBtn = doc.selectFirst("a[aria-label=Next]")
                ?: doc.selectFirst("div.shrink-0.px-4 > a[href*=/chapter-]") // kanan
                ?: doc.select("a[href*=/chapter-]").firstOrNull { it.text().trim().equals("Next", ignoreCase = true) }
                ?: doc.selectFirst("span[data-lucide=chevron-right]")?.closest("a")
    
            nextUrl = nextBtn?.absUrl("href") ?: break
        }
    
        return chapters
            .distinctBy { it.url }
            .sortedByDescending { extractChapterNumber(it.name) }
    }

    private fun parseChaptersFromAjax(document: Document): List<SChapter> {
        return document.select("a[href*=/chapter-]").mapNotNull { el ->
            val href = el.absUrl("href").removePrefix(baseUrl)
            val chapterName = cleanChapterName(el.text())
            val dateText = el.parent()?.selectFirst(".chapter-date")?.text().orEmpty()
            val dateUpload = parseAbsoluteDate(dateText) ?: defaultTimestamp()
            SChapter.create().apply {
                url = href
                name = chapterName
                date_upload = dateUpload
            }
        }
    }

    private fun parseAbsoluteDate(text: String): Long? {
        return runCatching {
            dateFormatter.parse(text)?.time
        }.getOrNull()
    }

    private fun parseDateFromDatetime(el: Element?): Long? {
        return el?.attr("datetime")?.let {
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH).apply {
                    timeZone = jakartaTimeZone
                }.parse(it)?.time
            }.getOrNull()
        }
    }

    private fun extractChapterNumber(name: String): Float {
        return Regex("chapter\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(name)?.groups?.get(1)?.value?.toFloatOrNull() ?: 0f
    }

    private fun cleanChapterName(name: String): String {
        return name.trim().replace(Regex("(?i)^chapter\\s*"), "Chapter ")
    }

    private fun defaultTimestamp(): Long {
        return Calendar.getInstance(jakartaTimeZone).apply {
            add(Calendar.DAY_OF_MONTH, -7)
        }.timeInMillis
    }
}