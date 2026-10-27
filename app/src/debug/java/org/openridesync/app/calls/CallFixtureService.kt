package org.openridesync.app.calls

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper

/** Debug-only foreground launch surface; no production screens, account or ride state. */
class CallFixtureActivity : Activity()

/** Holds only the test's synthetic notification. No Telecom, audio or real call operation. */
class CallFixtureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        running = true
        handler.postDelayed(expire, 60_000) // Fail-safe when instrumentation is interrupted.
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 34) { stopSelf(); return START_NOT_STICKY }
        val notification = intent?.getParcelableExtra(NOTIFICATION, Notification::class.java)
        if (notification == null) { stopSelf(); return START_NOT_STICKY }
        // Real service promotion satisfies Android's CallStyle posting requirement.
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int) { stopSelf() }
    override fun onDestroy() {
        handler.removeCallbacks(expire)
        stopForeground(STOP_FOREGROUND_REMOVE)
        running = false
        super.onDestroy()
    }
    companion object {
        const val NOTIFICATION = "synthetic_notification"
        const val ID = 0x4f5253
        @Volatile var running = false
            private set
    }
}
