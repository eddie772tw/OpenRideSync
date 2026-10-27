package org.openridesync.app.runtime

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.location.LocationManager
import kotlinx.coroutines.*
import org.openridesync.app.MainActivity
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.weather.weatherLocationGranted

/** Explicit selected-device connection only. No boot receiver or automatic service restart. */
internal class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ride get() = (application as OpenRideSyncApplication).ride
    private var job: Job? = null
    private var latestStartId = 0
    private var activeAttempt: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS denial does not forbid foreground service notification.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val id = intent?.getStringExtra(ATTEMPT)
        if (intent?.action == STOP) {
            ride.stop(id)
            if (job == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != CONNECT || id == null || !ride.accepts(id)) {
            if (job == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "車輛連線", NotificationManager.IMPORTANCE_LOW))
            // Promotion precedes garage IO, GATT connection and every BT write.
            val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            val types = rideForegroundTypes(weatherLocationGranted(this),
                getSystemService(LocationManager::class.java)?.isLocationEnabled == true,
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            // While-in-use grants/visibility can change before promotion. Keep the other optional
            // capability when possible, and never report a type which Android rejected.
            var promotedTypes = 0
            for (candidate in listOf(types,
                    types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv(),
                    types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION.inv(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE).distinct()) {
                try {
                    startForeground(NOTIFICATION, notification(id, RideStage.CONNECTING), candidate)
                    promotedTypes = candidate
                    break
                } catch (error: SecurityException) {
                    if (candidate == ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) throw error
                }
            }
            ride.promoted(id, promotedTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0,
                promotedTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
            activeAttempt = id
            job = ride.attach(id, scope, notify = {
                manager.notify(NOTIFICATION, notification(id, it.stage))
            }, done = {
                job = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(latestStartId)
            })
            if (job == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            else job?.start() // Assign first: even immediate cancellation must not retain a completed job.
        } catch (_: SecurityException) {
            ride.promotionFailed(id, "PERMISSION_REQUIRED")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
        } catch (_: RuntimeException) {
            ride.promotionFailed(id, "SERVICE_START_FAILED")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun notification(id: String, stage: RideStage): Notification {
        val stop = PendingIntent.getService(this, 1,
            Intent(this, RideService::class.java).setAction(STOP)
                .setData(Uri.parse("openridesync-internal://ride/$id")).putExtra(ATTEMPT, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.OPEN_RIDE)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenRideSync 車輛連線")
            .setContentText(when (stage) {
                RideStage.CONNECTED -> "車輛連線已驗證"
                RideStage.RECONNECTING -> "正在重新連接車輛…"
                RideStage.STOPPING -> "正在中斷車輛連線…"
                else -> "正在連接車輛…"
            })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "中斷連線", stop).build()).build()
    }

    override fun onDestroy() {
        ride.foregroundEnded(activeAttempt)
        scope.cancel() // Runtime finally closes transports before its completion releases the gate.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CONNECT = "org.openridesync.app.runtime.CONNECT"
        const val STOP = "org.openridesync.app.runtime.STOP"
        const val ATTEMPT = "attempt"
        private const val CHANNEL = "vehicle-connection"
        private const val NOTIFICATION = 1001
    }
}

internal fun rideForegroundTypes(locationGranted: Boolean, locationEnabled: Boolean, visible: Boolean,
    microphoneGranted: Boolean = false): Int =
    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
        (if (locationGranted && locationEnabled && visible) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) or
        (if (microphoneGranted && visible) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
