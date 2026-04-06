package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.util.Base64
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy

class OcrUrlInterceptor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val json: Json by injectLazy()

    fun getOcrData(url: String, html: String): String? {
        val vaultData = VAULT_REGEX.find(html)?.groupValues?.get(1) ?: return null

        val vault = vaultData.split(",").map { it.trim().removeSurrounding("\"") }
        if (vault.size < 5) return null

        val ch = try {
            String(Base64.decode(vault[0], Base64.DEFAULT))
        } catch (e: Exception) {
            return null
        }
        val tk = vault[1]
        val ts = vault[2].toLongOrNull() ?: return null
        val nc = vault[3]
        val gate = vault[4].replace("\\/", "/")

        val payload = OcrPayload(ch, tk, ts, nc)
        val requestBody = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)

        val ocrHeaders = headers.newBuilder()
            .add("Content-Type", "application/json")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", url)
            .add("Origin", url.substringBeforeLast("/manga/"))
            .build()

        val ocrResponse = try {
            client.newCall(POST(gate, ocrHeaders, requestBody)).execute()
        } catch (e: Exception) {
            return null
        }

        if (!ocrResponse.isSuccessful) {
            ocrResponse.close()
            return null
        }

        return ocrResponse.body.string()
    }

    @Serializable
    private data class OcrPayload(
        val ch: String,
        val tk: String,
        val ts: Long,
        val nc: String,
    )

    companion object {
        private val VAULT_REGEX = Regex("""const _0xvault = \[(.*?)];""", RegexOption.DOT_MATCHES_ALL)
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
