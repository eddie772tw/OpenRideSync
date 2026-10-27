package org.openridesync.app.calls

import android.app.*
import android.content.*
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.openridesync.app.media.MediaAccessService
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CallPendingIntentShadow::class])
class NativeCallsControlTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var listener: MediaAccessService
    private val owners = mutableListOf<NativeCalls>()
    private var deliveries = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { deliveries++ }
    }

    private fun notification(ongoing: Boolean = false): StatusBarNotification {
        fun pending(id: Int) = PendingIntent.getBroadcast(context, id,
            Intent("call.control.synthetic").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val person = Person.Builder().setName("Synthetic caller").build()
        val notification = Notification.Builder(context, "synthetic").setWhen(42)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(if (ongoing)
                Notification.CallStyle.forOngoingCall(person, pending(3))
            else Notification.CallStyle.forIncomingCall(person, pending(1), pending(2))).build()
        return StatusBarNotification(context.packageName, context.packageName, 1, "call", Process.myUid(),
            Process.myPid(), 0, notification, Process.myUserHandle(), 42L)
    }

    @Before fun setup() {
        context.registerReceiver(receiver, IntentFilter("call.control.synthetic"), Context.RECEIVER_NOT_EXPORTED)
        listener = Robolectric.buildService(MediaAccessService::class.java).create().get()
        Shadows.shadowOf(listener).addActiveNotification(notification())
        listener.onListenerConnected()
        access(true)
        Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
    }

    private fun access(granted: Boolean) = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        .setNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java), granted)
    private fun owner(allowLocked: () -> Boolean = { false }) = NativeCalls(context, allowLockedControl = allowLocked)
        .also { owners += it; it.start() }

    @After fun cleanup() {
        owners.forEach { it.close() }
        listener.onListenerDisconnected()
        listener.onDestroy()
        context.unregisterReceiver(receiver)
    }

    @Test fun physicalOwnerMayActLockedButPhoneCannotAndDispatchNeverChangesObservedKind() {
        val phone = owner()
        assertEquals(CallDispatchResult.LOCKED, phone.dispatch(phone.state.value.calls.single().id, CallAction.ANSWER))
        var eligible = false
        val ride = owner { eligible }
        val id = ride.state.value.calls.single().id
        assertEquals(CallDispatchResult.LOCKED, ride.dispatch(id, CallAction.ANSWER))
        eligible = true
        assertEquals(CallDispatchResult.DISPATCHED, ride.dispatch(id, CallAction.ANSWER))
        ride.refresh()
        assertEquals(CallKind.INCOMING, ride.state.value.calls.single().kind)
        assertTrue(ride.state.value.calls.single().actions.isEmpty())
        assertEquals(CallDispatchResult.STALE, ride.dispatch(id, CallAction.DECLINE))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, deliveries)
    }

    @Test fun physicalPermissionCannotBypassSourceChangeAccessLossOrClosedOwner() {
        val ride = owner { true }
        val id = ride.state.value.calls.single().id
        val changed = notification(true)
        listener.cancelNotification(changed.key)
        Shadows.shadowOf(listener).addActiveNotification(changed)
        assertEquals(CallDispatchResult.STALE, ride.dispatch(id, CallAction.ANSWER))
        val current = ride.state.value.calls.single().id
        assertNotEquals(id, current)
        access(false)
        assertEquals(CallDispatchResult.ACCESS_REQUIRED, ride.dispatch(current, CallAction.HANG_UP))
        assertTrue(ride.state.value.calls.isEmpty())
        ride.close()
        assertEquals(CallDispatchResult.UNAVAILABLE, ride.dispatch(current, CallAction.HANG_UP))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, deliveries)
    }
}
