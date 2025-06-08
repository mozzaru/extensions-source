package eu.kanade.tachiyomi.extension.id.kiryuu

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

class Kiryuu : MangaThemesia(
    name = "Kiryuu",
    baseUrl = "https://kiryuu01.com",
    lang = "id",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id")),
) {
    // Formerly "Kiryuu (WP Manga Stream)"
    override val id = 3639673976007021338

    private val tagPrefix = "genres/"
    private val listUrl = "komik/"
    private val datePattern = "dd MMM yy"
    private val stylePage = ""
    private val sourceLocale: Locale = Locale.ENGLISH

    private val randomLength = Random.Default.nextInt(13, 21)
    private val randomString = generateRandomString(randomLength)

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("X-Requested-With", randomString)
                .build()

            val response = chain.proceed(request)

            val mime = response.header("Content-Type")
            if (response.isSuccessful && mime == "application/octet-stream") {
                val type = IMG_CONTENT_TYPE.toMediaType()
                val body = response.body.bytes().toResponseBody(type)
                return@addInterceptor response.newBuilder()
                    .body(body)
                    .header("Content-Type", IMG_CONTENT_TYPE)
                    .build()
            }

            response
        }
        .rateLimit(4)
        .build()

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
    }

    override val hasProjectPage = true

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { charset.random() }
            .joinToString("")
    }
}

private const val IMG_CONTENT_TYPE = "image/jpeg"
