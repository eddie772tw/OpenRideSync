package org.openridesync.app.runtime

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.openridesync.app.media.MediaAccessService
import org.openridesync.app.media.CallNotificationSummary

internal data class CallContext(
    val cellularIdle: Boolean = true,
    val cellularEstablished: Boolean = false,
    val phoneObserved: Boolean = false,
    val notificationActive: Boolean = false,
    val notificationUnknown: Boolean = true,
    val listenerConnected: Boolean = false,
    val notificationIncoming: Boolean = false,
) {
    val volumeStream: RideVolume.Stream?
        get() = when {
            !cellularIdle || notificationActive -> RideVolume.Stream.CALL
            notificationUnknown -> null
            else -> RideVolume.Stream.MEDIA
        }
    val musicBlocked: Boolean get() = cellularEstablished || notificationActive
    val musicPopupBlocked: Boolean get() = !cellularIdle || notificationActive || notificationIncoming || notificationUnknown
}

/** Main-thread owner. Permission denial/read failure uses OEM IDLE fallback, visibly unobserved.
 * The native observer needs READ_PHONE_STATE only, not OEM's call-control/log permissions.
 * Caller refreshes on foreground/permission changes; no permission request or phone action. */
internal class RideCallContext(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val phone = this.context.getSystemService(TelephonyManager::class.java)
    private val mutableState = MutableStateFlow(CallContext())
    val state: StateFlow<CallContext> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var modern: TelephonyCallback? = null
    private var legacy: PhoneStateListener? = null
    private var generation = 0L
    private var started = false
    private var closed = false

    fun start() {
        mainThread()
        check(!closed) { "Call context is closed" }
        if (!started) {
            started = true
            if (notificationAccess()) {
                try { MediaAccessService.rebindIfDisconnected(ComponentName(context, MediaAccessService::class.java)) }
                catch (_: RuntimeException) { /* Keep unobserved until a real listener connection. */ }
            }
            notificationJob = scope.launch {
                MediaAccessService.callSummary.collect { summary ->
                    notifications(summary)
                }
            }
        }
        refresh()
    }

    @Suppress("DEPRECATION")
    fun refresh() {
        mainThread()
        if (closed || !started) return
        if (notificationAccess()) MediaAccessService.refreshNotifications()
        notifications(MediaAccessService.callSummary.value)
        if (!permitted() || phone == null) {
            unregister()
            cellular(null)
            return
        }
        try {
            cellular(phone.callState)
            if (modern == null && legacy == null) {
                val current = ++generation
                if (Build.VERSION.SDK_INT >= 31) {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) { observed(current, state) }
                    }
                    modern = callback
                    phone.registerTelephonyCallback(context.mainExecutor, callback)
                } else {
                    val callback = object : PhoneStateListener() {
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) { observed(current, state) }
                    }
                    legacy = callback
                    phone.listen(callback, PhoneStateListener.LISTEN_CALL_STATE)
                }
            }
        } catch (_: RuntimeException) {
            unregister()
            cellular(null)
        }
    }

    private fun permitted() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun notificationAccess(): Boolean = try {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(
                ComponentName(context, MediaAccessService::class.java)) == true
        } catch (_: RuntimeException) { false }

    private fun notifications(summary: CallNotificationSummary) {
        val value = if (notificationAccess()) summary else CallNotificationSummary()
        mutableState.value = mutableState.value.copy(notificationActive = value.active,
            notificationUnknown = value.unknown, listenerConnected = value.listenerConnected,
            notificationIncoming = value.incoming)
    }

    private fun observed(current: Long, value: Int) {
        if (closed || !started || current != generation) return
        if (!permitted()) { unregister(); cellular(null) }
        else cellular(value)
    }

    private fun cellular(value: Int?) {
        mutableState.value = cellularCallContext(value, mutableState.value)
    }

    @Suppress("DEPRECATION")
    private fun unregister() {
        generation++ // Invalidate callbacks already posted before unregister.
        val oldModern = modern
        val oldLegacy = legacy
        modern = null
        legacy = null
        try {
            if (Build.VERSION.SDK_INT >= 31 && oldModern != null) phone?.unregisterTelephonyCallback(oldModern)
            if (oldLegacy != null) phone?.listen(oldLegacy, PhoneStateListener.LISTEN_NONE)
        } catch (_: RuntimeException) { /* State remains unobserved; never retain a stale active call. */ }
    }

    override fun close() {
        mainThread()
        if (closed) return
        closed = true
        started = false
        unregister()
        notificationJob?.cancel()
        scope.cancel()
        mutableState.value = CallContext()
    }

    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
}

/** TelephonyManager constants: IDLE=0, RINGING=1, OFFHOOK=2. Unknown is not an observation. */
internal fun cellularCallContext(value: Int?, previous: CallContext = CallContext()): CallContext =
    previous.copy(cellularIdle = value !in listOf(1, 2), cellularEstablished = value == 2,
        phoneObserved = value != null && value in 0..2)
