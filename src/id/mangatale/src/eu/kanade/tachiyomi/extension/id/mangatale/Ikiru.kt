package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.multisrc.natsuid.NatsuId
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class Ikiru :
    NatsuId(
        "Ikiru",
        "id",
        "https://02.ikiru.wtf",
    ) {
    // Formerly "MangaTale"
    override val id = 1532456597012176985

    override fun OkHttpClient.Builder.customizeClient() = rateLimit(12, 3)
        .addInterceptor(::browserLikeInterceptor)

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")

    private fun browserLikeInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val builder = request.newBuilder()

        if (url.contains("wp-admin/admin-ajax.php") || url.contains("wp-json")) {
            builder.header("Accept", "application/json, text/javascript, */*; q=0.01")
            builder.header("X-Requested-With", "XMLHttpRequest")
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "same-origin")
        } else if (url.contains("wp-content/uploads") || url.contains("cdn.uqni.net") || url.contains("cdn.itachi.my.id")) {
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            builder.removeHeader("X-Requested-With")
            builder.header("Sec-Fetch-Dest", "image")
            builder.header("Sec-Fetch-Mode", "no-cors")
            builder.header("Sec-Fetch-Site", "cross-site")
        } else {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            builder.removeHeader("X-Requested-With")
            builder.header("Sec-Fetch-Dest", "document")
            builder.header("Sec-Fetch-Mode", "navigate")
            builder.header("Sec-Fetch-Site", "none")
            builder.header("Sec-Fetch-User", "?1")
            builder.header("Upgrade-Insecure-Requests", "1")
        }

        return chain.proceed(builder.build())
    }

    override fun transformJsonResponse(responseBody: String): String {
        val jsonStart = responseBody.indexOfFirst { it == '{' || it == '[' }
        return if (jsonStart >= 0) responseBody.substring(jsonStart) else responseBody
    }
}
