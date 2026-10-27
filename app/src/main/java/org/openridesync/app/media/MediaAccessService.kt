package org.openridesync.app.media

import android.app.Notification
import android.os.Build
import android.os.Looper
import android.media.session.MediaSession
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class CallNotificationKind { IGNORE, INACTIVE, ACTIVE, UNKNOWN }

/** Only public CallStyle type metadata is authoritative here; no OEM app-specific parser. */
internal fun classifyCallNotification(sdk: Int, callStyle: Boolean, categoryCall: Boolean, callType: Int?): CallNotificationKind {
    if (!callStyle) return if (categoryCall) CallNotificationKind.UNKNOWN else CallNotificationKind.IGNORE
    if (sdk < 34) return CallNotificationKind.UNKNOWN
    return when (callType) {
        1, 3 -> CallNotificationKind.INACTIVE // Incoming / screening, not an established call.
        2 -> CallNotificationKind.ACTIVE // Notification.CallStyle.CALL_TYPE_ONGOING.
        else -> CallNotificationKind.UNKNOWN
    }
}

internal data class CallNotificationSummary(
    val active: Boolean = false,
    val unknown: Boolean = true,
    val listenerConnected: Boolean = false,
    val incoming: Boolean = false,
)

internal data class NotificationMediaSession(val packageName: String, val token: MediaSession.Token)

/** Public MediaStyle token only; malformed/foreign parcelables are not media controllers. */
internal fun notificationMediaSession(sbn: StatusBarNotification): NotificationMediaSession? = try {
    val extras = sbn.notification.extras
    if (extras.getString(Notification.EXTRA_TEMPLATE) != Notification.MediaStyle::class.java.name) null
    else {
        val token = if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            else @Suppress("DEPRECATION") (extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token)
        token?.let { NotificationMediaSession(sbn.packageName, it) }
    }
} catch (_: RuntimeException) { null }

/** Retains ephemeral call classification and MediaStyle package/token pairs per notification key.
 * Never reads title, text, Person or actions; nothing here is persisted or included in diagnostics. */
internal class MediaAccessService : NotificationListenerService() {
    private val calls = mutableMapOf<String, CallNotificationKind>()
    private val media = mutableMapOf<String, NotificationMediaSession>()
    private var observed = false
    private var destroyed = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (destroyed) return
        owner = this
        snapshot()
        changes.value++
    }

    override fun onListenerDisconnected() {
        forget()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        destroyed = true
        forget()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (owner !== this || destroyed) return
        if (!observed) { snapshot(); return }
        try {
            if (sbn == null) throw IllegalStateException()
            update(sbn)
            publish()
        } catch (_: RuntimeException) { unobserved() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (owner !== this || destroyed) return
        if (!observed) { snapshot(); return }
        try {
            if (sbn == null) throw IllegalStateException()
            calls.remove(sbn.key)
            media.remove(sbn.key)
            publish()
        } catch (_: RuntimeException) { unobserved() }
    }

    private fun snapshot() {
        try {
            calls.clear()
            media.clear()
            val notifications = activeNotifications ?: throw IllegalStateException()
            notifications.forEach(::update)
            observed = true
            publish()
        } catch (_: RuntimeException) { unobserved() }
    }

    private fun update(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: throw IllegalStateException()
        val style = Build.VERSION.SDK_INT >= 31 &&
            notification.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name
        val type = if (style && Build.VERSION.SDK_INT >= 34)
            notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else null
        val kind = classifyCallNotification(Build.VERSION.SDK_INT, style,
            notification.category == Notification.CATEGORY_CALL, type)
        if (kind == CallNotificationKind.IGNORE) calls.remove(sbn.key)
        else {
            if (!calls.containsKey(sbn.key) && calls.size >= 512) throw IllegalStateException()
            calls[sbn.key] = kind
        }
        val session = notificationMediaSession(sbn)
        if (session == null) media.remove(sbn.key)
        else {
            if (!media.containsKey(sbn.key) && media.size >= 512) throw IllegalStateException()
            media[sbn.key] = session
        }
    }

    private fun publish() {
        if (owner !== this) return
        mutableCallSummary.value = CallNotificationSummary(
            active = calls.containsValue(CallNotificationKind.ACTIVE),
            unknown = !observed || calls.containsValue(CallNotificationKind.UNKNOWN),
            listenerConnected = true,
            incoming = calls.containsValue(CallNotificationKind.INACTIVE),
        )
        val sessions = media.values.distinct()
        notificationMediaSessions = sessions
        changes.value++ // Message pages also need content-only updates and removals.
    }

    private fun unobserved() {
        calls.clear()
        media.clear()
        observed = false
        publish()
        changes.value++
    }

    private fun forget() {
        calls.clear()
        media.clear()
        observed = false
        if (owner !== this) return
        owner = null
        mutableCallSummary.value = CallNotificationSummary()
        notificationMediaSessions = emptyList()
        changes.value++
    }

    companion object {
        // Platform listener lifecycle is process-wide; each bounded media instance observes it.
        internal val changes = MutableStateFlow(0L)
        private var owner: MediaAccessService? = null
        private val mutableCallSummary = MutableStateFlow(CallNotificationSummary())
        internal val callSummary: StateFlow<CallNotificationSummary> = mutableCallSummary.asStateFlow()
        internal var notificationMediaSessions: List<NotificationMediaSession> = emptyList()
            private set

        /** Read only for an active page owner; the listener never retains message bodies/actions. */
        internal fun readActiveNotifications(): List<StatusBarNotification>? {
            check(Looper.myLooper() == Looper.getMainLooper())
            val current = owner?.takeIf { !it.destroyed } ?: return null
            return try { current.activeNotifications?.takeIf { it.size <= 512 }?.toList() }
            catch (_: RuntimeException) { null }
        }

        /** Main-thread only. Retry a failed snapshot; no notification API before listener connection. */
        internal fun refreshNotifications() {
            check(Looper.myLooper() == Looper.getMainLooper())
            owner?.takeIf { !it.destroyed && !it.observed }?.snapshot()
        }

        /** Caller checks listener access; request only once at observer start, never in its poll. */
        internal fun rebindIfDisconnected(component: android.content.ComponentName) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (owner == null) NotificationListenerService.requestRebind(component)
        }
    }
}
