package eu.kanade.tachiyomi.extension.id.mgkomikbeta

import eu.kanade.tachiyomi.multisrc.natsuid.GenreExclusion
import eu.kanade.tachiyomi.multisrc.natsuid.GenreFilter
import eu.kanade.tachiyomi.multisrc.natsuid.GenreInclusion
import eu.kanade.tachiyomi.multisrc.natsuid.Manga
import eu.kanade.tachiyomi.multisrc.natsuid.NatsuId
import eu.kanade.tachiyomi.multisrc.natsuid.SortFilter
import eu.kanade.tachiyomi.multisrc.natsuid.StatusFilter
import eu.kanade.tachiyomi.multisrc.natsuid.TypeFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.injectLazy
import kotlin.random.Random

class MGKomikBeta :
    NatsuId(
        "MG Komik Beta",
        "id",
        "https://web.mgkomik.cc",
    ) {

    private val json: Json by injectLazy()

    override fun OkHttpClient.Builder.customizeClient() = rateLimit(2)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("admin-ajax.php") || url.contains("wp-json")) {
                val newRequest = request.newBuilder()
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", SortFilter.popular)
    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(page, "", SortFilter.latest)

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder().apply {
            set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            set("Referer", page.url)
            removeAll("Upgrade-Insecure-Requests")
        }.build()

        return GET(page.imageUrl!!, newHeaders)
    }

    private var nonce: String? = null

    @Synchronized
    private fun fetchNonce(): String {
        if (nonce != null) return nonce!!

        val ajaxHeaders = headersBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .build()

        // 1. Try various AJAX actions
        val actions = listOf(
            "get_nonce&type=search_form",
            "get_nonce",
            "natsu_get_nonce",
            "get_search_nonce",
            "search_nonce",
            "natsu_search_nonce",
        )

        for (action in actions) {
            try {
                val url = "$baseUrl/wp-admin/admin-ajax.php?action=$action"
                val response = client.newCall(GET(url, ajaxHeaders)).execute().body.string()
                nonce = Jsoup.parseBodyFragment(response).selectFirst("input[name$=_nonce], input[name$=security], input[name=search_nonce]")?.attr("value")
                    ?: Regex("""["'](?:nonce|security|natsu_nonce)["']\s*[:=]\s*["']([a-z0-9]{10})["']""").find(response)?.groupValues?.get(1)
                if (nonce != null) break
            } catch (_: Exception) {}
        }

        if (nonce != null) return nonce!!

        // 2. Try to find nonce in the homepage HTML
        try {
            val response = client.newCall(GET(baseUrl, headers)).execute().body.string()
            nonce = Regex("""["'](?:nonce|security|natsu_nonce)["']\s*[:=]\s*["']([a-z0-9]{10})["']""").find(response)?.groupValues?.get(1)
                ?: Jsoup.parse(response).selectFirst("input[name$=_nonce], input[name$=security], input[name=search_nonce]")?.attr("value")
        } catch (_: Exception) {}

        return nonce ?: throw Exception("Unable to get nonce (Beta)")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/wp-admin/admin-ajax.php?action=advanced_search"
        val ajaxHeaders = headersBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .build()

        val body = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            addFormDataPart("nonce", fetchNonce())
            filters.firstInstanceOrNull<GenreInclusion>()?.selected.also {
                addFormDataPart("inclusion", it ?: "OR")
            }
            filters.firstInstanceOrNull<GenreExclusion>()?.selected.also {
                addFormDataPart("exclusion", it ?: "OR")
            }
            addFormDataPart("page", page.toString())
            val genres = filters.firstInstanceOrNull<GenreFilter>()
            genres?.included.orEmpty().also {
                addFormDataPart("genre", it.toJsonString())
            }
            genres?.excluded.orEmpty().also {
                addFormDataPart("genre_exclude", it.toJsonString())
            }
            addFormDataPart("author", "[]")
            addFormDataPart("artist", "[]")
            addFormDataPart("project", "0")
            filters.firstInstanceOrNull<TypeFilter>()?.checked.orEmpty().also {
                addFormDataPart("type", it.toJsonString())
            }
            filters.firstInstanceOrNull<StatusFilter>()?.checked.orEmpty().also {
                addFormDataPart("status", it.toJsonString())
            }
            val sort = filters.firstInstance<SortFilter>()
            addFormDataPart("order", if (sort.isAscending) "asc" else "desc")
            addFormDataPart("orderby", sort.sort)
            addFormDataPart("query", query.trim())
        }.build()

        return POST(url, ajaxHeaders, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        if (body.contains("Result not found") || body.contains("tidak ditemukan")) {
            return MangasPage(emptyList(), false)
        }

        val document = Jsoup.parseBodyFragment(body, baseUrl)
        val slugs = document.select("a").mapNotNull {
            val href = it.attr("abs:href")
            if (href.contains("/komik/") || href.contains("/manga/") || href.contains("/series/")) {
                href.removeSuffix("/").substringAfterLast("/")
            } else {
                null
            }
        }.distinct().ifEmpty {
            return MangasPage(emptyList(), false)
        }

        val url = "$baseUrl/wp-json/wp/v2/manga".toHttpUrl().newBuilder().apply {
            slugs.forEach { slug ->
                addQueryParameter("slug[]", slug)
            }
            addQueryParameter("per_page", "${slugs.size + 1}")
            addQueryParameter("_embed", null)
        }.build()

        val details = client.newCall(GET(url, headers)).execute()
            .body.string().let { transformJsonResponse(it) }
            .let { json.decodeFromString<List<Manga>>(it) }
            .filterNot { manga ->
                manga.embedded.getTerms("type").contains("Novel")
            }
            .associateBy { it.slug }

        val mangas = slugs.mapNotNull { slug ->
            details[slug]?.toSManga()
        }

        val hasNextPage = document.selectFirst("button:has(svg)") != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith("https://")) {
        deepLink(query)
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    private fun deepLink(url: String): Observable<MangasPage> {
        val httpUrl = url.toHttpUrl()
        val isMangaUrl = httpUrl.host == baseUrl.toHttpUrl().host &&
            httpUrl.pathSegments.size >= 2 &&
            (httpUrl.pathSegments[0] == "komik" || httpUrl.pathSegments[0] == "manga" || httpUrl.pathSegments[0] == "series")

        if (isMangaUrl) {
            val slug = httpUrl.pathSegments[1]
            val restUrl = "$baseUrl/wp-json/wp/v2/manga".toHttpUrl().newBuilder()
                .addQueryParameter("slug[]", slug)
                .addQueryParameter("_embed", null)
                .build()

            return client.newCall(GET(restUrl, headers))
                .asObservableSuccess()
                .map { response ->
                    val mangaList = response.body.string().let { transformJsonResponse(it) }
                        .let { json.decodeFromString<List<Manga>>(it) }

                    if (mangaList.isEmpty()) throw Exception("Manga not found")
                    val manga = mangaList[0]

                    if (manga.embedded.getTerms("type").contains("Novel")) {
                        throw Exception("Novels are not supported")
                    }

                    MangasPage(listOf(manga.toSManga()), false)
                }
        }

        return Observable.error(Exception("Unsupported url"))
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = if (manga.url.startsWith("{")) {
            manga.url.parseAs<eu.kanade.tachiyomi.multisrc.natsuid.MangaUrl>().slug
        } else {
            val httpUrl = "$baseUrl${manga.url}".toHttpUrl()
            if (httpUrl.pathSegments.size >= 2) httpUrl.pathSegments[1] else httpUrl.pathSegments[0]
        }

        return "$baseUrl/komik/$slug/"
    }

    override fun transformJsonResponse(responseBody: String): String {
        val jsonStart = responseBody.indexOfFirst { it == '{' || it == '[' }
        return if (jsonStart >= 0) responseBody.substring(jsonStart) else responseBody
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = getMangaIdBeta(manga)
        val appendId = !manga.url.startsWith("{")

        return GET("$baseUrl/wp-json/wp/v2/manga/$id?_embed#$appendId", headers)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = getMangaIdBeta(manga)

        val url = "$baseUrl/wp-admin/admin-ajax.php".toHttpUrl().newBuilder()
            .addQueryParameter("manga_id", id)
            .addQueryParameter("page", "${Random.nextInt(99, 9999)}")
            .addQueryParameter("action", "chapter_list")
            .build()

        return GET(url, headers)
    }

    private val descriptionIdRegex = Regex("""ID: (\d+)""")

    private fun getMangaIdBeta(manga: SManga): String {
        if (manga.url.startsWith("{")) {
            return manga.url.parseAs<eu.kanade.tachiyomi.multisrc.natsuid.MangaUrl>().id.toString()
        }
        val descId = descriptionIdRegex.find(manga.description.orEmpty())?.groupValues?.get(1)
        if (descId != null) return descId

        return client.newCall(GET(getMangaUrl(manga), headers)).execute().use { response ->
            val document = response.asJsoup()
            document.selectFirst("link[rel=shortlink]")?.attr("href")?.substringAfter("?p=")
                ?: document.selectFirst("#gallery-list")?.attr("hx-get")?.substringAfter("manga_id=")?.substringBefore("&")
                ?: document.select("script").mapNotNull {
                    Regex("""manga_id\s*[:=]\s*["']?(\d+)""").find(it.data())?.groupValues?.get(1)
                }.firstOrNull()
                ?: throw Exception("Could not find manga ID")
        }
    }
}
