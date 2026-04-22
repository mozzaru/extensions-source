package eu.kanade.tachiyomi.extension.id.soulscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.Locale

class SoulScans : MangaThemesia("Soul Scans", "https://soulscans.my.id", "id") {

    override fun headersBuilder() = super.headersBuilder().apply {
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Upgrade-Insecure-Requests", "1")
        set("X-Requested-With", "com.android.chrome")
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val isPost = request.method == "POST"
            val isAjax = url.contains("admin-ajax.php") || url.contains("wp-json") || isPost
            val isImage = url.contains(".jpg") || url.contains(".png") || url.contains(".webp") || url.contains(".jpeg") || url.contains(".gif") || url.contains(".avif")
            val isSameOrigin = request.url.host == baseUrl.toHttpUrl().host
            val hasReferer = !request.header("Referer").isNullOrEmpty()

            val headers = request.headers.newBuilder().apply {
                if (isAjax) {
                    set("X-Requested-With", "XMLHttpRequest")
                    set("Accept", "application/json, text/javascript, */*; q=0.01")
                    set("Sec-Fetch-Dest", "empty")
                    set("Sec-Fetch-Mode", "cors")
                    set("Sec-Fetch-Site", if (isSameOrigin) "same-origin" else "cross-site")
                    set("Origin", "https://${request.url.host}")
                } else if (isImage) {
                    removeAll("X-Requested-With")
                    set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    set("Sec-Fetch-Dest", "image")
                    set("Sec-Fetch-Mode", "no-cors")
                    set("Sec-Fetch-Site", if (isSameOrigin) "same-origin" else "cross-site")
                } else {
                    removeAll("X-Requested-With")
                    set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    set("Sec-Fetch-Dest", "document")
                    set("Sec-Fetch-Mode", "navigate")
                    set(
                        "Sec-Fetch-Site",
                        if (hasReferer && isSameOrigin) {
                            "same-origin"
                        } else if (hasReferer) {
                            "cross-site"
                        } else {
                            "none"
                        },
                    )
                    set("Sec-Fetch-User", "?1")
                    set("Priority", "u=0, i")
                }
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(12, 3)
        .build()

    override val hasProjectPage = true

    override fun searchMangaSelector() = ".listupd .bs .bsx:not(:has(.novelabel))"

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst(seriesDetailsSelector)?.let { seriesDetails ->
            title = seriesDetails.selectFirst(seriesTitleSelector)?.text().orEmpty()
            artist = seriesDetails.selectFirst(seriesArtistSelector)?.ownText().removeEmptyPlaceholder()
            author = seriesDetails.selectFirst(seriesAuthorSelector)?.ownText().removeEmptyPlaceholder()
            description = seriesDetails.select(seriesDescriptionSelector).joinToString("\n") { it.text() }.trim()
            // Add alternative name to manga description
            val altName = seriesDetails.selectFirst(seriesAltNameSelector)?.ownText().takeIf { it.isNullOrBlank().not() }
            altName?.let {
                description = "$description\n\n$altNamePrefix$altName".trim()
            }
            val genres = seriesDetails.select(seriesGenreSelector).map { it.text() }.toMutableList()
            // Add series type (manga/manhwa/manhua/other) to genre
            seriesDetails.selectFirst(seriesTypeSelector)?.ownText().takeIf { it.isNullOrBlank().not() }?.let { genres.add(it) }
            genre = genres.map { genre ->
                genre.lowercase(Locale.forLanguageTag(lang)).replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.forLanguageTag(lang))
                    } else {
                        char.toString()
                    }
                }
            }
                .joinToString { it.trim() }

            status = seriesDetails.selectFirst(seriesStatusSelector)?.text().parseStatus()
            seriesDetails.select(seriesThumbnailSelector).firstOrNull()?.let { thumbnail_url = it.imgAttr() }
        }
    }

    override val pageSelector = "div#readerarea img:not([src*='.gif'])"
}
