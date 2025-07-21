package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(20, 5, TimeUnit.SECONDS)
        .build()

    private val bypasser by lazy { CloudflareBypasser(client) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml")
        .add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        .add("Referer", baseUrl)
        .add("X-Requested-With", randomString)

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { charset.random() }.joinToString("")
    }

    private val randomLength = Random.Default.nextInt(13, 21)
    private val randomString = generateRandomString(randomLength)

    override fun searchPage(page: Int): String = if (page > 1) "page/$page/" else ""
    override val mangaSubString = "komik"
    override fun searchMangaNextPageSelector() = "a.page.larger"
    override val chapterUrlSuffix = ""

    // Inject bypass ke popular/latest/search
    override fun popularMangaRequest(page: Int): Request {
        ensureCloudflareBypass("$baseUrl/manga/?page=$page")
        return super.popularMangaRequest(page)
    }

    private fun ensureCloudflareBypass(url: String) {
        val token = bypasser.solveChallenge(url)
        if (!token.isNullOrEmpty()) {
            headersBuilder().add("Cookie", "cf_clearance=$token")
        }
    }
}
