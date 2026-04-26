package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.util.Base64
import okhttp3.Headers

class OcrUrlInterceptor(private val headers: Headers) {

    data class OcrRequest(
        val url: String,
        val body: String,
        val headers: Headers,
    )

    data class Vault(
        val ch: String,
        val tk: String,
        val ts: String,
        val nc: String,
        val url: String,
        val ref: String,
    )

    fun getOcrRequest(html: String, referer: String): OcrRequest? {
        val vault = extractVault(html) ?: return null

        val body = """{"ch":"${vault.ch}","tk":"${vault.tk}","ts":${vault.ts},"nc":"${vault.nc}"}"""

        val ocrHeaders = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("X-Gate-Token", vault.tk)
            .add("X-Gate-Nonce", vault.nc)
            .add("X-Gate-Timestamp", vault.ts)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Cache-Control", "no-cache")
            .add("Referer", referer)
            .apply {
                headers["User-Agent"]?.let { add("User-Agent", it) }
            }
            .build()

        // The website uses a decoy body in the actual fetch call
        val decoyBody = """{"cid":"${Base64.encodeToString(vault.ch.toByteArray(), Base64.NO_WRAP)}","ref":"${vault.ref}"}"""

        return OcrRequest(vault.url, decoyBody, ocrHeaders)
    }

    private fun extractVault(html: String): Vault? {
        val vaultMatch = VAULT_REGEX.find(html) ?: return null
        val parts = vaultMatch.groupValues[1]
            .split(",")
            .map { it.trim().trim('"', '\'') }

        if (parts.size < 6) return null

        return try {
            Vault(
                ch = String(Base64.decode(parts[0], Base64.DEFAULT)),
                tk = parts[1],
                ts = parts[2],
                nc = parts[3],
                url = parts[4].replace("\\/", "/"),
                ref = parts[5],
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val VAULT_REGEX = Regex("""const _0xvault\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
    }
}
