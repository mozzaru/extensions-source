package eu.kanade.tachiyomi.extension.id.soulscans

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

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

@Serializable
class HomeSectionsDto(
    @SerialName("latest_comic_updates") val latestComicUpdates: List<HomeComicUpdateDto> = emptyList(),
)

@Serializable
class HomeComicUpdateDto(
    @SerialName("series_slug") private val seriesSlug: String,
    @SerialName("series_title") private val seriesTitle: String,
    @SerialName("poster_image_url") private val posterImageUrl: String? = null,
    @SerialName("series_comic_type") private val seriesComicType: String? = null,
    @SerialName("series_status") private val seriesStatus: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/comic/$seriesSlug"
        title = seriesTitle
        thumbnail_url = posterImageUrl
        genre = seriesComicType?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.replaceFirstChar { it.titlecase() }
        status = parseStatus(seriesStatus)
    }
}

@Serializable
class SeriesDetailDto(
    val slug: String,
    private val title: String,
    private val synopsis: String? = null,
    @SerialName("alternative_titles") private val alternativeTitles: JsonElement? = null,
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
            alternativeTitles.toTextOrNull()?.let {
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

private fun JsonElement?.toTextOrNull(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .joinToString()
        .takeIf { it.isNotBlank() }
    else -> null
}

@Serializable
class UnitDto(
    private val slug: String,
    private val number: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
    @SerialName("is_locked") private val isLocked: Boolean? = false,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = "/comic/$mangaSlug/chapter/${this@UnitDto.slug}"
        val rawName = number?.takeIf { it.isNotBlank() }
            ?.let { "Chapter ${it.cleanNumber()}" }
            ?: "Chapter"
        name = if (isLocked == true) "🔒 $rawName" else rawName
        date_upload = createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L
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

private fun String.cleanNumber(): String = if (contains('.')) trimEnd('0').trimEnd('.') else this
