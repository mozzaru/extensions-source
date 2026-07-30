package eu.kanade.tachiyomi.multisrc.natsuid

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import org.jsoup.Jsoup
import kotlin.random.Random
import kotlin.time.Instant

// https://themesinfo.com/natsu_id-theme-wordpress-c8x1c Wordpress Theme Author "Dzul Qurnain"
abstract class NatsuId : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage =
        searchManga(page, "", SortFilter.popular)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        searchManga(page, "", SortFilter.latest)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
        searchManga(page, query, filters)

    private suspend fun searchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val body = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            addFormDataPart("nonce", getNonce())
            addFormDataPart("inclusion", filters.firstInstanceOrNull<GenreInclusion>()?.selected ?: "OR")
            addFormDataPart("exclusion", filters.firstInstanceOrNull<GenreExclusion>()?.selected ?: "OR")
            addFormDataPart("page", page.toString())
            val genres = filters.firstInstanceOrNull<GenreFilter>()
            addFormDataPart("genre", genres?.included.orEmpty().toJsonString())
            addFormDataPart("genre_exclude", genres?.excluded.orEmpty().toJsonString())
            addFormDataPart("author", "[]")
            addFormDataPart("artist", "[]")
            addFormDataPart("project", if (filters.firstInstanceOrNull<ProjectFilter>()?.state == true) "1" else "0")
            addFormDataPart("type", filters.firstInstanceOrNull<TypeFilter>()?.checked.orEmpty().toJsonString())
            addFormDataPart("status", filters.firstInstanceOrNull<StatusFilter>()?.checked.orEmpty().toJsonString())
            val sort = filters.firstInstance<SortFilter>()
            addFormDataPart("order", if (sort.isAscending) "asc" else "desc")
            addFormDataPart("orderby", sort.sort)
            addFormDataPart("query", query.trim())
        }.build()
        val document = client.post("$baseUrl/wp-admin/admin-ajax.php?action=advanced_search", body = body).use {
            Jsoup.parseBodyFragment(it.body.string(), baseUrl)
        }
        val slugs = document.select("div > a[href*=/manga/]:has(> img)").mapNotNull {
            it.absUrl("href").toHttpUrl().pathSegments.getOrNull(1)
        }
        if (slugs.isEmpty()) return MangasPage(emptyList(), false)

        val mangas = mangaList(slugs)
            .filterNot { it.embedded.getTerms("type").contains("Novel") }
            .associateBy { it.slug }
            .let { details -> slugs.mapNotNull { details[it]?.toSManga() } }
        return MangasPage(mangas, document.selectFirst("button:has(svg)") != null)
    }

    private var nonce: String? = null
    private val nonceMutex = Mutex()

    private suspend fun getNonce(): String = nonceMutex.withLock {
        nonce ?: client.get("$baseUrl/wp-admin/admin-ajax.php?type=search_form&action=get_nonce").use {
            Jsoup.parseBodyFragment(it.body.string())
                .selectFirst("input[name=search_nonce]")
                ?.attr("value")
                ?.takeIf(String::isNotEmpty)
                ?.also { nonce = it }
                ?: throw Exception("Unable to get nonce")
        }
    }

    private suspend fun mangaList(slugs: List<String>): List<Manga> {
        val url = "$baseUrl/wp-json/wp/v2/manga".toHttpUrl().newBuilder().apply {
            slugs.forEach { addQueryParameter("slug[]", it) }
            addQueryParameter("per_page", "${slugs.size + 1}")
            addQueryParameter("_embed", null)
        }.build()
        return try {
            client.get(url).parseAs(transform = ::transformJsonResponse)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override val supportRelatedMangasBySearch = true

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.getOrNull(0) != "manga") return null

        val slug = url.pathSegments.getOrNull(1) ?: return null
        val manga = mangaList(listOf(slug)).firstOrNull() ?: return null
        if (manga.embedded.getTerms("type").contains("Novel")) throw Exception("Novels are not supported")
        return manga.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.memo["slug"]?.string ?: legacySlug(manga)
        return "$baseUrl/manga/$slug/"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.toIntOrNull() ?: legacyMangaId(manga)
        if (id == null) {
            val document = client.get(getMangaUrl(manga)).use { it.asJsoup() }
            val recoveredId = document.selectFirst("#gallery-list")!!.attr("hx-get")
                .substringAfter("manga_id=").substringBefore("&")
            val updatedManga = if (fetchDetails) fetchManga(recoveredId) else manga
            val updatedChapters = if (fetchChapters) getChapters(recoveredId) else chapters
            return SMangaUpdate(updatedManga, updatedChapters)
        }
        return updateManga(manga, chapters, id.toString(), fetchDetails, fetchChapters)
    }

    private suspend fun updateManga(
        manga: SManga,
        chapters: List<SChapter>,
        id: String,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val updatedManga = async { if (fetchDetails) fetchManga(id) else manga }
        val updatedChapters = async { if (fetchChapters) getChapters(id) else chapters }
        SMangaUpdate(updatedManga.await(), updatedChapters.await())
    }

    private suspend fun fetchManga(id: String): SManga = client.get("$baseUrl/wp-json/wp/v2/manga/$id?_embed")
        .parseAs<Manga>(transform = ::transformJsonResponse)
        .toSManga()

    private fun legacyMangaId(manga: SManga): Int? = manga.url.takeIf { it.startsWith("{") }
        ?.parseAs<MangaUrl>()
        ?.id

    private fun legacySlug(manga: SManga): String = manga.url.takeIf { it.startsWith("{") }
        ?.parseAs<MangaUrl>()
        ?.slug
        ?: "$baseUrl${manga.url}".toHttpUrl().pathSegments[1]

    protected open fun chapterListPage(): String = Random.nextInt(99, 9999).toString()

    private suspend fun getChapters(id: String): List<SChapter> {
        val url = "$baseUrl/wp-admin/admin-ajax.php".toHttpUrl().newBuilder()
            .addQueryParameter("manga_id", id)
            .addQueryParameter("page", chapterListPage())
            .addQueryParameter("action", "chapter_list")
            .build()
        val document = client.get(url).use { Jsoup.parseBodyFragment(it.body.string(), baseUrl) }
        return document.select(chapterListSelector).map {
            SChapter.create().apply {
                setUrlWithoutDomain(it.absUrl("href"))
                name = it.selectFirst(chapterNameSelector)!!.ownText()
                date_upload = it.selectFirst(chapterDateSelector)
                    ?.attr(chapterDateAttribute)
                    ?.let { date -> Instant.parseOrNull(date)?.toEpochMilliseconds() }
                    ?: 0L
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).use { response ->
        response.asJsoup().select(pageListSelector).mapIndexed { index, image ->
            Page(index, imageUrl = image.absUrl("src"))
        }
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get(
        "$baseUrl/wp-json/wp/v2/genre?per_page=100&page=1&orderby=count&order=desc",
    ).parseAs(transform = ::transformJsonResponse)

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(SortFilter(), TypeFilter(), StatusFilter(), ProjectFilter())
        val genres = data?.parseAs<List<Term>>().orEmpty()
        if (genres.isNotEmpty()) filters += listOf(GenreFilter(genres.map { it.name to it.slug }), GenreInclusion(), GenreExclusion())
        return FilterList(filters)
    }

    protected open val chapterListSelector = "div a:has(time)"
    protected open val chapterNameSelector = "span"
    protected open val chapterDateSelector = "time"
    protected open val chapterDateAttribute = "datetime"
    protected open val pageListSelector = "main .relative section > img"
    protected open fun transformJsonResponse(responseBody: String): String = responseBody
}
