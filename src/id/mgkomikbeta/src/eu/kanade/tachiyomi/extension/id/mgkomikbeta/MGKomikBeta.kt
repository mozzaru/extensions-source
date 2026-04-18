package eu.kanade.tachiyomi.extension.id.mgkomikbeta

import eu.kanade.tachiyomi.multisrc.natsuid.GenreExclusion
import eu.kanade.tachiyomi.multisrc.natsuid.GenreFilter
import eu.kanade.tachiyomi.multisrc.natsuid.GenreInclusion
import eu.kanade.tachiyomi.multisrc.natsuid.NatsuId
import eu.kanade.tachiyomi.multisrc.natsuid.SortFilter
import eu.kanade.tachiyomi.multisrc.natsuid.StatusFilter
import eu.kanade.tachiyomi.multisrc.natsuid.TypeFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.toJsonString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomikBeta :
    NatsuId(
        "MG Komik Beta",
        "id",
        "https://web.mgkomik.cc",
    ) {

    override fun OkHttpClient.Builder.customizeClient() = rateLimit(3)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("admin-ajax.php") || url.contains("wp-json")) {
                val newRequest = request.newBuilder()
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    // Popular and Latest from HTML instead of REST API/AJAX for Beta
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/komik/?filter=&order_by=views&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/komik/?filter=&order_by=latest&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = mangaListParse(response)
    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    private fun mangaListParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // Based on common WordPress manga theme structures
        val mangas = document.select(".listupd .bs, .listupd .utao, .grid-item, .manga-card, .list-item").mapNotNull { element ->
            val a = element.selectFirst("a[href*='/komik/'], a[href*='/manga/'], a[href*='/series/']") ?: return@mapNotNull null
            val img = element.selectFirst("img") ?: return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(a.attr("abs:href"))
                title = element.selectFirst(".title, h2, h3, .tt")?.text()?.trim()
                    ?: img.attr("alt").trim()
                thumbnail_url = img.attr("abs:src")
            }
        }.distinctBy { it.url }

        // Broader search if still empty (excluding known ad containers)
        if (mangas.isEmpty()) {
            val mangasAlt = document.select("a[href*='/komik/']:has(img), a[href*='/manga/']:has(img), a[href*='/series/']:has(img)")
                .filter { it.closest(".ads-responsive-grid") == null }
                .map { a ->
                    SManga.create().apply {
                        setUrlWithoutDomain(a.attr("abs:href"))
                        title = a.selectFirst("img")?.attr("alt")?.trim() ?: a.text().trim()
                        thumbnail_url = a.selectFirst("img")?.attr("abs:src")
                    }
                }.distinctBy { it.url }

            val hasNextPage = document.selectFirst(".pagination .next, a:contains(Next), .next-page, .hpage a.r") != null
            return MangasPage(mangasAlt, hasNextPage)
        }

        val hasNextPage = document.selectFirst(".pagination .next, a:contains(Next), .next-page, .hpage a.r") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder().apply {
            set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            set("Referer", page.url)
            removeAll("Upgrade-Insecure-Requests")
        }.build()

        return GET(page.imageUrl!!, newHeaders)
    }

    private var nonce: String? = null

    @Synchronized
    private fun getNonceBeta(): String {
        if (nonce != null) return nonce!!

        val ajaxHeaders = headersBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .build()

        // 1. Try various AJAX actions for "nonce Beta"
        val actions = listOf(
            "get_nonce",
            "get_nonce_beta",
            "natsu_get_nonce",
            "get_search_nonce",
            "search_nonce",
            "get_nonce&type=search_form",
        )

        for (action in actions) {
            try {
                val url = "$baseUrl/wp-admin/admin-ajax.php?action=$action"
                val response = client.newCall(GET(url, ajaxHeaders)).execute().body.string()
                nonce = Jsoup.parseBodyFragment(response).selectFirst("input[name$=_nonce], input[name$=security], input[name=search_nonce]")?.attr("value")
                    ?: Regex("""["'](?:nonce|security|natsu_nonce)["']\s*[:=]\s*["']([a-z0-9]{10})["']""").find(response)?.groupValues?.get(1)
                if (nonce != null) break
            } catch (_: Exception) {}
        }

        if (nonce != null) return nonce!!

        // 2. Try to find nonce in the homepage HTML
        try {
            val response = client.newCall(GET(baseUrl, headers)).execute().body.string()
            nonce = Regex("""["'](?:nonce|security|natsu_nonce)["']\s*[:=]\s*["']([a-z0-9]{10})["']""").find(response)?.groupValues?.get(1)
                ?: Jsoup.parse(response).selectFirst("input[name$=_nonce], input[name$=security], input[name=search_nonce]")?.attr("value")
        } catch (_: Exception) {}

        return nonce ?: throw Exception("Unable to get nonce (Beta)")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search/?q=${query.trim()}&page=$page", headers)
        }

        val url = "$baseUrl/wp-admin/admin-ajax.php?action=advanced_search"
        val ajaxHeaders = headersBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .build()

        val body = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            addFormDataPart("nonce", getNonceBeta())
            filters.firstInstanceOrNull<GenreInclusion>()?.selected.also {
                addFormDataPart("inclusion", it ?: "OR")
            }
            filters.firstInstanceOrNull<GenreExclusion>()?.selected.also {
                addFormDataPart("exclusion", it ?: "OR")
            }
            addFormDataPart("page", page.toString())
            val genres = filters.firstInstanceOrNull<GenreFilter>()
            genres?.included.orEmpty().also {
                addFormDataPart("genre", it.toJsonString())
            }
            genres?.excluded.orEmpty().also {
                addFormDataPart("genre_exclude", it.toJsonString())
            }
            addFormDataPart("author", "[]")
            addFormDataPart("artist", "[]")
            addFormDataPart("project", "0")
            filters.firstInstanceOrNull<TypeFilter>()?.checked.orEmpty().also {
                addFormDataPart("type", it.toJsonString())
            }
            filters.firstInstanceOrNull<StatusFilter>()?.checked.orEmpty().also {
                addFormDataPart("status", it.toJsonString())
            }
            val sort = filters.firstInstance<SortFilter>()
            addFormDataPart("order", if (sort.isAscending) "asc" else "desc")
            addFormDataPart("orderby", sort.sort)
            addFormDataPart("query", query.trim())
        }.build()

        return POST(url, ajaxHeaders, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        if (url.contains("/search/")) {
            return mangaListParse(response)
        }

        val body = response.body.string()
        if (body.contains("Result not found") || body.contains("tidak ditemukan")) {
            return MangasPage(emptyList(), false)
        }

        val document = Jsoup.parseBodyFragment(body, baseUrl)
        val mangas = document.select("a").mapNotNull {
            val href = it.attr("abs:href")
            if (href.contains("/komik/") || href.contains("/manga/") || href.contains("/series/")) {
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    title = it.selectFirst("img")?.attr("alt") ?: it.text()
                    thumbnail_url = it.selectFirst("img")?.attr("abs:src")
                }
            } else {
                null
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("button:has(svg)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".manga-title, h1#mangaTitle")?.ownText()?.trim() ?: ""
            val altTitle = document.selectFirst("h1#mangaTitle")?.attr("data-alt")
            description = document.select(".manga-description p").text().trim()
            if (!altTitle.isNullOrBlank()) {
                description = "Alternative Title: $altTitle\n\n$description"
            }
            author = document.selectFirst(".meta-item:contains(Author:)")?.text()?.substringAfter("Author:")?.trim()
            genre = document.select(".genre-tag").joinToString { it.text() }
            status = when (document.selectFirst(".status-badge")?.text()?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            thumbnail_url = document.selectFirst(".manga-cover-large")?.attr("abs:src")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        document.select(".chapter-list-item").forEach { element ->
            val a = element.selectFirst("a.chapter-link")!!
            chapters.add(
                SChapter.create().apply {
                    setUrlWithoutDomain(a.attr("abs:href"))
                    name = element.selectFirst(".chapter-number")?.text() ?: ""
                    date_upload = parseRelativeDate(element.selectFirst(".chapter-date")?.text() ?: "")
                },
            )
        }

        return chapters
    }

    private fun parseRelativeDate(date: String): Long {
        val trimmedDate = date.lowercase().trim()
        // Relative dates
        if (trimmedDate.contains("detik") || trimmedDate.contains("menit") || trimmedDate.contains("jam") || trimmedDate.contains("hari") || trimmedDate.contains("minggu") || trimmedDate.contains("bulan") || trimmedDate.contains("tahun")) {
            val value = Regex("""(\d+)""").find(trimmedDate)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
            val now = System.currentTimeMillis()
            return when {
                trimmedDate.contains("detik") -> now - (value * 1000)
                trimmedDate.contains("menit") -> now - (value * 60 * 1000)
                trimmedDate.contains("jam") -> now - (value * 60 * 60 * 1000)
                trimmedDate.contains("hari") -> now - (value * 24 * 60 * 60 * 1000)
                trimmedDate.contains("minggu") -> now - (value * 7 * 24 * 60 * 60 * 1000)
                trimmedDate.contains("bulan") -> now - (value * 30L * 24 * 60 * 60 * 1000)
                trimmedDate.contains("tahun") -> now - (value * 365L * 24 * 60 * 60 * 1000)
                else -> 0L
            }
        }
        // Absolute dates
        return try {
            SimpleDateFormat("dd MMM yy", Locale("id")).parse(date)?.time ?: 0L
        } catch (_: Exception) {
            try {
                SimpleDateFormat("dd MMM yyyy", Locale("id")).parse(date)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("#readingContent img").mapIndexed { idx, img ->
            Page(idx, imageUrl = img.attr("abs:src"))
        }
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith("https://")) {
        deepLink(query)
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    private fun deepLink(url: String): Observable<MangasPage> = client.newCall(GET(url, headers)).asObservableSuccess().map { response ->
        MangasPage(listOf(mangaDetailsParse(response).apply { this.url = url.toHttpUrl().encodedPath }), false)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)
    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)
}
