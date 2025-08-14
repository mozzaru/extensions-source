package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"

    // ---------------- Browser-like headers ----------------
    private val randomLength = Random.Default.nextInt(13, 21)
    private fun randomString(length: Int): String {
        val charset = "HALOGaES.BCDFHIJKMNPQRTUVWXYZ.bcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { charset.random() }.joinToString("")
    }

    override fun headersBuilder() = super.headersBuilder().apply {
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", "same-origin")
        set("Sec-Fetch-User", "?1")
        set("Upgrade-Insecure-Requests", "1")
        set("Referer", "$baseUrl/")
        // optional: kirim X-Requested-With random seperti Kotatsu (boleh) — jangan tambahkan interceptor yang menghapusnya
        set("X-Requested-With", randomString(randomLength))
    }

    // ---------------- gunakan cloudflareClient tanpa menghapus header ----------------
    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(9, 2)
        .build()

    // ---------------- warm cloudflare cookie supaya challenge lebih kecil ----------------
    init {
        launchIO {
            try {
                network.cloudflareClient.newCall(GET(baseUrl, headers)).execute().use { /* simpan cookie */ }
            } catch (_: Exception) { /* ignore */ }
        }
    }

    // ========================= Popular (null-safe) =========================
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        // safer parsing — jangan pakai "!!"
        element.selectFirst("div.item-thumb a")?.let { a ->
            manga.setUrlWithoutDomain(a.attr("abs:href"))
            manga.title = a.attr("title").ifBlank { a.text() }
        } ?: run {
            element.selectFirst("a")?.let { a ->
                manga.setUrlWithoutDomain(a.attr("abs:href"))
                manga.title = a.attr("title").ifBlank { a.text() }
            }
        }
        element.selectFirst("img")?.let {
            manga.thumbnail_url = imageFromElement(it)
        }
        return manga
    }

    // ========================= Latest (null-safe) =========================
    // override latest element parser to avoid nullpointer
    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        // gunakan selector yang aman untuk halaman terbaru (umumnya mirip popular)
        element.selectFirst("a[href*='/$mangaSubString/']")?.let { a ->
            manga.setUrlWithoutDomain(a.attr("abs:href"))
            manga.title = a.attr("title").ifBlank { a.text() }
        } ?: run {
            // fallback
            element.selectFirst("a")?.let { a ->
                manga.setUrlWithoutDomain(a.attr("abs:href"))
                manga.title = a.attr("title").ifBlank { a.text() }
            }
        }
        element.selectFirst("img")?.let { manga.thumbnail_url = imageFromElement(it) }
        return manga
    }

    // ========================= Chapter parsing: hilangkan "Baca di Web" =========================
    override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
        // ambil body string dulu karena super.chapterListParse may consume body
        val body = response.body?.string().orEmpty()
        val document = Jsoup.parse(body)

        // 1) coba super dulu — kalau sudah non-empty, kembalikan
        try {
            // buat Response baru dari body agar super bisa membaca lagi:
            val newResp = response.newBuilder()
                .body(okhttp3.ResponseBody.create(response.body?.contentType(), body))
                .build()
            val superList = super.chapterListParse(newResp)
            if (superList.isNotEmpty()) return superList
        } catch (_: Exception) {
            // lanjut ke fallback
        }

        // 2) parse langsung dari selector yang ada di HTML (menghilangkan "Baca di Web")
        val chapters = mutableListOf<SChapter>()
        document.select(".listing-chapters_wrap ul.main li.wp-manga-chapter a").forEach { a ->
            val ch = SChapter.create()
            ch.setUrlWithoutDomain(a.absUrl("href"))
            ch.name = a.text().trim().ifBlank { "Chapter" }
            ch.date_upload = 0L
            chapters += ch
        }

        // 3) fallback generic selectors
        if (chapters.isEmpty()) {
            document.select("li.wp-manga-chapter a, .chapters li a, .listing-chapters_wrap li a").forEach { a ->
                val ch = SChapter.create()
                ch.setUrlWithoutDomain(a.absUrl("href"))
                ch.name = a.text().trim().ifBlank { "Chapter" }
                ch.date_upload = 0L
                chapters += ch
            }
        }

        // 4) jika masih kosong: cek tombol "Baca di Web" -> buat single entry agar user buka webview
        if (chapters.isEmpty()) {
            val baca = document.selectFirst("a:containsOwn(Baca Di Web), a:containsOwn(Baca di Web), a:containsOwn(Read on web)")
            if (baca != null) {
                val ch = SChapter.create()
                ch.name = "Baca di web (link eksternal)"
                ch.setUrlWithoutDomain(baca.absUrl("href"))
                ch.date_upload = 0L
                return listOf(ch)
            }
        }

        return chapters
    }

    // ========================= Filters & Genres =========================
    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters = super.getFilterList().list.toMutableList()

        filters += if (genresList.isNotEmpty()) {
            listOf(
                Filter.Separator(),
                GenreContentFilter(
                    title = intl["genre_filter_title"],
                    options = genresList.map { it.name to it.id },
                ),
            )
        } else {
            listOf(
                Filter.Separator(),
                Filter.Header(intl["genre_missing_warning"]),
            )
        }

        return FilterList(filters)
    }

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) : UriPartFilter(
        title,
        options.toTypedArray(),
    )

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)
}
