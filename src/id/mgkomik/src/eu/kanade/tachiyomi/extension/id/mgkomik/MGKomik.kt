package eu.kanade.tachiyomi.extension.id.mgkomik

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
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
import java.util.concurrent.TimeUnit

class MGKomik(context: Context) :
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
        .rateLimit(2, 1)
        .addInterceptor(WebViewFallbackInterceptor(context))
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

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/$mangaSubString/?m_orderby=views"
        else "$baseUrl/$mangaSubString/page/$page/?m_orderby=views"
        android.util.Log.d("MGKomik", "REQUEST popular page=$page url=$url")
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/$mangaSubString/?m_orderby=latest"
        else "$baseUrl/$mangaSubString/page/$page/?m_orderby=latest"
        android.util.Log.d("MGKomik", "REQUEST latest page=$page url=$url")
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "div.page-item-detail"
    override fun latestUpdatesSelector() = "div.page-item-detail"
    override fun popularMangaNextPageSelector() = "div.wp-pagenavi a.page, div.wp-pagenavi a.last"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val a = element.selectFirst("div.item-thumb a") ?: run {
            android.util.Log.w("MGKomik", "ELEMENT: div.item-thumb a NOT FOUND")
            return@apply
        }
        setUrlWithoutDomain(a.attr("abs:href"))
        title = element.selectFirst("div.post-title a")?.text()?.trim()
            ?: a.attr("title").ifEmpty { "NO TITLE" }
        val img = element.selectFirst("div.item-thumb img")
        thumbnail_url = img?.attr("abs:data-src")?.ifEmpty { null }
            ?: img?.attr("abs:src")?.ifEmpty { null }
            ?: img?.attr("abs:data-lazy-src")?.ifEmpty { null }
        android.util.Log.d("MGKomik", "MANGA: title=$title url=$url")
    }

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        android.util.Log.d("MGKomik", "PARSE popular: code=${response.code} title=${document.title()}")
        val items = document.select(popularMangaSelector())
        if (items.isEmpty()) android.util.Log.w("MGKomik", "PARSE popular: NO ITEMS | ${document.body().text().take(200)}")
        val mangas = items.map { popularMangaFromElement(it) }
        val hasNext = document.selectFirst(popularMangaNextPageSelector()) != null
        android.util.Log.d("MGKomik", "PARSE popular: count=${mangas.size} hasNext=$hasNext")
        return MangasPage(mangas, hasNext)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        android.util.Log.d("MGKomik", "PARSE latest: code=${response.code} title=${document.title()}")
        val items = document.select(latestUpdatesSelector())
        if (items.isEmpty()) android.util.Log.w("MGKomik", "PARSE latest: NO ITEMS | ${document.body().text().take(200)}")
        val mangas = items.map { latestUpdatesFromElement(it) }
        val hasNext = document.selectFirst(latestUpdatesNextPageSelector()) != null
        android.util.Log.d("MGKomik", "PARSE latest: count=${mangas.size} hasNext=$hasNext")
        return MangasPage(mangas, hasNext)
    }

    override val chapterUrlSuffix = ""

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }
        val filters = super.getFilterList().list.toMutableList()
        filters += if (genresList.isNotEmpty()) {
            listOf(Filter.Separator(), GenreContentFilter(intl["genre_filter_title"], genresList.map { it.name to it.id }))
        } else {
            listOf(Filter.Separator(), Filter.Header(intl["genre_missing_warning"]))
        }
        return FilterList(filters)
    }

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) :
        UriPartFilter(title, options.toTypedArray())

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val rawGenres = document.select(".row.genres li a")
        android.util.Log.d("MGKomik", "parseGenres: found ${rawGenres.size} genres")
        return mutableListOf(Genre("All", "")).apply {
            addAll(rawGenres.map { Genre(it.text(), it.absUrl("href")) })
        }
    }

    // ===================== WebView Fallback Interceptor =====================

    inner class WebViewFallbackInterceptor(private val ctx: Context) : Interceptor {
        private val handler = Handler(Looper.getMainLooper())

        private var cachedUrl = ""
        private var cachedHtml = ""
        private var cacheTime = 0L
        private val cacheLock = Object()
        private val cacheTtlMs = 5 * 60 * 1000L

        @Volatile private var webViewBusy = false
        private val webViewLock = Object()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            if (!url.contains("mgkomik.cc") || request.method != "GET") {
                return chain.proceed(request)
            }

            val cached = synchronized(cacheLock) { if (cachedUrl == url && System.currentTimeMillis() - cacheTime < cacheTtlMs) cachedHtml else null }
            if (cached != null) {
                android.util.Log.d("MGKomik", "CACHE HIT: $url")
                return buildResponse(request, url, cached.html)
            }

            val response = chain.proceed(request)
            android.util.Log.d("MGKomik", "RESPONSE: ${response.code} | $url")

            if (response.code == 403 && response.header("cf-mitigated") == "challenge") {
                android.util.Log.w("MGKomik", "CF 403 → WebView fallback: $url")
                response.close()

                synchronized(webViewLock) {
                    while (webViewBusy) {
                        android.util.Log.d("MGKomik", "WAIT: WebView busy for $url")
                        val fresh = synchronized(cacheLock) { if (cachedUrl == url && System.currentTimeMillis() - cacheTime < cacheTtlMs) cachedHtml else null }
                        if (fresh != null) {
                            android.util.Log.d("MGKomik", "CACHE HIT (after wait): $url")
                            return buildResponse(request, url, fresh)
                        }
                        webViewLock.wait(500)
                    }
                    webViewBusy = true
                }

                try {
                    val fresh = synchronized(cacheLock) { if (cachedUrl == url && System.currentTimeMillis() - cacheTime < cacheTtlMs) cachedHtml else null }
                    if (fresh != null) {
                        android.util.Log.d("MGKomik", "CACHE HIT (after lock): $url")
                        return buildResponse(request, url, fresh)
                    }

                    val html = fetchHtmlViaWebView(url)
                    if (html.isBlank()) throw IOException("WebView returned empty HTML for: $url")

                    synchronized(cacheLock) { cachedUrl = url; cachedHtml = html; cacheTime = System.currentTimeMillis() }
                    android.util.Log.d("MGKomik", "CACHE STORED: $url")

                    return buildResponse(request, url, html)
                } finally {
                    synchronized(webViewLock) {
                        webViewBusy = false
                        webViewLock.notifyAll()
                    }
                }
            }

            return response
        }

        private fun buildResponse(request: Request, url: String, html: String): Response {
            return Response.Builder()
                .request(request.newBuilder().url(url.toHttpUrl()).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun fetchHtmlViaWebView(targetUrl: String): String {
            val latch = CountDownLatch(1)
            var html = ""
            var finalUrl = targetUrl

            handler.post {
                val wv = WebView(ctx)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36"
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(wv, true)
                }

                wv.webViewClient = object : WebViewClient() {
                    private var pageLoadCount = 0

                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        pageLoadCount++
                        finalUrl = pageUrl ?: finalUrl
                        val currentCount = pageLoadCount
                        android.util.Log.d("MGKomik", "WV PAGE_FINISHED #$pageLoadCount: $finalUrl")

                        handler.postDelayed({
                            if (pageLoadCount != currentCount || latch.count == 0L) return@postDelayed

                            view.evaluateJavascript("(function(){ return document.documentElement.outerHTML; })()") { raw ->
                                if (raw == null || raw == "null") {
                                    android.util.Log.e("MGKomik", "WV JS null")
                                    latch.countDown()
                                    view.destroy()
                                    return@evaluateJavascript
                                }

                                val decoded = raw.removeSurrounding("\"")
                                    .replace("\\u003C", "<").replace("\\u003E", ">")
                                    .replace("\\u0026", "&").replace("\\\"", "\"")
                                    .replace("\\n", "\n").replace("\\t", "\t")
                                    .replace("\\'", "'").replace("\\\\", "\\")

                                val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
                                    .find(decoded)?.groupValues?.get(1) ?: "-"
                                android.util.Log.d("MGKomik", "WV HTML: len=${decoded.length} title=$title")

                                if (title.contains("Just a moment", ignoreCase = true) ||
                                    title.contains("Checking your browser", ignoreCase = true)
                                ) {
                                    android.util.Log.w("MGKomik", "WV still CF page, waiting...")
                                    return@evaluateJavascript
                                }

                                html = decoded
                                android.util.Log.d("MGKomik", "WV SUCCESS: $finalUrl")
                                latch.countDown()
                                view.destroy()
                            }
                        }, 2000)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        android.util.Log.e("MGKomik", "WV ERROR: $errorCode $description @ $failingUrl")
                        if (failingUrl == targetUrl) {
                            latch.countDown()
                            view?.destroy()
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean = false
                }

                android.util.Log.d("MGKomik", "WV LOAD: $targetUrl")
                wv.loadUrl(targetUrl)
            }

            val completed = latch.await(25, TimeUnit.SECONDS)
            android.util.Log.d("MGKomik", "WV DONE: completed=$completed htmlLen=${html.length} url=$targetUrl")
            return html
        }
    }
}
