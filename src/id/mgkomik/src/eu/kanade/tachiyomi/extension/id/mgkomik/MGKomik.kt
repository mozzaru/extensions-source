package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madarav2.Madarav2
import eu.kanade.tachiyomi.multisrc.madarav2.Madarav2.Genre
import eu.kanade.tachiyomi.multisrc.madarav2.Madarav2.ContentRating
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MGKomik : Madarav2("MG Komik", "https://id.mgkomik.cc", "id") {

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(20, 5, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .add("X-Requested-With", randomString)

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { charset.random() }.joinToString("")
    }

    private val randomLength = Random.Default.nextInt(13, 21)
    private val randomString = generateRandomString(randomLength)

    override fun searchPage(page: Int): String = if (page > 1) "page/$page/" else ""
    override val mangaSubString = "komik"
    override val chapterUrlSuffix = ""

    override fun searchMangaNextPageSelector() = "a.page.larger"
    override fun latestUpdatesNextPageSelector(): String? = searchMangaNextPageSelector()
    override fun popularMangaNextPageSelector(): String? = searchMangaNextPageSelector()
    override fun searchMangaFromElement(element: org.jsoup.nodes.Element): SManga = popularMangaFromElement(element)
    override fun popularMangaSelector(): String = "div.page-item-detail"
    override fun latestUpdatesSelector(): String = "div.page-item-detail"
    override fun searchMangaSelector(): String = "div.page-item-detail"

    override fun getGenreList() = listOf(
        Genre("Action", "action"),
        Genre("Adventure", "adventure"),
        Genre("Comedy", "comedy"),
        Genre("Drama", "drama"),
        Genre("Fantasy", "fantasy"),
        Genre("Harem", "harem"),
        Genre("Isekai", "isekai"),
        Genre("Martial Arts", "martial-arts"),
        Genre("Romance", "romance"),
        Genre("School Life", "school-life"),
        Genre("Seinen", "seinen"),
        Genre("Shounen", "shounen"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Supernatural", "supernatural")
    )

    override fun getRatingList() = listOf(
        ContentRating("Safe", "safe"),
        ContentRating("NSFW", "nsfw")
    )
}