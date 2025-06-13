package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

class MGKomik : MangaThemesia(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    dateFormat = SimpleDateFormat("dd MMM yy", Locale.US),
) {

    // Random X-Requested-With value
    private val randomLength = Random.nextInt(13, 21)
    private val randomString = generateRandomString(randomLength)

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder()
                .removeAll("X-Requested-With")
                .build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(9, 2)
        .build()

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Sec-Fetch-User", "?1")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomString)
    }

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { charset.random() }.joinToString("")
    }
}
