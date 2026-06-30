package eu.kanade.tachiyomi.extension.id.mgkomik

import keiyoushi.utils.tryParse
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// mages
internal fun Element.imageUrl(): String = when {
    hasAttr("data-src") -> attr("abs:data-src")
    hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
    hasAttr("srcset") -> attr("abs:srcset").substringBefore(' ')
    hasAttr("src") -> attr("abs:src")
    else -> attr("src")
}

internal fun Element.isPageImage(imageUrl: String): Boolean {
    if (imageUrl.isBlank() || imageUrl.startsWith("data:image") || imageUrl.isNonContentImage()) return false

    return PAGE_ALT_REGEX.containsMatchIn(attr("alt"))
}

internal fun String.isNonContentImage(): Boolean = NON_CONTENT_IMAGE_REGEX.containsMatchIn(this)

// URL paths
internal fun String.isMangaUrl(): Boolean = MANGA_URL_REGEX.matches(toSourcePath().trim('/'))

internal fun String.isChapterUrl(): Boolean = CHAPTER_URL_REGEX.matches(toSourcePath().trim('/'))

internal fun String.toSourcePath(): String {
    val rawPath = if (startsWith("http", ignoreCase = true)) {
        "/" + substringAfter("://").substringAfter("/")
    } else {
        this
    }
    val path = rawPath.substringBefore('?').substringBefore('#')
    return if (path.startsWith('/')) path else "/$path"
}

// Text utils
internal fun String.cleanText(): String = replace(Regex("\\s+"), " ").trim()

internal fun String.titleCase(): String = split(' ')
    .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }

// Chapters
internal fun String.cleanChapterName(url: String): String {
    val slug = url.trim('/').substringAfterLast('/').substringAfter("chapter-")
    val slugDigits = slug.filter(Char::isDigit)
    val textMatch = CHAPTER_NAME_REGEX.find(this)

    if (textMatch != null) {
        val number = textMatch.groupValues[1]
        if (slugDigits.isEmpty() || number.filter(Char::isDigit) == slugDigits) {
            return "Chapter $number"
        }
    }

    return if (slug.isNotBlank()) {
        "Chapter ${slug.replace(NUMBER_SEPARATOR_REGEX, ".")}"
    } else {
        this.cleanText()
    }
}

internal fun chapterNumberFromNameOrUrl(name: String, url: String): Float {
    val nameNumber = name.substringAfter("Chapter", "").trim().toFloatOrNull()
    if (nameNumber != null) return nameNumber

    return url.trim('/')
        .substringAfterLast('/')
        .substringAfter("chapter-")
        .replace(NUMBER_SEPARATOR_REGEX, ".")
        .toFloatOrNull()
        ?: -1f
}

// Dates
internal fun parseChapterDate(text: String): Long {
    val relativeDate = RELATIVE_DATE_REGEX.find(text.lowercase(Locale.ROOT))
    if (relativeDate != null) {
        val amount = relativeDate.groupValues[1].toIntOrNull() ?: return 0L
        val unit = relativeDate.groupValues[2]

        return Calendar.getInstance().apply {
            when {
                unit.startsWith("minute") -> add(Calendar.MINUTE, -amount)
                unit.startsWith("hour") -> add(Calendar.HOUR_OF_DAY, -amount)
                unit.startsWith("day") -> add(Calendar.DATE, -amount)
                unit.startsWith("week") -> add(Calendar.DATE, -amount * 7)
                unit.startsWith("month") -> add(Calendar.MONTH, -amount)
                unit.startsWith("year") -> add(Calendar.YEAR, -amount)
            }
        }.timeInMillis
    }

    val absoluteDate = ABSOLUTE_DATE_REGEX.find(text)?.value
    return dateFormat.tryParse(absoluteDate)
}

// Regex / format
internal val MANGA_URL_REGEX = Regex("""komik/[^/]+""")
internal val CHAPTER_URL_REGEX = Regex("""komik/[^/]+/chapter-[^/]+""")
internal val dateFormat = SimpleDateFormat("dd MMM yy", Locale.US).apply { isLenient = false }
internal val ONGOING_STATUS_REGEX = Regex("""(?i)\b(on-?going)\b""")
internal val COMPLETED_STATUS_REGEX = Regex("""(?i)\b(completed|complete|end)\b""")
internal val CHAPTER_NAME_REGEX = Regex("""(?i)Chapter\s+([0-9]+(?:\.[0-9]+)?)""")
internal val NUMBER_SEPARATOR_REGEX = Regex("""(?<=\d)-(?=\d)""")
internal val ABSOLUTE_DATE_REGEX = Regex("""\b\d{1,2}\s+[A-Za-z]{3}\s+\d{2}\b""")
internal val RELATIVE_DATE_REGEX = Regex("""\b(\d+)\s+(minute|hour|day|week|month|year)s?\s+ago""")
internal val PAGE_ALT_REGEX = Regex("""(?i)\bchapter\b.*\bpage\b|\bpage\b.*\bchapter\b""")
internal val NON_CONTENT_IMAGE_REGEX = Regex("""(?i)(?:^data:image|/banner/|flagcdn\.com|komentar\.mgkomik\.cc|/favicon\.ico)""")
