package eu.kanade.tachiyomi.extension.id.mgkomik

import java.util.UUID

object CloudflareBypassHelper {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

    fun generateXRequestedWith(): String {
        return UUID.randomUUID().toString().replace("-", "").take(12)
    }
}