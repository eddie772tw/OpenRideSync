package org.openridesync.app.calls

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openridesync.app.media.MediaAccessService
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.UUID

internal enum class CallAccess { ACCESS_REQUIRED, UNAVAILABLE, READY }
internal enum class CallAction { ANSWER, DECLINE, HANG_UP }
internal enum class CallKind { INCOMING, ONGOING }
internal data class CallItem(val id: String, val appName: String, val packageName: String,
    val caller: String, val kind: CallKind, val actions: Set<CallAction>, val isVideo: Boolean)
internal data class CallInboxState(val access: CallAccess = CallAccess.UNAVAILABLE,
    val calls: List<CallItem> = emptyList())
internal enum class CallDispatchResult { DISPATCHED, STALE, UNSUPPORTED, ACCESS_REQUIRED, UNAVAILABLE, LOCKED }

/** Explicit phone-page or ride owner. Sources and PendingIntents stay in RAM until the owner closes.
 * A dispatched request is not proof the source app answered, declined or ended its call. */
internal class NativeCalls(context: Context, private val journal: DiagnosticJournal? = null,
    private val allowLockedControl: () -> Boolean = { false },
) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val keyguard = this.context.getSystemService(KeyguardManager::class.java)
    private val component = ComponentName(this.context, MediaAccessService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(CallInboxState())
    val state: StateFlow<CallInboxState> = mutableState.asStateFlow()
    private var entries = emptyMap<String, CallSource>()
    private val spent = mutableSetOf<String>()
    private var running = false
    private var closed = false

    fun start() {
        mainThread()
        check(!closed)
        if (running) return
        running = true
        scope.launch { MediaAccessService.changes.drop(1).collect { refresh() } }
        scope.launch {
            var granted = hasAccess()
            while (running) {
                delay(1_000)
                val current = hasAccess()
                if (current != granted) {
                    granted = current
                    refresh()
                    if (current) rebind()
                }
            }
        }
        refresh()
        if (hasAccess()) rebind()
    }

    fun refresh() {
        mainThread()
        if (!running || closed) return
        try {
            if (manager == null) { clear(CallAccess.UNAVAILABLE); return }
            if (!hasAccess()) { clear(CallAccess.ACCESS_REQUIRED); return }
            val notifications = MediaAccessService.readActiveNotifications()
                ?: run { clear(CallAccess.UNAVAILABLE); return }
            if (notifications.size > 512) { clear(CallAccess.UNAVAILABLE); return }
            entries = reconcileCalls(entries, notifications)
            spent.retainAll(entries.keys)
            mutableState.value = CallInboxState(CallAccess.READY, entries.values.map {
                CallItem(it.id, appLabel(it.signature.packageName), it.signature.packageName,
                    it.signature.caller, it.signature.kind,
                    if (it.id in spent) emptySet() else it.signature.actions.keys, it.signature.isVideo)
            })
        } catch (_: SecurityException) { clear(CallAccess.ACCESS_REQUIRED) }
        catch (_: RuntimeException) { clear(CallAccess.UNAVAILABLE) }
    }

    fun dispatch(id: String, action: CallAction): CallDispatchResult {
        mainThread()
        val operation = journal?.newOperation()
        journal?.record(Component.NOTIFICATION, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
        val result = try {
            val before = entries[id]
            refresh()
            when {
                !running || closed -> CallDispatchResult.UNAVAILABLE
                state.value.access == CallAccess.ACCESS_REQUIRED -> CallDispatchResult.ACCESS_REQUIRED
                state.value.access != CallAccess.READY -> CallDispatchResult.UNAVAILABLE
                before == null || entries[id]?.signature != before.signature || id in spent -> CallDispatchResult.STALE
                keyguard?.isDeviceLocked != false && !allowLockedControl() -> CallDispatchResult.LOCKED
                before.signature.actions[action] == null -> CallDispatchResult.UNSUPPORTED
                else -> {
                    val dispatched = dispatchCall(context, checkNotNull(before.signature.actions[action]), spent, id)
                    mutableState.value = mutableState.value.copy(calls = mutableState.value.calls.map {
                        if (it.id == id) it.copy(actions = emptySet()) else it
                    })
                    dispatched
                }
            }
        } catch (_: SecurityException) { clear(CallAccess.ACCESS_REQUIRED); CallDispatchResult.ACCESS_REQUIRED }
        catch (_: RuntimeException) { clear(CallAccess.UNAVAILABLE); CallDispatchResult.UNAVAILABLE }
        val code = when (result) {
            CallDispatchResult.DISPATCHED -> ErrorCode.NONE
            CallDispatchResult.STALE -> ErrorCode.ABORTED
            CallDispatchResult.UNSUPPORTED -> ErrorCode.UNSUPPORTED
            CallDispatchResult.ACCESS_REQUIRED -> ErrorCode.PERMISSION_DENIED
            CallDispatchResult.UNAVAILABLE -> ErrorCode.UNAVAILABLE
            CallDispatchResult.LOCKED -> ErrorCode.FAILED_PRECONDITION
        }
        journal?.record(Component.NOTIFICATION, operation, Event.REQUEST,
            if (result == CallDispatchResult.DISPATCHED) Outcome.OBSERVED else Outcome.FAILED, code)
        return result
    }

    private fun appLabel(packageName: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
            .toString().take(512)
    } catch (_: RuntimeException) { packageName }
      catch (_: android.content.pm.PackageManager.NameNotFoundException) { packageName }
    private fun hasAccess() = try { manager?.isNotificationListenerAccessGranted(component) == true }
        catch (_: RuntimeException) { false }
    private fun rebind() {
        try { MediaAccessService.rebindIfDisconnected(component) } catch (_: RuntimeException) { }
    }
    private fun clear(access: CallAccess) {
        entries = emptyMap(); spent.clear(); mutableState.value = CallInboxState(access)
    }
    override fun close() {
        mainThread()
        if (closed) return
        closed = true; running = false; scope.cancel(); clear(CallAccess.UNAVAILABLE)
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
}

internal data class CallSignature(val key: String, val packageName: String, val postTime: Long,
    val whenTime: Long, val caller: String, val kind: CallKind, val isVideo: Boolean,
    val actions: Map<CallAction, PendingIntent>)
internal data class CallSource(val signature: CallSignature, val id: String = "")

internal fun reconcileCalls(previous: Map<String, CallSource>, notifications: List<StatusBarNotification>): Map<String, CallSource> {
    val prior = previous.values.associateBy { it.signature }
    val duplicates = notifications.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
    return notifications.sortedByDescending { it.postTime }.asSequence()
        .filter { it.key !in duplicates }.mapNotNull(::parseCall).take(50).map {
            it.copy(id = prior[it.signature]?.id ?: UUID.randomUUID().toString())
        }.associateBy { it.id }
}

/** Public CallStyle extras have exact action semantics; no action-index or translated-title guesses.
 * API34 supplies public EXTRA_CALL_TYPE. Older and screening/unknown calls remain unsupported. */
internal fun parseCall(sbn: StatusBarNotification): CallSource? {
    return try {
    if (Build.VERSION.SDK_INT < 34 || sbn.user != Process.myUserHandle()) null
    else {
        val n = sbn.notification
        val extras = n.extras
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            extras.getString(Notification.EXTRA_TEMPLATE) != Notification.CallStyle::class.java.name) return null
        val kind = when (extras.getInt(Notification.EXTRA_CALL_TYPE, 0)) {
            1 -> CallKind.INCOMING
            2 -> CallKind.ONGOING
            else -> return null
        }
        val person = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java) ?: return null
        val caller = person.name?.toString()?.trim().orEmpty()
        if (caller.isBlank() || caller.length > 512) return null
        val keys = if (kind == CallKind.INCOMING) mapOf(CallAction.ANSWER to Notification.EXTRA_ANSWER_INTENT,
            CallAction.DECLINE to Notification.EXTRA_DECLINE_INTENT)
            else mapOf(CallAction.HANG_UP to Notification.EXTRA_HANG_UP_INTENT)
        val actions = linkedMapOf<CallAction, PendingIntent>()
        if (n.actions.orEmpty().size > 16) return null
        for ((action, key) in keys) {
            if (!extras.containsKey(key)) return null
            val pending = extras.getParcelable(key, PendingIntent::class.java) ?: return null
            if (pending.creatorPackage != sbn.packageName || pending.creatorUserHandle != sbn.user ||
                n.actions.orEmpty().any { it.actionIntent == pending && !it.remoteInputs.isNullOrEmpty() }) return null
            actions[action] = pending
        }
        if (actions.isEmpty() || actions.values.distinct().size != actions.size) return null
        CallSource(CallSignature(sbn.key, sbn.packageName, sbn.postTime, n.`when`, caller, kind,
            extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO), actions))
    }
    } catch (_: RuntimeException) { null }
}

internal fun dispatchCall(context: Context, pending: PendingIntent, spent: MutableSet<String>, id: String): CallDispatchResult {
    if (!spent.add(id)) return CallDispatchResult.STALE
    return try {
        pending.send(context, 0, null)
        CallDispatchResult.DISPATCHED
    } catch (_: PendingIntent.CanceledException) { CallDispatchResult.STALE }
      catch (_: RuntimeException) { CallDispatchResult.UNAVAILABLE }
}
