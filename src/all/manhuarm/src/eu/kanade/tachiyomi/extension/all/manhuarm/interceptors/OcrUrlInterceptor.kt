package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OcrUrlInterceptor(private val headers: Headers) {

    private val context: Application by injectLazy()
    private val handler = Handler(Looper.getMainLooper())

    // A predictable name so utilities.js can find us via `window.__manhuarmBridge`.
    private val bridgeName = "__manhuarmBridge"

    fun getOcrRequest(url: String): OcrRequest? {
        val latch = CountDownLatch(1)
        var ocrRequest: OcrRequest? = null
        var webView: WebView? = null
        val startedAt = System.currentTimeMillis()

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = headers["User-Agent"]
            }

            webview.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onFetch(url: String, body: String, headersJson: String) {
                        if (ocrRequest == null && url.contains("fetch-ocr.php")) {
                            // Filter out the scraper-detection probe. The
                            // website sends a fake payload with cid="fake" and
                            // ref="fake" - we have to ignore that and only act
                            // on a real-looking payload.
                            if (body.contains("\"cid\":\"fake\"") || body.contains("\"ref\":\"fake\"")) {
                                Log.d(
                                    TAG,
                                    "Ignoring scraper-detection probe",
                                )
                                return
                            }
                            val headerMap = mutableMapOf<String, String>()
                            try {
                                val json = org.json.JSONObject(headersJson)
                                val keys = json.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    headerMap[key] = json.getString(key)
                                }
                            } catch (_: Exception) { /* ignore */ }

                            ocrRequest = OcrRequest(url, body, headerMap)
                            val elapsed = System.currentTimeMillis() - startedAt
                            Log.d(
                                TAG,
                                "Real OCR call captured after ${elapsed}ms, " +
                                    "body=${body.take(120)}",
                            )
                            latch.countDown()
                        }
                    }
                },
                bridgeName,
            )

            webview.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) = injectScript(view)
                override fun onPageFinished(view: WebView?, url: String?) = injectScript(view)
            }

            webview.loadUrl(url, headers.toMultimap().mapValues { it.value.first() })
        }

        val completed = latch.await(15, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - startedAt

        handler.post {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }

        if (!completed) {
            Log.w(
                TAG,
                "OCR call NOT captured after ${elapsed}ms (timeout). " +
                    "Anti-scraping may have rejected the page - check that " +
                    "Function.prototype.toString is being patched correctly.",
            )
        } else {
            Log.d(TAG, "OCR request returned in ${elapsed}ms")
        }
        return ocrRequest
    }

    private val utilities: String by lazy {
        javaClass.getResource("/assets/scripts/utilities.js")!!.readText()
    }

    private fun injectScript(view: WebView?) {
        // The utilities.js script does ALL the patching now: it patches
        // Function.prototype.toString to fool the anti-scraping check, then
        // installs XHR/fetch/setTimeout/setInterval/Worker wrappers. It uses
        // `window.__manhuarmBridge` (set up by the JavascriptInterface above)
        // to send the captured OCR request back to us.
        view?.evaluateJavascript(utilities, null)
    }

    private companion object {
        const val TAG = "Manhuarm.OCR"
    }
}

data class OcrRequest(
    val url: String,
    val body: String,
    val interceptedHeaders: Map<String, String>,
)
