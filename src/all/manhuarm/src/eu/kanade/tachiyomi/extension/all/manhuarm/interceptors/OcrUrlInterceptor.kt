package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OcrUrlInterceptor(private val headers: Headers) {

    private val context: Application by injectLazy()
    private val handler = Handler(Looper.getMainLooper())
    private val bridgeName = ('a'..'z').shuffled().take(10).joinToString("")

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
                            val headerMap = mutableMapOf<String, String>()
                            try {
                                val json = JSONObject(headersJson)
                                val keys = json.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    headerMap[key] = json.getString(key)
                                }
                            } catch (_: Exception) { /* do nothing */ }

                            ocrRequest = OcrRequest(url, body, headerMap)
                            val elapsed = System.currentTimeMillis() - startedAt
                            Log.d(TAG, "OCR call captured after ${elapsed}ms, body=${body.take(80)}")
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
                    "This is the cause of empty bubbles. The website may be using " +
                    "a transport that our JS patches do not cover.",
            )
        } else {
            Log.d(
                TAG,
                "OCR request returned in ${elapsed}ms",
            )
        }
        return ocrRequest
    }

    private val utilities: String by lazy {
        javaClass.getResource("/assets/scripts/utilities.js")!!.readText()
    }

    private fun injectScript(view: WebView?) {
        view?.evaluateJavascript(
            """
            (function() {
                if (window.__manhuarmPatched) {
                    return;
                }
                window.__manhuarmPatched = true;
                window.__manhuarmBridge = '$bridgeName';

                // Capture the native fetch BEFORE we (and utilities.js)
                // override it, so the XHRProxy inside utilities.js can be
                // wrapped and report the OCR call back to us. The website
                // uses XMLHttpRequest which utilities.js patches via an
                // XHRProxy that delegates to fetch(); if we don't override
                // fetch first, the OCR call bypasses our interceptor.
                const nativeFetch = window.fetch ? window.fetch.bind(window) : null;

                if (nativeFetch) {
                    window.fetch = function() {
                        const input = arguments[0];
                        const options = arguments[1] || {};
                        const url = typeof input === 'string' ? input : (input.url || "");

                        if (url && url.indexOf('fetch-ocr.php') !== -1) {
                            let body = options.body;
                            if (body && typeof body !== 'string') {
                                try { body = JSON.stringify(body); } catch (_) { body = String(body); }
                            }
                            try {
                                window.$bridgeName.onFetch(url, body || '', JSON.stringify(options.headers || {}));
                            } catch (_) { /* ignore */ }
                        }
                        return nativeFetch.apply(window, arguments);
                    };
                }

                // Also patch XMLHttpRequest.prototype.open to capture the URL
                // and method, and .send to capture the body. This is a
                // belt-and-braces fallback for the case where the page's own
                // scripts run before our fetch override and create their own
                // XHR before the XHRProxy in utilities.js is installed.
                const origOpen = XMLHttpRequest.prototype.open;
                const origSend = XMLHttpRequest.prototype.send;
                const xhrState = new WeakMap();
                XMLHttpRequest.prototype.open = function(method, url) {
                    xhrState.set(this, { method: method, url: String(url || ''), body: null });
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(body) {
                    const state = xhrState.get(this);
                    if (state) {
                        state.body = body;
                        if (state.url && state.url.indexOf('fetch-ocr.php') !== -1) {
                            try {
                                let payload = body;
                                if (payload && typeof payload !== 'string') {
                                    try { payload = JSON.stringify(payload); } catch (_) { payload = String(payload); }
                                }
                                window.$bridgeName.onFetch(state.url, payload || '', '{}');
                            } catch (_) { /* ignore */ }
                        }
                    }
                    return origSend.apply(this, arguments);
                };

                // Now run utilities.js which replaces XMLHttpRequest with a
                // proxy. The proxy calls fetch() internally, which is now our
                // patched version, so OCR calls are also reported back.
                $utilities
            })();
            """.trimIndent(),
            null,
        )
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
