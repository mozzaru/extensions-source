package eu.kanade.tachiyomi.extension.id.soulscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.Locale

class SoulScans : MangaThemesia("Soul Scans", "https://soulscans.my.id", "id") {

    override fun headersBuilder() = super.headersBuilder().apply {
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Upgrade-Insecure-Requests", "1")
        set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36")
        set("X-Requested-With", "com.android.chrome")
    }

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val headers = request.headers.newBuilder().apply {
                val isPost = request.method == "POST"
                val isAjax = url.contains("admin-ajax.php") || url.contains("wp-json") || isPost

                if (isAjax) {
                    set("X-Requested-With", "XMLHttpRequest")
                    set("Sec-Fetch-Dest", "empty")
                    set("Sec-Fetch-Mode", "cors")
                    set("Sec-Fetch-Site", "same-origin")
                } else if (url.contains(".jpg") || url.contains(".png") || url.contains(".webp") || url.contains(".jpeg") || url.contains(".gif") || url.contains(".avif")) {
                    removeAll("X-Requested-With")
                    set("Sec-Fetch-Dest", "image")
                    set("Sec-Fetch-Mode", "no-cors")
                    set("Sec-Fetch-Site", "cross-site")
                } else {
                    removeAll("X-Requested-With")
                    set("Sec-Fetch-Dest", "document")
                    set("Sec-Fetch-Mode", "navigate")
                    set("Sec-Fetch-Site", "none")
                }
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(3)
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
