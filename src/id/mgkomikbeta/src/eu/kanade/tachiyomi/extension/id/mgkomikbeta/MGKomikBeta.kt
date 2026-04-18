package eu.kanade.tachiyomi.extension.id.mgkomikbeta

import eu.kanade.tachiyomi.multisrc.natsuid.Manga
import eu.kanade.tachiyomi.multisrc.natsuid.NatsuId
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parseBodyFragment(response.body.string(), baseUrl)
        val slugs = document.select("div > a[href*=/komik/]:has(> img)").map {
            it.absUrl("href").toHttpUrl().pathSegments[1]
        }.ifEmpty {
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
        if (
            httpUrl.host == baseUrl.toHttpUrl().host &&
            httpUrl.pathSegments.size >= 2 &&
            httpUrl.pathSegments[0] == "komik"
        ) {
            val slug = httpUrl.pathSegments[1]
            val url = "$baseUrl/wp-json/wp/v2/manga".toHttpUrl().newBuilder()
                .addQueryParameter("slug[]", slug)
                .addQueryParameter("_embed", null)
                .build()

            return client.newCall(GET(url, headers))
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
            "$baseUrl${manga.url}".toHttpUrl().pathSegments[1]
        }

        return "$baseUrl/komik/$slug/"
    }

    override fun transformJsonResponse(responseBody: String): String {
        val jsonStart = responseBody.indexOfFirst { it == '{' || it == '[' }
        return if (jsonStart >= 0) responseBody.substring(jsonStart) else responseBody
    }
}
