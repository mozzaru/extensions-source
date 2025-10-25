package eu.kanade.tachiyomi.extension.id.sirenkomik

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class SirenKomik : MangaThemesia(
    "Siren Komik",
    "https://sirenkomik.xyz",
    "id",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id")),
) {
    override val id = 8457447675410081142

    // Corrected line: Use override instead of private
    override val json: Json by injectLazy()

    // =========================== Manga List Fetching ============================
    // Overrides to fetch from API instead of parsing HTML

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/list/popular?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/list/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // Search needs API too, assuming it exists at /api/search
    // NOTE: The exact search API endpoint might differ. This is a common pattern.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Use user agent from parent class
        val searchHeaders = headersBuilder().add("User-Agent", headers["User-Agent"] ?: "").build()
        return GET("$baseUrl/api/search?query=${query.trim()}&page=$page", searchHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    // Helper function to parse API response
    private fun parseMangaList(response: Response): MangasPage {
        val apiResponse = json.decodeFromString<ApiResponse<List<MangaDto>>>(response.body.string())

        if (!apiResponse.success || apiResponse.data == null) {
            return MangasPage(emptyList(), false)
        }

        val mangas = apiResponse.data.map { mangaDto ->
            SManga.create().apply {
                url = "/manga/${mangaDto.slug}" // Construct URL from slug
                title = mangaDto.title
                thumbnail_url = mangaDto.cover // Use cover field from DTO
                // Other fields like genre, status could be added if needed/available directly
            }
        }

        // Assuming API doesn't provide hasNextPage, default to true if list is not empty
        val hasNextPage = mangas.isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    // ======================= Manga Details & Chapters =======================
    // Keep original overrides as they might still work for meta tags or specific elements
    // If details/chapters also fail, these would need API overrides too.

    override val seriesTitleSelector = "h1.judul-komik" // (Original selector, might get title from meta/server-render)
    override val seriesThumbnailSelector = ".gambar-kecil img" // (Original selector)
    override val seriesGenreSelector = ".genre-komik a" // (Original selector)
    override val seriesAuthorSelector = ".keterangan-komik:contains(author) span" // (Original selector)
    override val seriesArtistSelector = ".keterangan-komik:contains(artist) span" // (Original selector)

    override fun chapterListSelector() = ".list-chapter a" // (Original selector)

    override fun chapterFromElement(element: Element) = SChapter.create().apply { // (Original method)
        name = element.selectFirst(".nomer-chapter")!!.text()
        date_upload = element.selectFirst(".tgl-chapter")?.text().parseChapterDate()
        setUrlWithoutDomain(element.absUrl("href"))
    }

    // Remove hasProjectPage unless specifically confirmed it works via API
    // override val hasProjectPage = true

    // ============================== Data Classes for API ==============================
    @Serializable
    data class ApiResponse<T>(
        val success: Boolean,
        val message: String? = null,
        val data: T? = null,
    )

    @Serializable
    data class MangaDto(
        val id: Int,
        val title: String,
        val slug: String,
        val cover: String? = null,
        val views: Int? = null,
        val rating: Float? = null,
        val status: String? = null,
        val genres: List<GenreDto>? = null,
        val chapters: List<ChapterDto>? = null,
        val rank: Int? = null,
        val views_current: Int? = null,
    )

    @Serializable
    data class GenreDto(
        val name: String,
        val slug: String,
    )

    @Serializable
    data class ChapterDto(
        val number: String,
        val slug: String,
        val date: Long,
    )
}
