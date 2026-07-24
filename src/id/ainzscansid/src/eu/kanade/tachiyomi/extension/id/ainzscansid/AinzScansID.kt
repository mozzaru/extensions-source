package eu.kanade.tachiyomi.extension.id.ainzscansid

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class AinzScansID : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Referer", "$baseUrl/")
    }

    private val apiUrl = "https://api.ainzscans01.com/api"

    // ============================== Popular ===============================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "COMIC")
            .addQueryParameter("sort", "views")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url).parseAs<SearchResponseDto>().toMangasPage(page)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "COMIC")
            .addQueryParameter("sort", "latest")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url).parseAs<SearchResponseDto>().toMangasPage(page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "COMIC")
            .addQueryParameter("limit", "20")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("q", query)

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> urlBuilder.addQueryParameter("sort", filter.selectedValue())
                is OrderFilter -> urlBuilder.addQueryParameter("order", filter.selectedValue())
                is StatusFilter -> urlBuilder.addQueryParameter("status", filter.selectedValue())
                is GenreFilter -> urlBuilder.addQueryParameter("genre", filter.selectedValue())
                is TypeFilter -> urlBuilder.addQueryParameter("comic_type", filter.selectedValue())
                is ColorFilter -> urlBuilder.addQueryParameter("color_format", filter.selectedValue())
                is ReadingFilter -> urlBuilder.addQueryParameter("reading_format", filter.selectedValue())
                is TextFilter -> urlBuilder.addQueryParameter(filter.queryKey, filter.state)
                else -> {}
            }
        }

        return client.get(urlBuilder.build()).parseAs<SearchResponseDto>().toMangasPage(page)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${getNormalizedMangaUrl(manga)}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = SManga.create().apply {
            this.url = "/comic/$slug"
        }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga.apply {
            initialized = true
        }
    }

    // =========================== Manga Updates ============================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = client.get("$apiUrl/series${getNormalizedMangaUrl(manga)}").parseAs<SeriesDetailDto>()
        val updatedManga = dto.toSManga()
        val comicSlug = updatedManga.url.substringAfterLast("/")
        val updatedChapters = dto.units.map { it.toSChapter(comicSlug, dateFormat) }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // =============================== Pages ================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return client.get("$apiUrl/series${chapter.url}").parseAs<ChapterDetailDto>().toPageList()
    }

    // =============================== Filters ===============================
    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        OrderFilter(),
        StatusFilter(),
        GenreFilter(),
        TypeFilter(),
        ColorFilter(),
        ReadingFilter(),
        TextFilter("Author", "author"),
        TextFilter("Artist", "artist"),
        TextFilter("Publisher", "publisher"),
    )

    private fun getNormalizedMangaUrl(manga: SManga): String = if (manga.url.startsWith("/series/")) {
        "/comic/${manga.url.substringAfter("/series/").removeSuffix("/")}"
    } else {
        manga.url
    }

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
