package eu.kanade.tachiyomi.extension.id.soulscans

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class SoulScans : KeiSource() {

    // support sugesttion komikku
    override val supportRelatedMangasBySearch = true

    private val apiUrl get() = "$baseUrl/api"

    private val apiHost get() = baseUrl.toHttpUrl().host

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(5, 1.seconds) { it.host == apiHost }
    }

    private fun SManga.slug(): String = url.substringBefore("?").trimEnd('/').substringAfterLast('/')

    // ============================== Popular =============================

    override suspend fun getPopularManga(page: Int): MangasPage = getMangaList(page, "", SortFilter().apply { state = 1 })

    // ============================== Latest ==============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val end = page * PAGE_SIZE
        val limit = minOf(end + 1, MAX_HOME_LATEST_ITEMS)
        val url = "$apiUrl/comic/home-sections".toHttpUrl().newBuilder()
            .addQueryParameter("updateLimit", limit.toString())
            .addQueryParameter("sections", "latest_comic_updates")
            .build()
        val updates = client.get(url, cacheControl = CacheControl.FORCE_NETWORK)
            .parseAs<HomeSectionsDto>()
            .latestComicUpdates
        val mangas = updates.drop((page - 1) * PAGE_SIZE)
            .take(PAGE_SIZE)
            .map { it.toSManga() }
        return MangasPage(mangas, updates.size > end)
    }

    // ============================== Search =============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()
        return getMangaList(page, query, sort, filters)
    }

    private suspend fun getMangaList(
        page: Int,
        query: String,
        sort: SortFilter,
        filters: FilterList? = null,
    ): MangasPage {
        val (sortBy, orderBy) = sort.toQuery()
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "COMIC")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sortBy)
            .addQueryParameter("order", orderBy)
            .apply {
                if (query.isNotBlank()) addQueryParameter("q", query)

                filters?.firstInstanceOrNull<ProjectFilter>()?.takeIf { it.state }
                    ?.let { addQueryParameter("project_only", "1") }

                filters?.firstInstanceOrNull<TypeFilter>()?.selectedValue()
                    ?.let { addQueryParameter("comic_type", it) }

                filters?.firstInstanceOrNull<StatusFilter>()?.selectedValue()
                    ?.let { addQueryParameter("status", it) }

                filters?.firstInstanceOrNull<GenreFilter>()?.state
                    ?.filter { it.state }
                    ?.forEach { addQueryParameter("genre", it.slug) }
            }
            .build()

        val result = client.get(url, cacheControl = CacheControl.FORCE_NETWORK)
            .parseAs<SearchPageDto>()
        return MangasPage(result.data.map { it.toSManga() }, result.hasNextPage)
    }

    // ============================== Details =============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "comic") {
            return null
        }

        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return getSeriesDetail(slug).toSManga().apply { initialized = true }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comic/${manga.slug()}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail = getSeriesDetail(manga.slug())
        return SMangaUpdate(
            manga = detail.toSManga(),
            chapters = detail.units.map { it.toSChapter(detail.slug) },
        )
    }

    private suspend fun getSeriesDetail(slug: String): SeriesDetailDto = client.get("$apiUrl/series/comic/$slug").parseAs()

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // =============================== Pages ==============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = chapter.url.trimEnd('/').split('/')
        val mangaSlug = segments.getOrNull(2).orEmpty()
        val chapterSlug = segments.lastOrNull().orEmpty()
        val pages = client.get("$apiUrl/series/comic/$mangaSlug/chapter/$chapterSlug")
            .parseAs<ChapterResponseDto>()
            .chapter
            .pages
        return pages.mapIndexed { index, page -> Page(index, imageUrl = page.imageUrl) }
    }

    // ============================== Filters =============================

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/genres").parseAs<List<GenreDto>>().toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreDto>>()?.map { it.name to it.slug }.orEmpty()
        return getSoulScansFilterList(genres)
    }

    companion object {
        private const val PAGE_SIZE = 18
        private const val MAX_HOME_LATEST_ITEMS = 720
    }
}
