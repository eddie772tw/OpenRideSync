package org.openridesync.app.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** Explicit, nonexported activity. Result contains no account identifiers or credentials. */
class BrowserAuthActivity : ComponentActivity() {
    private lateinit var model: BrowserAuthViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)
        model = ViewModelProvider(this)[BrowserAuthViewModel::class.java]
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        val label = TextView(this)
        val retry = Button(this).apply {
            text = "重新開啟瀏覽器"
            setOnClickListener { model.url()?.let(::openBrowser) }
        }
        val cancel = Button(this).apply {
            text = "返回主畫面／取消"
            setOnClickListener { model.cancel(); finish() }
        }
        panel.addView(label); panel.addView(retry); panel.addView(cancel)
        setContentView(panel)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Native exchange starts only after this activity really reaches RESUMED.
                model.setVisible(true)
                model.state.collect {
                    label.text = it.message
                    retry.isEnabled = it.canOpen
                    if (it.terminal) { setResult(if (it.success) RESULT_OK else RESULT_CANCELED); finish() }
                }
            }
        }
        model.initialUrl()?.let(::openBrowser)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Delivery may precede foregrounding. The RESUMED collector completes the return.
    }

    override fun onPause() { model.setVisible(false); super.onPause() }

    private fun openBrowser(url: String) {
        try {
            val browser = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            browser.putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
            browser.setPackage("com.android.chrome")
            if (browser.resolveActivity(packageManager) == null) browser.setPackage(null)
            startActivity(browser)
        } catch (_: Exception) { model.browserUnavailable() }
    }
}
