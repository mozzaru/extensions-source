package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.multisrc.natsuid.NatsuId
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.OkHttpClient
import okhttp3.Request

class Ikiru :
    NatsuId(
        "Ikiru",
        "id",
        "https://02.ikiru.wtf",
    ) {
    // Formerly "MangaTale"
    override val id = 1532456597012176985

    override fun OkHttpClient.Builder.customizeClient() = rateLimit(12, 3)

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "cross-site")
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun transformJsonResponse(responseBody: String): String {
        val jsonStart = responseBody.indexOfFirst { it == '{' || it == '[' }
        return if (jsonStart >= 0) responseBody.substring(jsonStart) else responseBody
    }
}
