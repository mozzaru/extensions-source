package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc", 
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US)
) {
    // ========================
    // 1. KONFIGURASI DASAR
    // ========================
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"

    // ========================
    // 2. CLIENT & HEADER
    // ========================
    // Gunakan globalClient dari NetworkHelper
    override val client = NetworkHelper.getInstance().globalClient.newBuilder()
        .rateLimit(9, 2) // Batasan request: 9 request per 2 detik
        .addInterceptor { chain ->
            val request = chain.request()
                .newBuilder()
                // Hapus header khusus WebView
                .removeHeader("X-Requested-With") 
                // Tambahkan header khusus MGKomik
                .header("Referer", baseUrl)
                .build()
            chain.proceed(request)
        }
        .build()

    // Header dasar (diwarisi dari Madara + tambahan)
    override fun headersBuilder() = super.headersBuilder().apply {
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Site", "same-origin")
        add("Accept-Language", "id-ID,id;q=0.9")
    }

    // ========================
    // 3. CLOUDFLARE HANDLING
    // ========================
    // Tidak perlu interceptor tambahan karena sudah ditangani NetworkHelper

    // ========================
    // 4. POPULAR & LATEST
    // ========================
    override fun popularMangaNextPageSelector() = ".wp-pagenavi span.current + a"

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/$mangaSubString/page/$page", headers)
    }

    // ========================
    // 5. SEARCH & FILTER
    // ========================
    override fun searchMangaSelector() = "${super.searchMangaSelector()}, .page-listing-item .page-item-detail"

    override fun searchMangaNextPageSelector() = "a.page.larger"

    // Filter Genre (tidak perlu perubahan)
    override fun getFilterList() = super.getFilterList()

    // ========================
    // 6. CHAPTER HANDLING
    // ========================
    override val chapterUrlSuffix = ""

    // ========================
    // 7. UTILITIES
    // ========================
    private fun randomString(length: Int): String {
        val charset = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charset.random() }.joinToString("")
    }
}