package org.openridesync.app.auth

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.openridesync.authbridge.Loopback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class BrowserState(val message: String, val canOpen: Boolean = false, val success: Boolean = false, val terminal: Boolean = false)

/** Owns one memory-only browser receiver across rotation; no credentials in saved state. */
class BrowserAuthViewModel(application: Application) : AndroidViewModel(application) {
    private val main = Handler(Looper.getMainLooper())
    private val session = AuthSession.get()
    private val mutableState = MutableStateFlow(BrowserState("正在準備 Google 登入…"))
    internal val state = mutableState.asStateFlow()
    private var server: Loopback? = null
    private var operation: String? = null
    private val received = AtomicBoolean(false)
    private val result = AtomicReference<Loopback.Result?>(null)
    private val completed = CountDownLatch(1)
    @Volatile private var closed = false
    private var opened = false
    private var visible = false
    // Main-thread only, memory-only; never saved in state, diagnostics or an Intent.
    private var queuedToken: String? = null
    private var submitted = false
    private val timeout = Runnable {
        if (!closed && complete(Loopback.Result.TIMEOUT)) {
            queuedToken = null
            operation?.let(session::cancel)
            server?.close()
            finishTerminal(Loopback.Result.TIMEOUT)
        }
    }

    init {
        try {
            val config = checkNotNull(session.config)
            operation = session.begin()
            val page = application.assets.open("openridesync-login.html").use { input ->
                val bytes = input.readBytes()
                require(bytes.size < 65536)
                bytes.toString(Charsets.UTF_8).replace("__FIREBASE_CONFIG__", config.browserJson()).toByteArray(Charsets.UTF_8)
            }
            server = Loopback(page, { raw ->
                val token = runCatching { validateGoogleCredential(raw, config.webClientId) }.getOrNull()
                if (token == null || closed || !received.compareAndSet(false, true)) Loopback.Result.INVALID
                else {
                    main.post {
                        if (closed || result.get() != null) complete(Loopback.Result.CANCELLED)
                        else {
                            queuedToken = token
                            mutableState.value = BrowserState("Google 憑證已接收，正在返回 App 進行原生 Firebase 驗證…")
                            if (visible) submitQueuedToken() else returnToApp()
                        }
                    }
                    val arrived = try { completed.await(25, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
                    if (!arrived && complete(Loopback.Result.TIMEOUT)) {
                        main.post { queuedToken = null; operation?.let(session::cancel) }
                    }
                    result.get() ?: Loopback.Result.TIMEOUT
                }
            }, { terminal -> main.post { finishTerminal(terminal) } })
            mutableState.value = BrowserState("正在開啟 Google 登入；完成後會自動返回。", canOpen = true)
            main.postDelayed(timeout, 300000)
            Thread({
                server?.serve()
                main.post {
                    if (!closed && !received.get()) timeout.run()
                }
            }, "OpenRideSyncBrowserAuth").start()
        } catch (_: Exception) {
            operation?.let(session::cancel)
            server?.close()
            mutableState.value = BrowserState("登入設定或本機接收器不可用。請返回主畫面確認設定。")
        }
    }

    internal fun initialUrl(): String? = if (opened) null else url().also { opened = true }
    internal fun url(): String? = if (!closed && !received.get()) server?.url() else null
    internal fun browserUnavailable() { mutableState.value = BrowserState("無法開啟瀏覽器。請按重新開啟，或返回主畫面。", canOpen = true) }
    internal fun setVisible(value: Boolean) {
        visible = value
        if (value) submitQueuedToken()
    }

    private fun submitQueuedToken() {
        if (!visible || closed || submitted || result.get() != null) {
            if (closed || result.get() != null) queuedToken = null
            return
        }
        val token = queuedToken ?: return
        queuedToken = null
        submitted = true
        mutableState.value = BrowserState("App 已恢復前景，正在由原專案 Firebase 驗證…")
        session.submit(checkNotNull(operation), token) { success ->
            val nativeResult = if (success) Loopback.Result.SUCCEEDED else Loopback.Result.FAILED
            if (!complete(nativeResult) && success && result.get() != Loopback.Result.SUCCEEDED)
                session.signOut() // A timeout won the race; do not revive the cancelled session.
        }
    }

    private fun returnToApp() {
        runCatching {
            val application = getApplication<Application>()
            application.startActivity(Intent(application, BrowserAuthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun complete(value: Loopback.Result): Boolean {
        val first = result.compareAndSet(null, value)
        completed.countDown()
        return first
    }

    private fun finishTerminal(value: Loopback.Result) {
        if (closed) return
        closed = true
        queuedToken = null
        main.removeCallbacks(timeout)
        // Both Firebase success and failure return. A transport receipt alone is never success.
        if (!visible) returnToApp()
        mutableState.value = BrowserState(when (value) {
            Loopback.Result.SUCCEEDED -> "Firebase 登入完成；Honda 帳號讀取仍須另外驗證。"
            Loopback.Result.FAILED -> "原生 Firebase 驗證失敗；請在主畫面查看登入狀態並重新登入。"
            Loopback.Result.CANCELLED -> "登入已取消。"
            else -> "原生登入驗證已逾時；請返回主畫面重新登入。"
        }, success = value == Loopback.Result.SUCCEEDED, terminal = true)
    }

    internal fun cancel() {
        if (closed) return
        closed = true
        queuedToken = null
        complete(Loopback.Result.CANCELLED)
        main.removeCallbacks(timeout)
        server?.close()
        operation?.let(session::cancel)
    }

    override fun onCleared() { cancel(); super.onCleared() }

    companion object {
        internal fun validateGoogleCredential(raw: String, audience: String): String {
            val payload = JSONObject(raw)
            require(payload.length() == 1)
            val token = payload.getString("googleIdToken")
            require(token.length in 1..8192 && token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
            val claims = JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
            val now = System.currentTimeMillis() / 1000
            require(claims.getString("iss") in setOf("accounts.google.com", "https://accounts.google.com"))
            require(claims.getString("aud") == audience && claims.getString("sub").isNotEmpty())
            require(claims.getLong("exp") > now && claims.getLong("iat") <= now + 30)
            // Shape/expiry guards only; the native Firebase SDK verifies this Google credential.
            return token
        }
    }
}
