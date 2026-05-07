package eu.kanade.tachiyomi.extension.id.mgkomik

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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
    // FIX 1: Matikan LoadMore agar tidak pakai AJAX POST
    // AJAX POST diblock oleh interceptor → return "0" → manga kosong
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false
    override val mangaSubString = "komik"

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(SmartWebViewInterceptor())
        .build()

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36")
        set("Referer", "$baseUrl/")
        set("Upgrade-Insecure-Requests", "1")
        set("sec-ch-ua", "\"Chromium\";v=\"147\", \"Not.A/Brand\";v=\"8\"")
        set("sec-ch-ua-mobile", "?1")
        set("sec-ch-ua-platform", "\"Android\"")
        set("sec-fetch-dest", "document")
        set("sec-fetch-mode", "navigate")
        set("sec-fetch-site", "same-origin")
        set("sec-fetch-user", "?1")
    }

    // FIX 2: Override request URL dengan query yang benar
    // Madara base tidak append ?m_orderby → page jadi generic/search kosong
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/?m_orderby=views&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/$mangaSubString/?m_orderby=latest&page=$page", headers)

    inner class SmartWebViewInterceptor : Interceptor {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val webViewSemaphore = Semaphore(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            val method = request.method

            // FIX 3: Skip admin-ajax.php (POST dan GET) — tidak perlu WebView
            if (!url.contains("mgkomik.cc") ||
                method != "GET" ||
                url.contains("admin-ajax.php")
            ) {
                android.util.Log.d("MGKomik", "=== SKIP: $url")
                return chain.proceed(request)
            }

            android.util.Log.d("MGKomik", "=== WEBVIEW FETCH: $url")
            return try {
                fetchWithWebView(request)
            } catch (e: Exception) {
                android.util.Log.e("MGKomik", "=== ERROR: ${e.message}, fallback OkHttp")
                chain.proceed(request)
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun fetchWithWebView(request: Request): Response {
            val originalUrl = request.url.toString()
            val latch = CountDownLatch(1)
            var htmlContent = ""
            var finalUrl = originalUrl

            android.util.Log.d("MGKomik", "=== ANTRI SEMAPHORE: $originalUrl")
            val acquired = webViewSemaphore.tryAcquire(30, TimeUnit.SECONDS)
            if (!acquired) throw IOException("Semaphore timeout: $originalUrl")
            android.util.Log.d("MGKomik", "=== DAPAT SEMAPHORE: $originalUrl")

            try {
                mainHandler.post {
                    val appContext = try {
                        Class.forName("android.app.ActivityThread")
                            .getMethod("currentApplication")
                            .invoke(null) as android.content.Context
                    } catch (e: Exception) {
                        android.util.Log.e("MGKomik", "=== CONTEXT ERROR: ${e.message}")
                        latch.countDown()
                        return@post
                    }

                    val webView = WebView(appContext)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36"
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webView, true)
                    }

                    var pageFinishedCount = 0

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            pageFinishedCount++
                            finalUrl = url ?: finalUrl
                            android.util.Log.d("MGKomik", "=== WV onPageFinished #$pageFinishedCount: $url")

                            val countAtDelay = pageFinishedCount
                            mainHandler.postDelayed({
                                if (pageFinishedCount == countAtDelay && latch.count > 0) {
                                    android.util.Log.d("MGKomik", "=== WV STABIL: $finalUrl")
                                    view.evaluateJavascript(
                                        "(function() { return document.documentElement.outerHTML; })()",
                                    ) { html ->
                                        htmlContent = html
                                            .removeSurrounding("\"")
                                            .replace("\\u003C", "<")
                                            .replace("\\u003E", ">")
                                            .replace("\\u0026", "&")
                                            .replace("\\\"", "\"")
                                            .replace("\\n", "\n")
                                            .replace("\\t", "\t")
                                            .replace("\\'", "'")
                                            .replace("\\\\", "\\")

                                        // FIX 4: Log title dan URL final untuk debug
                                        val titleMatch = Regex("<title>(.*?)</title>")
                                            .find(htmlContent)?.groupValues?.get(1) ?: "null"
                                        android.util.Log.d("MGKomik", "=== WV HTML LENGTH: ${htmlContent.length}")
                                        android.util.Log.d("MGKomik", "=== WV TITLE: $titleMatch")
                                        android.util.Log.d("MGKomik", "=== WV HAS ITEM-THUMB: ${htmlContent.contains("item-thumb")}")
                                        android.util.Log.d("MGKomik", "=== WV HAS TAB-THUMB: ${htmlContent.contains("tab-thumb")}")
                                        android.util.Log.d("MGKomik", "=== WV FINAL URL: $finalUrl")

                                        latch.countDown()
                                        view.destroy()
                                    }
                                }
                            }, 3000)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            android.util.Log.e("MGKomik", "=== WV ERROR: $errorCode $description")
                            latch.countDown()
                            view?.destroy()
                        }
                    }

                    android.util.Log.d("MGKomik", "=== WV LOAD URL: $originalUrl")
                    webView.loadUrl(originalUrl)
                }

                val completed = latch.await(25, TimeUnit.SECONDS)
                android.util.Log.d("MGKomik", "=== WV LATCH: completed=$completed, len=${htmlContent.length}")

                if (!completed || htmlContent.isBlank()) {
                    throw IOException("WebView timeout: $originalUrl")
                }

                val finalRequest = request.newBuilder()
                    .url(finalUrl.toHttpUrl())
                    .build()

                return Response.Builder()
                    .request(finalRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(htmlContent.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            } finally {
                webViewSemaphore.release()
                android.util.Log.d("MGKomik", "=== RELEASE SEMAPHORE: $originalUrl")
            }
        }
    }

    // FIX 5: Parse dari struktur HTML nyata (tab-thumb, bukan item-thumb)
    // Struktur: div.tab-thumb.c-image-hover > a[href][title] > img[src]
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val a = element.select("div.tab-thumb a").firstOrNull()
            ?: element.select("div.item-thumb a").firstOrNull()
        android.util.Log.d("MGKomik", "=== MANGA FROM ELEMENT: title=${a?.attr("title")}, href=${a?.attr("abs:href")}")
        a?.let {
            setUrlWithoutDomain(it.attr("abs:href"))
            title = it.attr("title")
            thumbnail_url = it.select("img").attr("abs:src")
        }
    }

    // FIX 6: Selector untuk popular/latest — pakai struktur search result
    // Yang muncul di /komik/?m_orderby=xxx adalah format search result
    override fun popularMangaSelector() = "div.c-tabs-item__content"
    override fun latestUpdatesSelector() = "div.c-tabs-item__content"

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    // FIX 7: Tidak ada next page di search result format ini, pakai wp-pagenavi
    override fun popularMangaNextPageSelector() = "div.wp-pagenavi a.nextpostslink"
    override fun latestUpdatesNextPageSelector() = "div.wp-pagenavi a.nextpostslink"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        android.util.Log.d("MGKomik", "=== PARSE popularMangaParse: baseUri=${document.baseUri()}")
        android.util.Log.d("MGKomik", "=== PARSE title=${document.title()}")
        android.util.Log.d("MGKomik", "=== PARSE tab-thumb count=${document.select("div.tab-thumb").size}")
        android.util.Log.d("MGKomik", "=== PARSE item-thumb count=${document.select("div.item-thumb").size}")
        android.util.Log.d("MGKomik", "=== PARSE c-tabs-item__content count=${document.select("div.c-tabs-item__content").size}")
        return super.popularMangaParse(response)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        android.util.Log.d("MGKomik", "=== PARSE latestUpdatesParse: baseUri=${document.baseUri()}")
        android.util.Log.d("MGKomik", "=== PARSE title=${document.title()}")
        android.util.Log.d("MGKomik", "=== PARSE tab-thumb count=${document.select("div.tab-thumb").size}")
        android.util.Log.d("MGKomik", "=== PARSE c-tabs-item__content count=${document.select("div.c-tabs-item__content").size}")
        return super.latestUpdatesParse(response)
    }

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
}
