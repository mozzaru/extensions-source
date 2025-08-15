package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madarav2.Madarav5
import kotlin.random.Random
import okhttp3.Headers

class MGKomik : Madarav5(
    name = "MG Komik",
    baseUrl = "https://id.mgkomik.cc",
    lang = "id"
) {

    override val tagPrefix: String = "genres/"
    override val listUrl: String = "komik/"
    override val datePattern: String = "dd MMM yy"
    override val stylePage: String = ""

    private val randomLength: Int = Random.Default.nextInt(13, 21)
    private val randomString: String = generateRandomString(randomLength)

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .add("X-Requested-With", randomString)

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRSTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { charset.random() }
            .joinToString("")
    }

    override fun getGenreList(): List<Genre> = listOf(
        Genre("4-Koma", "4-koma"),
        Genre("Action", "action"),
        Genre("Adult", "adult"),
        Genre("Adventure", "adventure"),
        Genre("Comedy", "comedy"),
        Genre("Cooking", "cooking"),
        Genre("Crime", "crime"),
        Genre("Demons", "demons"),
        Genre("Drama", "drama"),
        Genre("Ecchi", "ecchi"),
        Genre("Fantasy", "fantasy"),
        Genre("Gender Bender", "gender-bender"),
        Genre("Harem", "harem"),
        Genre("Historical", "historical"),
        Genre("Horror", "horror"),
        Genre("Isekai", "isekai"),
        Genre("Josei", "josei"),
        Genre("Magic", "magic"),
        Genre("Martial Arts", "martial-arts"),
        Genre("Mature", "mature"),
        Genre("Mecha", "mecha"),
        Genre("Medical", "medical"),
        Genre("Military", "military"),
        Genre("Music", "music"),
        Genre("Mystery", "mystery"),
        Genre("One Shot", "one-shot"),
        Genre("Parody", "parody"),
        Genre("Police", "police"),
        Genre("Psychological", "psychological"),
        Genre("Romance", "romance"),
        Genre("School", "school"),
        Genre("Sci-Fi", "sci-fi"),
        Genre("Seinen", "seinen"),
        Genre("Shoujo", "shoujo"),
        Genre("Shoujo Ai", "shoujo-ai"),
        Genre("Shounen", "shounen"),
        Genre("Shounen Ai", "shounen-ai"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Sports", "sports"),
        Genre("Super Power", "super-power"),
        Genre("Supernatural", "supernatural"),
        Genre("Thriller", "thriller"),
        Genre("Tragedy", "tragedy"),
        Genre("Vampire", "vampire"),
        Genre("Webtoons", "webtoons"),
        Genre("Yaoi", "yaoi"),
        Genre("Yuri", "yuri")
    )
}
