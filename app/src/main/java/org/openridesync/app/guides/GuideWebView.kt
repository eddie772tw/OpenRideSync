package org.openridesync.app.guides

import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode

internal enum class GuideRenderError(val diagnostic: ErrorCode) {
    SETUP(ErrorCode.UNAVAILABLE), BLOCKED_DOCUMENT(ErrorCode.PERMISSION_DENIED),
    UNEXPECTED_DOCUMENT(ErrorCode.DATA_LOSS), NETWORK(ErrorCode.IO), HTTP(ErrorCode.IO),
    SSL(ErrorCode.PERMISSION_DENIED), RENDERER_GONE(ErrorCode.UNAVAILABLE),
}

/** Trusted Honda HTML, with its downloaded HTTPS URL as base; no native bridge or auth headers. */
@Composable
internal fun GuideWebView(
    document: GuideDocument,
    modifier: Modifier = Modifier,
    onLoaded: () -> Unit,
    onError: (GuideRenderError) -> Unit,
    onFinish: (() -> Unit)? = null,
) {
    val loadedCallback = rememberUpdatedState(onLoaded)
    val errorCallback = rememberUpdatedState(onError)
    val finishCallback = rememberUpdatedState(onFinish)
    val textZoom = (min(LocalConfiguration.current.fontScale, 1.35f) * 100).roundToInt()
    key(document.url, document.html) {
        val alive = remember { AtomicBoolean(true) }
        val failed = remember { AtomicBoolean(false) }
        val delivered = remember { AtomicBoolean(false) }
        val finishDelivered = remember { AtomicBoolean(false) }
        val initialDocument = remember { AtomicBoolean(true) }
        val main = remember { Handler(Looper.getMainLooper()) }
        fun fail(reason: GuideRenderError) {
            if (failed.compareAndSet(false, true)) main.post { if (alive.get()) errorCallback.value(reason) }
        }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                FrameLayout(context).apply {
                    try {
                        val origin = GuideUrlPolicy.requireHttps(document.url)
                        val web = WebView(context)
                        addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        web.settings.apply {
                            javaScriptEnabled = true // OEM ResetScreen contract; no addJavascriptInterface.
                            domStorageEnabled = false
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            safeBrowsingEnabled = true
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                            this.textZoom = textZoom
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
                        web.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (request.isForMainFrame && finishCallback.value != null && GuideUrlPolicy.isTutorialAction(url)) {
                                    if (alive.get() && !failed.get() && GuideUrlPolicy.isTutorialFinish(url) && finishDelivered.compareAndSet(false, true))
                                        finishCallback.value?.invoke()
                                    return true
                                }
                                return if (request.isForMainFrame) !GuideUrlPolicy.sameOrigin(origin, url) else !isHttps(url)
                            }

                            @Deprecated("Legacy WebView callback")
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                                !GuideUrlPolicy.sameOrigin(origin, url)

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                // Chromium reports loadDataWithBaseURL's own HTML as a data: main
                                // request. Only that first request may be inline; later top-level
                                // requests still need the verified HTTPS origin.
                                val initialInline = request.isForMainFrame && initialDocument.compareAndSet(true, false) &&
                                    request.url.scheme.equals("data", ignoreCase = true)
                                val allowed = if (request.isForMainFrame) initialInline || GuideUrlPolicy.sameOrigin(origin, request.url.toString())
                                    else isHttps(request.url.toString()) || request.url.toString().startsWith("data:image/", ignoreCase = true)
                                if (allowed) return null
                                if (request.isForMainFrame) fail(GuideRenderError.BLOCKED_DOCUMENT)
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                initialDocument.set(false)
                                if (!GuideUrlPolicy.sameOrigin(origin, url)) { fail(GuideRenderError.UNEXPECTED_DOCUMENT); return }
                                // Download completion is not rendering. Wait until the DOM is ready to draw.
                                view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        if (alive.get() && !failed.get() && delivered.compareAndSet(false, true)) loadedCallback.value()
                                    }
                                })
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) fail(GuideRenderError.NETWORK)
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                if (request.isForMainFrame) fail(GuideRenderError.HTTP)
                            }

                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel()
                                fail(GuideRenderError.SSL)
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                fail(GuideRenderError.RENDERER_GONE)
                                (view.parent as? ViewGroup)?.removeView(view)
                                view.destroy()
                                return true
                            }
                        }
                        // Keep the downloaded URL in history as well as the relative-resource base.
                        web.loadDataWithBaseURL(origin.toString(), document.html, "text/html", "UTF-8", origin.toString())
                    } catch (_: Exception) { fail(GuideRenderError.SETUP) }
                }
            },
            update = { frame -> (frame.getChildAt(0) as? WebView)?.settings?.textZoom = textZoom },
            onRelease = { frame ->
                alive.set(false)
                val web = frame.getChildAt(0) as? WebView
                frame.removeAllViews()
                web?.stopLoading()
                web?.destroy()
            },
        )
    }
}

private fun isHttps(value: String): Boolean = try { GuideUrlPolicy.requireHttps(value); true }
catch (_: IllegalArgumentException) { false }
