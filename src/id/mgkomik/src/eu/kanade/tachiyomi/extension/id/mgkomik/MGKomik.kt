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
    override val useLoadMoreRequest = LoadMoreStrategy.Always
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

    inner class SmartWebViewInterceptor : Interceptor {
        private val mainHandler = Handler(Looper.getMainLooper())
        // Batasi hanya 1 WebView aktif sekaligus
        private val webViewSemaphore = Semaphore(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            val method = request.method

            if (!url.contains("mgkomik.cc") || method != "GET") {
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

            // Tunggu giliran — max 30 detik antri
            android.util.Log.d("MGKomik", "=== ANTRI SEMAPHORE: $originalUrl")
            val acquired = webViewSemaphore.tryAcquire(30, TimeUnit.SECONDS)
            if (!acquired) {
                throw IOException("Semaphore timeout: $originalUrl")
            }
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

                                        android.util.Log.d("MGKomik", "=== WV HTML LENGTH: ${htmlContent.length}")
                                        android.util.Log.d("MGKomik", "=== WV HAS ITEM-THUMB: ${htmlContent.contains("item-thumb")}")
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

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val items = document.select("div.item-thumb a")
        android.util.Log.d("MGKomik", "=== PARSE popularMangaParse: baseUri=${document.baseUri()}")
        android.util.Log.d("MGKomik", "=== PARSE item-thumb count=${items.size}")
        android.util.Log.d("MGKomik", "=== PARSE first title=${items.first()?.attr("title")}")
        android.util.Log.d("MGKomik", "=== PARSE first href=${items.first()?.attr("abs:href")}")
        return super.popularMangaParse(response)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val items = document.select("div.item-thumb a")
        android.util.Log.d("MGKomik", "=== PARSE latestUpdatesParse: baseUri=${document.baseUri()}")
        android.util.Log.d("MGKomik", "=== PARSE item-thumb count=${items.size}")
        return super.latestUpdatesParse(response)
    }

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.select("div.item-thumb a").let {
            android.util.Log.d("MGKomik", "=== MANGA: title=${it.attr("title")}, href=${it.attr("abs:href")}")
            setUrlWithoutDomain(it.attr("abs:href"))
            title = it.attr("title")
            thumbnail_url = it.select("img").attr("abs:src")
        }
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
