package eu.kanade.tachiyomi.extension.id.mgkomik

import okhttp3.Headers
import kotlin.random.Random
import java.util.Locale
import eu.kanade.tachiyomi.multisrc.madarav2.Madarav3

class MgKomik : Madarav3(
    name = "MgKomik",
    baseUrl = "https://id.mgkomik.cc",
    lang = "id",
    dateFormat = "dd MMM yy"
) {

    // Menyamakan behaviour dengan Kotatsu MadaraParser kamu
    override val tagPrefix: String = "genres/"
    override val listUrl: String = "komik/"
    override val stylePage: String = "" // sesuai file Kotatsu
    override val sourceLocale: Locale = Locale.ENGLISH

    // random X-Requested-With seperti di Kotatsu
    private val randomLength = Random.Default.nextInt(13, 21)
    private val randomString = generateRandomString(randomLength)

    override fun headersBuilder(): Headers.Builder {
        // panggil builder default dari Madara lalu tambahkan header custom
        return super.headersBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-User", "?1")
            .add("Upgrade-Insecure-Requests", "1")
            .add("X-Requested-With", randomString)
    }

    private fun generateRandomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { charset.random() }
            .joinToString("")
    }
}
