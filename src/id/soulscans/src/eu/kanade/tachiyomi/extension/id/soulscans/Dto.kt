package eu.kanade.tachiyomi.extension.id.soulscans

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }

@Serializable
class SearchPageDto(
    val data: List<MangaDto> = emptyList(),
    val page: Int = 1,
    val limit: Int = 50,
    val total: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
) {
    val hasNextPage: Boolean get() = page < totalPages
}

@Serializable
class MangaDto(
    private val slug: String,
    private val title: String,
    @SerialName("poster_image_url") private val posterImageUrl: String? = null,
    @SerialName("comic_subtype") private val comicSubtype: String? = null,
    @SerialName("comic_status") private val comicStatus: String? = null,
    @SerialName("series_status") private val seriesStatus: String? = null,
    private val synopsis: String? = null,
    @SerialName("alternative_titles") private val alternativeTitles: String? = null,
    @SerialName("author_name") private val authorName: String? = null,
    @SerialName("artist_name") private val artistName: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/comic/$slug"
        this.title = this@MangaDto.title
        thumbnail_url = posterImageUrl
        author = authorName?.takeIf { it.isNotBlank() }
        artist = artistName?.takeIf { it.isNotBlank() }
        genre = comicSubtype?.takeIf { it.isNotBlank() }
            ?.let { it.lowercase().replaceFirstChar { c -> c.titlecase() } }
        status = parseStatus(comicStatus ?: seriesStatus)
        description = synopsis?.takeIf { it.isNotBlank() }?.trim()
    }
}

/**
 * A chapter-level "latest releases" feed entry, returned by `/api/feed`.
 *
 * Unlike `/api/search?sort=latest` (which sorts by a series-level `updated_at`
 * that can be bumped by unrelated events, e.g. view counts, and does not
 * reliably reflect a new chapter release), this feed is driven directly by
 * chapter creation time and matches what the website's homepage shows.
 *
 * The `page` query parameter is ignored by this endpoint; use `limit` +
 * `offset` for pagination instead.
 */
@Serializable
class FeedItemDto(
    @SerialName("series_slug") private val seriesSlug: String,
    @SerialName("series_title") private val seriesTitle: String,
    @SerialName("poster_image_url") private val posterImageUrl: String? = null,
    private val type: String? = null,
) {
    val isComic get() = type == "COMIC"
    val slug get() = seriesSlug

    fun toSManga() = SManga.create().apply {
        url = "/comic/$seriesSlug"
        title = seriesTitle
        thumbnail_url = posterImageUrl
    }
}

@Serializable
class SeriesDetailDto(
    val slug: String,
    private val title: String,
    private val synopsis: String? = null,
    @SerialName("alternative_titles") private val alternativeTitles: String? = null,
    @SerialName("poster_image_url") private val posterImageUrl: String? = null,
    @SerialName("author_name") private val authorName: String? = null,
    @SerialName("artist_name") private val artistName: String? = null,
    private val genres: List<GenreRefDto> = emptyList(),
    @SerialName("comic_subtype") private val comicSubtype: String? = null,
    @SerialName("comic_status") private val comicStatus: String? = null,
    @SerialName("series_status") private val seriesStatus: String? = null,
    val units: List<UnitDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = "/comic/$slug"
        title = this@SeriesDetailDto.title
        thumbnail_url = posterImageUrl
        author = authorName?.takeIf { it.isNotBlank() }
        artist = artistName?.takeIf { it.isNotBlank() }
        description = buildString {
            synopsis?.takeIf { it.isNotBlank() }?.let { append(it.trim()) }
            alternativeTitles?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n\n")
                append("Judul alternatif: $it")
            }
        }
        genre = buildList {
            addAll(genres.mapNotNull { it.name?.takeIf { g -> g.isNotBlank() } })
            comicSubtype?.takeIf { it.isNotBlank() }
                ?.let { add(it.lowercase().replaceFirstChar { c -> c.titlecase() }) }
        }.joinToString()
        status = parseStatus(comicStatus ?: seriesStatus)
    }
}

@Serializable
class GenreRefDto(
    val name: String? = null,
    val slug: String? = null,
)

@Serializable
class GenreDto(
    val slug: String,
    val name: String,
)

private fun parseStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
    "ongoing" -> SManga.ONGOING
    "completed", "complete", "ended" -> SManga.COMPLETED
    "hiatus", "on_hiatus" -> SManga.ON_HIATUS
    "cancelled", "canceled", "dropped" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

@Serializable
class UnitDto(
    private val slug: String,
    private val title: String? = null,
    private val number: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
    @SerialName("is_locked") private val isLocked: Boolean? = false,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = "/comic/$mangaSlug/chapter/${this@UnitDto.slug}"
        val rawName = when {
            // Skip messy auto-generated filenames like "65_My_Simulated_Path_to_Immortality"
            title != null && title.isNotBlank() && !title.contains("_") -> title
            number != null -> "Chapter ${number.cleanNumber()}"
            else -> "Chapter"
        }
        // Locked/premium chapters can't be opened without an account. Mark them
        // so the user knows, while still listing them to keep numbering intact.
        name = if (isLocked == true) "🔒 $rawName" else rawName
        date_upload = createdAt?.let { dateFormat.tryParse(it) } ?: 0L
    }
}

@Serializable
class ChapterResponseDto(
    val chapter: ChapterDto = ChapterDto(),
)

@Serializable
class ChapterDto(
    val pages: List<PageDto> = emptyList(),
)

@Serializable
class PageDto(
    @SerialName("image_url") val imageUrl: String? = null,
)

private fun String.cleanNumber(): String = trimEnd('0').trimEnd('.')
