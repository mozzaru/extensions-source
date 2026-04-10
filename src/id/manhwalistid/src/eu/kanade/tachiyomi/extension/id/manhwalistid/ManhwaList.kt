package eu.kanade.tachiyomi.extension.id.manhwalistid

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient

class ManhwaList :
    MangaThemesia(
        "Manhwa List",
        "https://manhwalist02.site",
        "id",
    ) {
    override fun headersBuilder() = super.headersBuilder()
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Upgrade-Insecure-Requests", "1")
        // Added for WebView, and removed in interceptor for normal use
        .add("X-Requested-With", randomString((1..20).random()))

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(4)
        .build()

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charPool.random() }.joinToString("")
    }
}
