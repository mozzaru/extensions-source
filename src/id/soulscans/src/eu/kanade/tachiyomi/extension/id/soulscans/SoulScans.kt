package eu.kanade.tachiyomi.extension.id.soulscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlin.time.Duration.Companion.seconds

@Source
abstract class SoulScans : HttpSource() {

    override val supportsLatest = true

    // The NestJS backend is served from the same origin as the SvelteKit site
    // (proxied at /api on port 3000 internally).
    private val apiUrl by lazy { "$baseUrl/api" }

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(5, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val scope = CoroutineScope(Dispatchers.IO)
    private var genresList: List<Pair<String, String>> = emptyList()
    private var fetchGenresAttempts = 0

    private fun launchIO(block: () -> Unit) = scope.launch { block() }

    private fun fetchGenres() {
        if (fetchGenresAttempts >= 3 && genresList.isEmpty()) return
        runCatching {
            client.newCall(GET("$apiUrl/genres", headers)).execute().use { res ->
                genresList = res.parseAs<List<GenreDto>>().map { it.name to it.slug }
            }
        }
        fetchGenresAttempts++
    }

    /**
     * Library migration helper.
     *
     * The old MangaThemesia site (soulscans.my.id) stored manga URLs as
     * `/manga/{slug}/`. The new platform uses `/comic/{slug}`. The slug is
     * identical on both sites, so extracting the last path segment lets manga
     * that were already in a user's library keep working after the move.
     */
    private fun SManga.slug(): String = url.substringBefore("?").trimEnd('/').substringAfterLast('/')

    // ============================== Popular =============================

    override fun popularMangaRequest(page: Int): Request = searchRequest(page, "", SortFilter().apply { state = 1 })

    override fun popularMangaParse(response: Response): MangasPage = parseSearchPage(response)

    // ============================== Latest ==============================

    /**
     * `/api/search?sort=latest` sorts by a series-level `updated_at` field
     * that can be bumped by unrelated events (e.g. view counts), so it does
     * not reliably reflect a new chapter release. `/api/feed` is chapter-level
     * and matches what the website's homepage "Semua Rilisan Terbaru" shows.
     *
     * The `page` query parameter is silently ignored by this endpoint, but
     * `limit` + `offset` work correctly for pagination.
     */
    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * FEED_PAGE_SIZE
        return GET("$apiUrl/feed?limit=$FEED_PAGE_SIZE&offset=$offset", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val items = response.parseAs<List<FeedItemDto>>()
        val mangas = items.filter { it.isComic }.distinctBy { it.slug }.map { it.toSManga() }
        return MangasPage(mangas, items.size == FEED_PAGE_SIZE)
    }

    // ============================== Search =============================

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        if (query.startsWith("http://") || query.startsWith("https://")) {
            val url = query.toHttpUrlOrNull()
            if (url != null && url.pathSegments.firstOrNull() == "comic" && url.pathSegments.size >= 2) {
                val slug = url.pathSegments[1]
                if (slug.isNotBlank()) {
                    val manga = SManga.create().apply { this.url = "/comic/$slug" }
                    return fetchMangaDetails(manga).map { MangasPage(listOf(it), false) }
                }
            }
            return Observable.just(MangasPage(emptyList(), false))
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()
        return searchRequest(page, query, sort, filters)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSearchPage(response)

    /**
     * build request to /api/search?type=COMIC. Semua filter (sort, order,
     * type, status, genre, and q are passed as server-side query parameters.
     */
    private fun searchRequest(
        page: Int,
        query: String,
        sort: SortFilter,
        filters: FilterList? = null,
    ): Request {
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

        return GET(url, headers)
    }

    private fun parseSearchPage(response: Response): MangasPage {
        val result = response.parseAs<SearchPageDto>()
        val mangas = result.data.map { it.toSManga() }
        return MangasPage(mangas, result.hasNextPage)
    }

    // ============================== Details =============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comic/${manga.slug()}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/series/comic/${manga.slug()}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<SeriesDetailDto>().toSManga()

    // ============================= Chapters =============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val detail = response.parseAs<SeriesDetailDto>()
        return detail.units.map { it.toSChapter(detail.slug) }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // =============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url = "/comic/{mangaSlug}/chapter/{chapterSlug}"
        val segments = chapter.url.trimEnd('/').split('/')
        val mangaSlug = segments.getOrNull(2).orEmpty()
        val chapterSlug = segments.lastOrNull().orEmpty()
        return GET("$apiUrl/series/comic/$mangaSlug/chapter/$chapterSlug", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pages = response.parseAs<ChapterResponseDto>().chapter.pages
        return pages.mapIndexed { index, page ->
            Page(index, imageUrl = page.imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters =============================

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }
        return getSoulScansFilterList(genresList)
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val FEED_PAGE_SIZE = 50
    }
}
