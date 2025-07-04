package eu.kanade.tachiyomi.extension.id.mgkomik

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

fun resolveCloudflare(client: OkHttpClient, url: String): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", CloudflareBypassHelper.USER_AGENT)
        .header("Referer", url)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("Cloudflare protection detected: ${response.code}")
        }
        return response.body?.string() ?: throw Exception("Empty response from Cloudflare-protected page")
    }
}

fun cloudflareBypassedDocument(client: OkHttpClient, url: String): Document {
    val response = resolveCloudflare(client, url)
    return Jsoup.parse(response, url)
}