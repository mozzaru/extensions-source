package eu.kanade.tachiyomi.extension.id.mangkomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.security.SecureRandom
import java.util.Base64

class SirenKomik : HttpSource() {

    override val name = "Siren Komik"
    override val baseUrl = "https://sirenkomik.xyz"
    override val lang = "id"
    override val supportsLatest = true

    private val apiBase = "$baseUrl/api"
    private val imageBaseUrl = "https://gambar.sirenkomik.xyz"

    private val json: Json by injectLazy()

    /**
     * Solusi Token: Menghasilkan token Base64 URL-safe baru untuk setiap sesi
     * untuk menghindari masalah token kedaluwarsa secara permanen.
     */
    private val sessionToken: String by lazy { generateRandomBase64UrlToken() }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("GlobalAuthorization", "Bearer $sessionToken")
        .add("Cache-Control", "no-cache, no-store, must-revalidate")
        .add("Pragma", "no-cache")
        .add("Expires", "0")

    // === Utilitas Token ===
    // Menghasilkan string Base64Url acak sepanjang 43 karakter.
    private fun generateRandomBase64UrlToken(length: Int = 43): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)

        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        return base64.substring(0, length)
    }

    // === Request API ===
    override fun popularMangaRequest(page: Int): Request {
        return GET("$apiBase/manga/list?page=$page&sort=popular", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$apiBase/manga/list?page=$page&sort=latest", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$apiBase/manga/list?page=$page&q=$query", headers)
    }

    // Semua parsing tetap sama karena format DTO sudah benar
    override fun popularMangaParse(response: Response): MangasPage {
        val dto = json.decodeFromString(MangaListResponseDto.serializer(), response.body.string())
        return parseMangaList(dto)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = json.decodeFromString(MangaListResponseDto.serializer(), response.body.string())
        return parseMangaList(dto)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = json.decodeFromString(MangaListResponseDto.serializer(), response.body.string())
        return parseMangaList(dto)
    }

    private fun parseMangaList(dto: MangaListResponseDto): MangasPage {
        val mangas = dto.data.manga.map { it.toSManga() }
        val hasNextPage = dto.data.pagination.current_page < dto.data.pagination.total_pages
        return MangasPage(mangas, hasNextPage)
    }

    // === Detail Manga ===
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$apiBase${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = json.decodeFromString(MangaDetailResponseDto.serializer(), response.body.string())
        return dto.data.toSMangaDetails()
    }

    // === Chapter ===
    override fun chapterListRequest(manga: SManga): Request {
        return mangaDetailsRequest(manga)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = json.decodeFromString(MangaDetailResponseDto.serializer(), response.body.string())
        val mangaSlug = dto.data.slug
        return dto.data.chapters.map { it.toSChapter(mangaSlug) }.reversed()
    }

    // === Halaman (Gambar) ===
    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$apiBase${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val dto = json.decodeFromString(ChapterResponseDto.serializer(), response.body.string())
        val images = dto.data.currentChapterData.images.firstOrNull()
            ?: throw Exception("Tidak ada sumber gambar ditemukan")

        return images.mapIndexed { index, imageUrl ->
            // Pastikan URL gambar menggunakan domain gambar yang benar
            Page(index, "", imageUrl.replace("https://gambar.sirenkomik.xyz", imageBaseUrl))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // === Helper Konversi DTO ===
    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        title = this@toSManga.title
        url = "/manga/${this@toSManga.slug}"
        thumbnail_url = (this@toSManga.cover ?: this@toSManga.coverImage)?.replace("https://gambar.sirenkomik.xyz", imageBaseUrl)
    }

    private fun MangaDto.toSMangaDetails(): SManga = SManga.create().apply {
        title = this@toSMangaDetails.title
        url = "/manga/${this@toSMangaDetails.slug}"
        // FIX: Hapus baseUrl dari thumbnail_url karena sudah ada di DTO
        thumbnail_url = this@toSMangaDetails.coverImage?.replace("https://gambar.sirenkomik.xyz", imageBaseUrl)
        author = this@toSMangaDetails.author
        artist = this@toSMangaDetails.artist
        status = parseStatus(this@toSMangaDetails.status)
        description = this@toSMangaDetails.description
        genre = this@toSMangaDetails.genres?.joinToString(", ") { it.name }
    }

    private fun ChapterDto.toSChapter(mangaSlug: String): SChapter = SChapter.create().apply {
        name = this@toSChapter.title
        url = "/manga/$mangaSlug/${this@toSChapter.slug}"
        date_upload = this@toSChapter.date * 1000 // Epoch seconds to milliseconds
        chapter_number = this@toSChapter.number.toFloatOrNull() ?: 0f
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
}

// === DTO (Data Transfer Objects) ===
@Serializable
data class MangaListResponseDto(
    val data: MangaListDataDto,
)

@Serializable
data class MangaListDataDto(
    val manga: List<MangaDto>,
    val pagination: PaginationDto,
)

@Serializable
data class PaginationDto(
    @SerialName("current_page") val current_page: Int,
    @SerialName("total_pages") val total_pages: Int,
)

@Serializable
data class MangaDetailResponseDto(
    val data: MangaDto,
)

@Serializable
data class MangaDto(
    val id: Int,
    val title: String,
    val slug: String,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val genres: List<GenreDto>? = null,
    val description: String? = null,
    val cover: String? = null,
    @SerialName("coverImage") val coverImage: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
data class GenreDto(
    val name: String,
)

@Serializable
data class ChapterDto(
    val id: Int,
    val title: String,
    val number: String,
    val slug: String,
    val date: Long, // Epoch timestamp
)

@Serializable
data class ChapterResponseDto(
    val data: ChapterDataDto,
)

@Serializable
data class ChapterDataDto(
    @SerialName("currentChapterData") val currentChapterData: CurrentChapterDto,
)

@Serializable
data class CurrentChapterDto(
    val images: List<List<String>>,
)
