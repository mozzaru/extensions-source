package eu.kanade.tachiyomi.extension.id.soulscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class SoulScans : MangaThemesia("Soul Scans", "https://soulscans.my.id", "id") {

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
        add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
        add("X-Requested-With", randomString((1..20).random())) // added for webview, and removed in interceptor for normal use
    }

    override val hasProjectPage = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                val url = request.url.toString()
                removeAll("X-Requested-With")
                if (url.contains(".jpg") || url.contains(".png") || url.contains(".webp") || url.contains(".jpeg")) {
                    set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    set("Sec-Fetch-Dest", "image")
                    set("Sec-Fetch-Mode", "no-cors")
                }
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(3)
        .build()

    override fun searchMangaSelector() = ".listupd .bs .bsx:not(:has(.novelabel))"

    override val pageSelector = "div#readerarea img:not([src*='.gif'])"

    override fun Element.imgAttr(): String = sequenceOf("data-lazy-src", "data-src", "data-cfsrc", "data-original", "data-realsrc", "src")
        .map { attr("abs:$it") }
        .find { it.isNotEmpty() && !it.startsWith("data:") }
        ?: ""

    override fun Elements.imgAttr(): String = this.firstOrNull()?.imgAttr() ?: ""

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charPool.random() }.joinToString("")
    }
}
