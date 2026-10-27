package org.openridesync.app.navigation

import android.app.*
import android.content.*
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.openridesync.app.media.MediaAccessService
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowPendingIntent
import org.robolectric.util.ReflectionHelpers

/** Synthetic provider identity; the PendingIntent still targets this test's local receiver. */
@Implements(PendingIntent::class)
class NavigationProviderIntentShadow : ShadowPendingIntent() {
    @Implementation override fun getCreatorPackage(): String = MAPS_PACKAGE
    @Implementation override fun getCreatorUid(): Int = Process.myUid()
    @Implementation fun getCreatorUserHandle(): UserHandle = Process.myUserHandle()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [NavigationProviderIntentShadow::class])
class NativeNavigationControlTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var listener: MediaAccessService
    private lateinit var snapshot: MapsSource
    private val owners = mutableListOf<NativeNavigation>()
    private var deliveries = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { deliveries++ }
    }

    @Before fun setup() {
        context.registerReceiver(receiver, IntentFilter("navigation.synthetic.exit"), Context.RECEIVER_NOT_EXPORTED)
        val pending = PendingIntent.getBroadcast(context, 1,
            Intent("navigation.synthetic.exit").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, "synthetic")
            .setSmallIcon(android.R.drawable.ic_dialog_map).setOngoing(true).setCategory("navigation")
            .setContentTitle("200 m - Synthetic street").setContentText("Synthetic street")
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "Exit navigation", pending).build()).build()
        val item = StatusBarNotification(MAPS_PACKAGE, MAPS_PACKAGE, 1, "route", Process.myUid(),
            Process.myPid(), 0, notification, Process.myUserHandle(), 42L)
        listener = Robolectric.buildService(MediaAccessService::class.java).create().get()
        Shadows.shadowOf(listener).addActiveNotification(item)
        listener.onListenerConnected()
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java), true)
        snapshot = checkNotNull(mapsSource(item, "Exit navigation", " - ", " · ", null)).copy(id = "snapshot")
        assertNotNull(snapshot.exitAction)
        Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
    }

    /** Seed the already parsed snapshot, isolating control from APK resource loading. */
    private fun owner(allowLocked: () -> Boolean = { false }): NativeNavigation =
        NativeNavigation(context, allowLockedControl = allowLocked).also { value ->
            owners += value
            ReflectionHelpers.setField(value, "running", true)
            ReflectionHelpers.setField(value, "source", snapshot)
            ReflectionHelpers.setField(value, "sourceRevision", MediaAccessService.changes.value)
            ReflectionHelpers.setField(value, "exitLabel", "Exit navigation")
            ReflectionHelpers.getField<MutableStateFlow<NavigationObservation>>(value, "mutableState").value =
                NavigationObservation(NavigationStatus.OBSERVED, snapshot.instruction,
                    sourceId = snapshot.id, canCancel = true)
        }

    @After fun cleanup() {
        owners.forEach { it.close() }
        listener.onListenerDisconnected()
        listener.onDestroy()
        context.unregisterReceiver(receiver)
    }

    @Test fun phoneRequiresUnlockButCurrentPhysicalOwnerCanDispatchOnlyOnce() {
        val phone = owner()
        assertEquals(NavigationCancelResult.LOCKED, phone.cancel(snapshot.id))
        assertTrue(phone.state.value.canRepeat)
        var live = false
        val ride = owner { live }
        assertEquals(NavigationCancelResult.LOCKED, ride.cancel(snapshot.id))
        live = true
        assertEquals(NavigationCancelResult.DISPATCHED, ride.cancel(snapshot.id))
        assertFalse(ride.state.value.canRepeat)
        assertFalse(ride.state.value.canCancel)
        assertNull(ride.currentInstruction(snapshot.id))
        assertEquals(NavigationCancelResult.STALE, ride.cancel(snapshot.id))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, deliveries)
        assertEquals("dispatch cannot mean route ended", NavigationStatus.OBSERVED, ride.state.value.status)
    }

    @Test fun lockedPermissionDoesNotBypassFreshNotificationAccessOrClosedOwner() {
        val ride = owner { true }
        MediaAccessService.changes.value++
        assertEquals(NavigationCancelResult.STALE, ride.cancel(snapshot.id))
        assertNull(ride.currentInstruction(snapshot.id))
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java), false)
        assertEquals(NavigationCancelResult.ACCESS_REQUIRED, ride.cancel(snapshot.id))
        ride.close()
        assertEquals(NavigationCancelResult.UNAVAILABLE, ride.cancel(snapshot.id))
        assertNull(ride.state.value.instruction)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, deliveries)
    }
}
