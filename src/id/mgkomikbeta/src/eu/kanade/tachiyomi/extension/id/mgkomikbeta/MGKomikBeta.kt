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
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.injectLazy

class MGKomikBeta :
    NatsuId(
        "MG Komik Beta",
        "id",
        "https://web.mgkomik.cc",
    ) {

    private val json: Json by injectLazy()

    override fun OkHttpClient.Builder.customizeClient() = rateLimit(4)

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
    }

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder().apply {
            set("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            set("Referer", page.url)
            removeAll("Upgrade-Insecure-Requests")
        }.build()

        return GET(page.imageUrl!!, newHeaders)
    }

    private var nonce: String? = null

    private fun fetchNonce(): String {
        if (nonce != null) return nonce!!

        // 1. Try to get it from the homepage first as it's often more reliable
        try {
            val response = client.newCall(GET(baseUrl, headers)).execute().body.string()
            val document = Jsoup.parse(response)
            nonce = document.selectFirst("input[name=search_nonce], input[name=nonce]")?.attr("value")

            if (nonce == null) {
                // Regex for common nonce patterns in scripts
                nonce = Regex("""["'](?:nonce|search_nonce|security|wp_manga_nonce)["']\s*:\s*["']([a-z0-9]{10})["']""").find(response)?.groupValues?.get(1)
            }
        } catch (_: Exception) {}

        if (nonce != null) return nonce!!

        // 2. Try AJAX endpoint
        try {
            val url = "$baseUrl/wp-admin/admin-ajax.php?type=search_form&action=get_nonce"
            val response = client.newCall(GET(url, headers.newBuilder().add("X-Requested-With", "XMLHttpRequest").build())).execute().body.string()

            nonce = Jsoup.parseBodyFragment(response).selectFirst("input[name=search_nonce]")?.attr("value")

            if (nonce == null && response.contains("{")) {
                val jsonStr = transformJsonResponse(response)
                val data = json.decodeFromString<kotlinx.serialization.json.JsonObject>(jsonStr)["data"]?.jsonPrimitive?.content
                if (data != null) {
                    nonce = Jsoup.parseBodyFragment(data).selectFirst("input[name=search_nonce]")?.attr("value")
                }
            }
        } catch (_: Exception) {}

        return nonce ?: throw Exception("Unable to get nonce")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/wp-admin/admin-ajax.php?action=advanced_search"
        val ajaxHeaders = headersBuilder().add("X-Requested-With", "XMLHttpRequest").build()
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
        val slugs = document.select("div > a:has(img)").mapNotNull {
            val href = it.attr("href")
            if (href.contains("/komik/") || href.contains("/manga/")) {
                href.removeSuffix("/").toHttpUrl().pathSegments.last()
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
            (httpUrl.pathSegments[0] == "komik" || httpUrl.pathSegments[0] == "manga")

        if (isMangaUrl) {
            val slug = httpUrl.pathSegments[1]
            val restUrl = "$baseUrl/wp-json/wp/v2/manga".toHttpUrl().newBuilder()
                .addQueryParameter("slug[]", slug)
                .addQueryParameter("_embed", null)
                .build()

            return client.newCall(GET(restUrl, headers))
                .asObservableSuccess()
                .map { response ->
                    val manga = response.body.string().let { transformJsonResponse(it) }
                        .let { json.decodeFromString<List<Manga>>(it) }[0]

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
}
