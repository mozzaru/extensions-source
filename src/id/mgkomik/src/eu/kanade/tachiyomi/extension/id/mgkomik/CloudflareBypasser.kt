package eu.kanade.tachiyomi.extension.id.mgkomik

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import com.squareup.duktape.Duktape

class CloudflareBypasser(private val client: OkHttpClient) {

    fun solveChallenge(url: String): String? {
        val initialRequest = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(initialRequest).execute()
        val body = response.body?.string() ?: return null

        if (!body.contains("setTimeout(function()")) {
            return null // Tidak ada JS challenge
        }

        val jsCode = extractChallenge(body)
        val duktape = Duktape.create()
        val answer = duktape.evaluate(jsCode).toString()
        duktape.close()

        return answer
    }

    private fun extractChallenge(html: String): String {
        val doc = Jsoup.parse(html)
        val script = doc.select("script").html()
        return script.substringAfter("setTimeout(function(){").substringBefore("},")
    }
}
