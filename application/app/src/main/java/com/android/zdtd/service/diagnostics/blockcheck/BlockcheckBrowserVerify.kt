package com.android.zdtd.service.diagnostics.blockcheck

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * "Open like a real browser" verification (zapret22 semantics): after a
 * strategy is confirmed by the curl probe, load the page once more through a
 * headless WebView. WebView is the same Chromium network stack the device
 * browser uses (system TLS, system DNS, h2), so a pass here is much closer to
 * "the site actually opens" than another curl round.
 *
 * Verdicts:
 *  - PASS: onPageFinished without a primary-frame network error. Redirects,
 *    consent pages and 4xx/5xx shells still prove the transport works —
 *    content quality is out of scope, same as the curl probe.
 *  - FAIL: primary-frame network error (the transport is broken for a real
 *    browser too), or the page never settled within [TIMEOUT_MS].
 */
object BlockcheckBrowserVerify {

    const val TIMEOUT_MS: Long = 15_000

    data class Result(val ok: Boolean, val detail: String)

    /**
     * @param host a hostname from the scan's probe targets; the strategy must
     *   already be applied by the caller (WebView traffic goes through the
     *   live engine, so this measures the applied state).
     */
    suspend fun verify(context: Context, host: String, strategy: String): Result =
        withContext(Dispatchers.Main) {
            val appContext = context.applicationContext
            val webView = WebView(appContext)
            webView.layout(0, 0, 1080, 1920)
            val settings = webView.settings
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webView.clearCache(true)

            var started = false
            var finished = false
            var hardError: String? = null
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!started) return
                    // Redirects fire this for every hop; the last settle wins.
                    finished = true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!started || !request.isForMainFrame) return
                    if (hardError == null) hardError = error.description?.toString() ?: "network error"
                }
            }

            try {
                val outcome = withTimeoutOrNull(TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        synchronized(this@BlockcheckBrowserVerify) {
                            started = true
                            webView.loadUrl("https://$host/")
                        }
                        // A dedicated watcher turns the two callbacks into one
                        // resume; UI thread stays free.
                        val deadline = System.currentTimeMillis() + TIMEOUT_MS
                        Thread {
                            while (System.currentTimeMillis() < deadline) {
                                if (finished || hardError != null) break
                                Thread.sleep(150)
                            }
                            if (cont.isActive) {
                                cont.resume(hardError ?: "loaded")
                            }
                        }.apply { isDaemon = true; start() }
                    }
                }
                Result(ok = started && finished && hardError == null, detail = outcome ?: "timeout")
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }

}
