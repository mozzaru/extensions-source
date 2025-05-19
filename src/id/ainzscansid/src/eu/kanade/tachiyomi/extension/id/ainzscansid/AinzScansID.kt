package eu.kanade.tachiyomi.extension.id.ainzscansid

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import okhttp3.OkHttpClient

class AinzScansID : MangaThemesia(
    "Ainz Scans ID",
    "https://ainzscans.net",
    "id",
    "/series"
) {
    override val hasProjectPage = true

    // Gunakan client dengan CloudflareInterceptor
    override val client: OkHttpClient = network.cloudflareClient
}