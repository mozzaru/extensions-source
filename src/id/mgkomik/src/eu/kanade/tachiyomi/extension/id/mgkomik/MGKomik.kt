package eu.kanade.tachiyomi.extension.id.mgkomik

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MGKomik :
    Madara(
        "MG Komik",
        "https://id.mgkomik.cc",
        "id",
        SimpleDateFormat("dd MMM yy", Locale.US),
    ) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(CloudflareWebViewInterceptor())
        .build()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36")
        set("Referer", "$baseUrl/")
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        set("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        set("Upgrade-Insecure-Requests", "1")
        set("sec-ch-ua", "\"Chromium\";v=\"147\", \"Not.A/Brand\";v=\"8\"")
        set("sec-ch-ua-mobile", "?1")
        set("sec-ch-ua-platform", "\"Android\"")
        set("sec-fetch-dest", "document")
        set("sec-fetch-mode", "navigate")
        set("sec-fetch-site", "same-origin")
        set("sec-fetch-user", "?1")
    }

    // ===================== Requests =====================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/?m_orderby=views&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/?m_orderby=latest&page=$page", headers)

    // ===================== Selectors =====================

    override fun popularMangaSelector() = "div.page-item-detail"
    override fun latestUpdatesSelector() = "div.page-item-detail"

    // Fix: pakai selector yang lebih luas untuk pagination
    override fun popularMangaNextPageSelector() =
        "div.wp-pagenavi a.nextpostslink, .navigation-ajax + div a[href*='page'], a.next.page-numbers"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ===================== Parsing =====================

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val a = element.selectFirst("div.item-thumb a")!!
        setUrlWithoutDomain(a.attr("abs:href"))
        title = element.selectFirst("div.post-title a, div.item-summary .post-title a")
            ?.text()?.trim()
            ?: a.attr("title").ifEmpty { "NO TITLE" }
        thumbnail_url = element.selectFirst("div.item-thumb img")
            ?.let { img ->
                img.attr("abs:data-src").ifEmpty { null }
                    ?: img.attr("abs:src").ifEmpty { null }
            }
        android.util.Log.d("MGKomik", "MANGA: title=$title url=$url thumb=$thumbnail_url")
    }

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        android.util.Log.d("MGKomik", "PARSE popular: url=${document.baseUri()} title=${document.title()}")
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
        android.util.Log.d("MGKomik", "PARSE popular: count=${mangas.size} hasNext=$hasNextPage")
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        android.util.Log.d("MGKomik", "PARSE latest: url=${document.baseUri()} title=${document.title()}")
        val mangas = document.select(latestUpdatesSelector()).map { latestUpdatesFromElement(it) }
        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null
        android.util.Log.d("MGKomik", "PARSE latest: count=${mangas.size} hasNext=$hasNextPage")
        return MangasPage(mangas, hasNextPage)
    }

    // ===================== Genre Filter =====================

    override val chapterUrlSuffix = ""

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

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) :
        UriPartFilter(title, options.toTypedArray())

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")
        genres += document.select(".row.genres li a").map { a ->
            Genre(a.text(), a.absUrl("href"))
        }
        return genres
    }

    // ===================== WebView Interceptor =====================

    inner class CloudflareWebViewInterceptor : Interceptor {
        private val mainHandler = Handler(Looper.getMainLooper())

        // Semaphore 1: WebView harus jalan satu per satu di main thread
        private val webViewSemaphore = Semaphore(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            // Lewati request yang tidak perlu WebView:
            // - Bukan domain mgkomik
            // - Bukan GET
            // - AJAX request
            // - File gambar (uploads)
            if (!url.contains("mgkomik.cc") ||
                request.method != "GET" ||
                url.contains("admin-ajax.php") ||
                url.contains("/wp-content/uploads/")
            ) {
                android.util.Log.d("MGKomik", "SKIP: $url")
                return chain.proceed(request)
            }

            android.util.Log.d("MGKomik", "WEBVIEW: $url")
            return fetchWithWebView(request)
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun fetchWithWebView(request: Request): Response {
            val originalUrl = request.url.toString()
            val latch = CountDownLatch(1)
            var htmlContent = ""
            var finalUrl = originalUrl

            // Tunggu giliran (max 30 detik)
            if (!webViewSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                android.util.Log.e("MGKomik", "SEMAPHORE TIMEOUT: $originalUrl")
                throw IOException("WebView semaphore timeout for: $originalUrl")
            }
            android.util.Log.d("MGKomik", "SEMAPHORE ACQUIRED: $originalUrl")

            try {
                mainHandler.post {
                    val appContext = try {
                        Class.forName("android.app.ActivityThread")
                            .getMethod("currentApplication")
                            .invoke(null) as android.content.Context
                    } catch (e: Exception) {
                        android.util.Log.e("MGKomik", "CONTEXT ERROR: ${e.message}")
                        latch.countDown()
                        return@post
                    }

                    val webView = WebView(appContext)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36"
                        // Blokir gambar agar halaman lebih cepat load
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webView, true)
                    }

                    webView.webViewClient = object : WebViewClient() {
                        private var loadCount = 0

                        override fun onPageFinished(view: WebView, url: String?) {
                            loadCount++
                            finalUrl = url ?: finalUrl
                            android.util.Log.d("MGKomik", "PAGE_FINISHED #$loadCount: $finalUrl")

                            // Tunggu 2.5 detik setelah halaman selesai load.
                            // Jika tidak ada load baru dalam waktu itu, ambil HTML.
                            val countAtDelay = loadCount
                            mainHandler.postDelayed({
                                if (loadCount != countAtDelay || latch.count == 0L) return@postDelayed

                                android.util.Log.d("MGKomik", "STABLE: $finalUrl")
                                view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                    htmlContent = raw
                                        .removeSurrounding("\"")
                                        .replace("\\u003C", "<")
                                        .replace("\\u003E", ">")
                                        .replace("\\u0026", "&")
                                        .replace("\\\"", "\"")
                                        .replace("\\n", "\n")
                                        .replace("\\t", "\t")
                                        .replace("\\'", "'")
                                        .replace("\\\\", "\\")

                                    val title = Regex("<title>(.*?)</title>").find(htmlContent)?.groupValues?.get(1) ?: "-"
                                    android.util.Log.d("MGKomik", "HTML len=${htmlContent.length} title=$title url=$finalUrl")

                                    latch.countDown()
                                    view.destroy()
                                }
                            }, 2500)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            // Hanya hentikan jika error di URL utama
                            if (failingUrl == originalUrl || failingUrl == finalUrl) {
                                android.util.Log.e("MGKomik", "WV_ERROR $errorCode $description @ $failingUrl")
                                latch.countDown()
                                view?.destroy()
                            }
                        }

                        // Blokir resource tidak penting agar lebih cepat
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            return false // Izinkan semua navigasi (redirect CF)
                        }
                    }

                    android.util.Log.d("MGKomik", "WV_LOAD: $originalUrl")
                    webView.loadUrl(originalUrl)
                }

                val completed = latch.await(25, TimeUnit.SECONDS)
                android.util.Log.d("MGKomik", "LATCH: completed=$completed len=${htmlContent.length} url=$originalUrl")

                if (!completed || htmlContent.isBlank()) {
                    throw IOException("WebView timeout for: $originalUrl")
                }

                return Response.Builder()
                    .request(request.newBuilder().url(finalUrl.toHttpUrl()).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(htmlContent.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            } finally {
                webViewSemaphore.release()
                android.util.Log.d("MGKomik", "SEMAPHORE RELEASED: $originalUrl")
            }
        }
    }
}
