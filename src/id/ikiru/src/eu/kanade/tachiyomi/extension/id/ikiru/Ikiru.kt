package eu.kanade.tachiyomi.extension.id.ikiru

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
import keiyoushi.utils.stringOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Ikiru : KeiSource() {

    // support sugesttion komikku
    override val supportRelatedMangasBySearch = true

    // Chapter images come from a separate CDN host, so only the API host is throttled.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(8, 2.seconds) { it.host == apiUrlHost }

    private val apiUrl get() = baseUrl.toHttpUrl()

    private val apiUrlHost get() = apiUrl.host

    private fun api(path: String) = apiUrl.newBuilder().addPathSegments(path)

    private fun webUrl(vararg segments: String) = apiUrl.newBuilder()
        .apply { segments.forEach { addPathSegment(it) } }
        .build()
        .toString()

    override suspend fun getPopularManga(page: Int) = search(page, "", SortFilter.popular)

    override suspend fun getLatestUpdates(page: Int) = search(page, "", SortFilter.latest)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
        search(page, query, filters)

    private suspend fun search(page: Int, query: String, filters: FilterList): MangasPage {
        // The project listing is a separate endpoint that ignores every other filter.
        if (filters.firstInstanceOrNull<ProjectFilter>()?.state == true) {
            val url = api("api/public/manga/project")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .build()

            val result = client.get(url)
                .parseAs<ApiResponse<ProjectList>>()
                .data ?: error("Failed to fetch project list")

            return MangasPage(
                mangas = result.project.map { it.toSManga() },
                hasNextPage = page * PAGE_SIZE < result.total,
            )
        }

        val sort = filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()

        val url = api("api/public/library/search")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sortBy", sort.sort)
            .addQueryParameter("sort", if (sort.isAscending) "asc" else "desc")
            .apply {
                query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("query", it) }
                filters.firstInstanceOrNull<GenreFilter>()?.included.orEmpty()
                    .forEach { addQueryParameter("genre", it) }
                filters.firstInstanceOrNull<TypeFilter>()?.checked.orEmpty()
                    .forEach { addQueryParameter("type", it) }
                filters.firstInstanceOrNull<StatusFilter>()?.checked.orEmpty()
                    .forEach { addQueryParameter("status", it) }
                filters.firstInstanceOrNull<AuthorFilter>()?.state?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("author", it) }
                filters.firstInstanceOrNull<ArtistFilter>()?.state?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("artist", it) }
            }
            .build()

        val result = client.get(url).parseAs<ApiResponse<MangaList>>().data
            ?: error("Failed to fetch manga list")

        return MangasPage(
            mangas = result.mangas.map { it.toSManga() },
            hasNextPage = page * PAGE_SIZE < result.total,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != apiUrlHost) return null
        if (url.pathSegments.firstOrNull() != "manga") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null

        return client.get(api("api/public/manga/$slug"))
            .parseAs<ApiResponse<MangaDetail>>()
            .data
            ?.toSManga()
    }

    override fun getMangaUrl(manga: SManga) = webUrl("manga", manga.slug())

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.slug()

        // Details and chapters come from the same response, so parse it once and return both.
        val detail = client.get(api("api/public/manga/$slug"))
            .parseAs<ApiResponse<MangaDetail>>()
            .data ?: error("Manga not found")

        val chapterList = if (detail.hasMoreChapters) {
            client.get(api("api/public/manga/$slug/chapter").addQueryParameter("all", "true"))
                .parseAs<ApiResponse<ChapterList>>()
                .data
                ?.chapters
                ?.map { it.toSChapter(slug) }
                ?: error("Failed to fetch chapter list")
        } else {
            detail.toSChapterList(slug)
        }

        return SMangaUpdate(detail.toSManga(), chapterList)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = chapter.mangaSlug() ?: return baseUrl + chapter.url

        return webUrl("manga", slug, "chapter-${chapter.number()}")
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client
        .get(
            api("api/public/chapter/${chapter.number()}")
                .addQueryParameter("mangaSlug", chapter.mangaSlug() ?: error("Missing manga slug"))
                .build(),
        )
        .parseAs<ApiResponse<ChapterDetail>>()
        .data
        ?.toPages() ?: error("Failed to fetch chapter")

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client
        .get(api("api/user/genres").build())
        .parseAs<ApiResponse<GenreList>>()
        .data
        ?.allGenres
        .orEmpty()
        .sortedBy { it.name }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        ProjectFilter(),
        AuthorFilter(),
        ArtistFilter(),
        GenreFilter(data?.parseAs<List<Genre>>().orEmpty().map { it.name to it.id }),
    )

    companion object {
        private const val PAGE_SIZE = 24
    }
}

/**
 * [SManga.url] holds a bare slug, or a JSON blob for library entries written by the previous
 * WordPress-based version of this extension.
 */
private fun SManga.slug(): String = when {
    url.startsWith("{") -> url.parseAs<LegacyMangaUrl>().slug
    else -> url.trim('/').substringAfterLast('/')
}

/**
 * Chapters written by the previous versions store a path instead of the plain number the API keys
 * on: `/manga/one-piece/chapter-1194.417155/` (the trailing part is a salt) or the older flat
 * `/one-piece-chapter-1194/`.
 */
private fun SChapter.legacyChapterParts(): Pair<String, String>? {
    // Stored urls are relative, so resolve them against a dummy host to get decoded path segments.
    val segments = (url.toHttpUrlOrNull() ?: "https://localhost/${url.trimStart('/')}".toHttpUrlOrNull())
        ?.pathSegments
        ?.filter { it.isNotEmpty() }
        ?: return null

    if (segments.size >= 3 && segments[0] == "manga") {
        val number = segments[2].substringAfter("chapter-", "").takeIf { it.isNotEmpty() }
        if (number != null) return segments[1] to number.withoutSalt()
    }

    val flat = segments.singleOrNull() ?: return null
    val slug = flat.substringBeforeLast("-chapter-")
    if (slug.isEmpty() || !flat.contains("-chapter-")) return null

    return slug to flat.substringAfterLast("-chapter-").withoutSalt()
}

/**
 * Strips the numeric salt the old site appended to chapter numbers, keeping any decimal part
 * (`1194.417155` and `1053.5.95138` become `1194` and `1053.5`).
 */
private fun String.withoutSalt(): String {
    val parts = split('.')
    return when {
        parts.size >= 3 -> parts.dropLast(1).joinToString(".")
        // The site salts with 4-6 digits, while real decimal chapters use at most 2.
        parts.size == 2 && parts[1].length >= 4 -> parts[0]
        else -> this
    }
}

private fun SChapter.mangaSlug(): String? = memo["slug"]?.stringOrNull ?: legacyChapterParts()?.first

/**
 * [SChapter.url] holds the site's chapter number, which is what the chapter endpoints key on.
 */
private fun SChapter.number(): String = legacyChapterParts()?.second ?: url
