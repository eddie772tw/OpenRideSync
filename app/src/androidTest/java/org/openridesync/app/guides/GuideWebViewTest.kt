package org.openridesync.app.guides

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic inline documents only: no guide downloader, Google sign-in, Honda RPC or screenshot.
 * Requires an installed WebView provider and an Android device/emulator; compilation is not execution.
 */
class GuideWebViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val loaded = AtomicInteger()
    private val errors = AtomicInteger()
    private val lastError = AtomicReference<GuideRenderError>()
    private val finishes = AtomicInteger()

    private fun showDocument(): WebView {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { fontScale = 2f }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    GuideWebView(
                        GuideDocument("https://synthetic-guides.example.invalid/setup/index.html", """
                            <!doctype html><html lang="zh-Hant"><head><meta charset="utf-8"></head>
                            <body><p id="message">原生教學：繁體中文</p><a id="relative" href="steps/next.html">下一步</a>
                            <script>window.syntheticScript = 'script executed';</script></body></html>
                        """.trimIndent()),
                        Modifier.fillMaxSize(),
                        onLoaded = { loaded.incrementAndGet() },
                        onError = { lastError.set(it); errors.incrementAndGet() },
                        onFinish = { finishes.incrementAndGet() },
                    )
                }
            }
        }
        compose.waitUntil(15_000) { loaded.get() > 0 || errors.get() > 0 }
        assertEquals("inline document must render without an external download; failure=${lastError.get()}", 0, errors.get())
        assertEquals(1, loaded.get())
        val view = AtomicReference<WebView>()
        compose.runOnUiThread {
            view.set(findWebView(compose.activity.window.decorView))
            // The HTML has no network subresources; also block accidental loads during navigation checks.
            view.get().settings.blockNetworkLoads = true
        }
        return checkNotNull(view.get())
    }

    @Test fun inlineUtf8BaseResolutionAndNativeSettingsMatchContract() {
        val web = showDocument()
        assertEquals("原生教學：繁體中文", evaluate(web, "document.getElementById('message').textContent"))
        assertEquals("script executed", evaluate(web, "window.syntheticScript"))
        assertEquals("https://synthetic-guides.example.invalid/setup/index.html", evaluate(web, "document.baseURI"))
        assertEquals("https://synthetic-guides.example.invalid/setup/steps/next.html", evaluate(web, "document.getElementById('relative').href"))
        compose.runOnUiThread {
            assertEquals("https://synthetic-guides.example.invalid/setup/index.html", web.url)
            assertTrue(web.settings.javaScriptEnabled)
            assertFalse(web.settings.domStorageEnabled)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertFalse(web.settings.javaScriptCanOpenWindowsAutomatically)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.settings.mixedContentMode)
            assertEquals(135, web.settings.textZoom)
            assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web))
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams.height)
        }
    }

    @Test fun tutorialNavigationConsumesUnknownActionsAndFinishesOnlyOnceForExactHost() {
        val web = showDocument()
        evaluate(web, "location.href='roadsync://different-host?action=tapped_finish_button'; 'queued'")
        evaluate(web, "location.href='roadsync://webview-tutorial?action=unknown_action&screenName=synthetic'; 'queued'")
        assertEquals("navigation policy must keep the inline document", "原生教學：繁體中文", evaluate(web, "document.getElementById('message').textContent"))
        assertEquals(0, finishes.get())
        assertEquals(0, errors.get())
        evaluate(web, "location.href='roadsync://webview-tutorial?action=tapped_finish_button&screenName=synthetic'; 'queued'")
        compose.waitUntil(5_000) { finishes.get() == 1 }
        evaluate(web, "location.href='roadsync://webview-tutorial?action=tapped_finish_button'; 'queued'")
        assertEquals("script executed", evaluate(web, "window.syntheticScript"))
        assertEquals(1, finishes.get())
        assertEquals(0, errors.get())
    }

    @Test fun laterInlineDocumentIsRejected() {
        val web = showDocument()
        compose.runOnUiThread { web.loadUrl("data:text/html,<html><body>Untrusted replacement</body></html>") }
        compose.waitUntil(5_000) { errors.get() > 0 }
        assertEquals(GuideRenderError.BLOCKED_DOCUMENT, lastError.get())
        assertEquals(1, errors.get())
        assertEquals(1, loaded.get())
        assertEquals(0, finishes.get())
    }

    private fun evaluate(web: WebView, script: String): Any? {
        val done = CountDownLatch(1)
        val answer = AtomicReference<String>()
        compose.runOnUiThread {
            web.evaluateJavascript(script) { value -> answer.set(value); done.countDown() }
        }
        assertTrue("synthetic JavaScript callback timed out", done.await(5, TimeUnit.SECONDS))
        return JSONTokener(answer.get()).nextValue()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
