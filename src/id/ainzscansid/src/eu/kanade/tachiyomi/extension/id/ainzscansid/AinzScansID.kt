package eu.kanade.tachiyomi.extension.id.ainzscansid

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import okhttp3.Headers
import java.util.Locale
import kotlin.random.Random

class AinzScansID : MangaThemesia("Ainz Scans ID", "https://ainzscans.net", "id", "/series") {

    override val hasProjectPage = true

    // Custom properties (no override)
    private val tagPrefix = "genres/"
    private val listUrl = "komik/"
    private val datePattern = "dd MMM yy"
    private val stylePage = ""
    private val sourceLocale: Locale = Locale.ENGLISH

    private val randomLength = Random.Default.nextInt(13, 21)
    private val randomString = generateRandomString(randomLength)

    // If MangaThemesia supports header customization via headersBuilder()
    override fun headersBuilder(): Headers.Builder {
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
