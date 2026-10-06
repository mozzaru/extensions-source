package eu.kanade.tachiyomi.extension.id.ikiru

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import kotlin.time.Instant

@Serializable
class ApiResponse<T>(
    val data: T? = null,
)

@Serializable
class MangaList(
    val mangas: List<Manga>,
    val total: Int,
)

@Serializable
class ProjectList(
    val project: List<Manga>,
    val total: Int,
)

@Serializable
class Manga(
    private val slug: String,
    private val title: String,
    private val featuredImage: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        this.title = this@Manga.title
        thumbnail_url = featuredImage
    }
}

@Serializable
class MangaDetail(
    private val slug: String,
    private val title: String,
    private val featuredImage: String? = null,
    private val description: String? = null,
    private val type: String? = null,
    private val status: String? = null,
    private val metadata: Metadata? = null,
    private val chapters: ChapterList,
) {
    val hasMoreChapters get() = chapters.hasMore

    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@MangaDetail.title
        thumbnail_url = featuredImage
        description = buildString {
            this@MangaDetail.description?.let { append(Jsoup.parseBodyFragment(it).wholeText().trim()) }
            val altTitles = metadata?.alternateTitles.orEmpty()
                .filterNot { it.equals(this@MangaDetail.title, ignoreCase = true) }
            if (altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Names:\n")
                append(altTitles.joinToString("\n") { "- $it" })
            }
        }.trim().ifEmpty { null }
        author = metadata?.author?.joinToString { it.name }
        artist = metadata?.artist?.joinToString { it.name }
        genre = buildList {
            type?.let { add(it.replaceFirstChar(Char::uppercase)) }
            metadata?.genre?.forEach { add(it.name) }
        }.joinToString().ifEmpty { null }
        status = when (this@MangaDetail.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "CANCELLED" -> SManga.CANCELLED
            "HIATUS" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    fun toSChapterList(mangaSlug: String) = chapters.chapters.map { it.toSChapter(mangaSlug) }
}

@Serializable
class Metadata(
    val alternateTitles: List<String>? = null,
    val genre: List<Genre>? = null,
    val author: List<Entity>? = null,
    val artist: List<Entity>? = null,
)

@Serializable
class Entity(
    val name: String,
)

@Serializable
class ChapterList(
    val chapters: List<Chapter>,
    val hasMore: Boolean = false,
)

@Serializable
class Chapter(
    private val number: Double,
    private val title: String,
    private val createdAt: String? = null,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = number.toChapterNumber()
        name = title
        chapter_number = number.toFloat()
        date_upload = Instant.tryParse(createdAt)
        memo = buildJsonObject { put("slug", mangaSlug) }
    }
}

@Serializable
class ChapterDetail(
    val medias: List<Media>,
) {
    fun toPages() = medias
        .sortedBy { it.pageNumber }
        .mapIndexed { index, media -> Page(index, imageUrl = media.filePath) }
}

@Serializable
class Media(
    val filePath: String,
    val pageNumber: Int,
)

@Serializable
class GenreList(
    val allGenres: List<Genre>,
)

@Serializable
class Genre(
    val name: String,
    val id: String,
)

/**
 * Manga URL written by the previous (WordPress/natsuid) version of this extension.
 */
@Serializable
class LegacyMangaUrl(
    val slug: String,
)

private fun Double.toChapterNumber() = toString().removeSuffix(".0")
