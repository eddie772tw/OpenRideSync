package org.openridesync.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.diagnostics.DiagnosticJournal.*

/** Foreground phone window only; no wake lock, unlock, brightness or service changes. */
@Composable
internal fun RideScreenAwake(window: Window, connected: Boolean, mode: ScreenAwakeMode) {
    val context = LocalContext.current.applicationContext
    LifecycleResumeEffect(window, connected, mode) {
        var active = true
        var registered = false
        fun update(enabled: Boolean) {
            if (active) {
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> update(true)
                    Intent.ACTION_POWER_DISCONNECTED -> update(false)
                }
            }
        }
        update(connected && mode == ScreenAwakeMode.ON)
        if (connected && mode == ScreenAwakeMode.ONLY_CHARGING) {
            try {
                val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED).apply { addAction(Intent.ACTION_POWER_DISCONNECTED) }
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(receiver, filter)
                registered = true
                val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                update(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            } catch (_: SecurityException) {
                update(false)
                (context as? OpenRideSyncApplication)?.journal?.record(Component.APP, null,
                    Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.UNAVAILABLE)
            }
        }
        onPauseOrDispose {
            active = false // An already queued callback cannot revive a disposed connection/window.
            if (registered) context.unregisterReceiver(receiver)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
