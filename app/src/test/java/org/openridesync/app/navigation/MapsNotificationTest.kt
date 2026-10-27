package org.openridesync.app.navigation

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowPendingIntent

/** Give the synthetic, app-owned action a deterministic creator identity. */
@Implements(PendingIntent::class)
class MapsPendingIntentShadow : ShadowPendingIntent() {
    @RealObject private lateinit var pending: PendingIntent
    @Implementation override fun getCreatorPackage(): String = RuntimeEnvironment.getApplication().packageName
    @Implementation override fun getCreatorUid(): Int = Process.myUid()
    @Implementation fun getCreatorUserHandle(): UserHandle = UserHandle.getUserHandleForUid(pending.creatorUid)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [MapsPendingIntentShadow::class])
class MapsNotificationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun pending(id: Int) = PendingIntent.getBroadcast(context, id,
        Intent("maps.synthetic.$id").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    private fun exit(id: Int, title: String = "Exit navigation") = Notification.Action.Builder(
        android.R.drawable.ic_menu_close_clear_cancel, title, pending(id)).build()
    private fun notification(title: String = "300 m - Main Street") =
        Notification.Builder(RuntimeEnvironment.getApplication(), "navigation")
            .setSmallIcon(android.R.drawable.ic_dialog_map).setOngoing(true).setCategory("navigation")
            .setContentTitle(title).setContentText("fallback street").setSubText("5 min · 2 km · 18:00").build()
    private fun sbn(n: Notification, pkg: String = MAPS_PACKAGE, user: UserHandle = Process.myUserHandle()) =
        StatusBarNotification(pkg, pkg, 1, "navigation", Process.myUid(), Process.myPid(), 0, n, user, 42L)

    @Test fun navigationSourceRequiresCurrentUserExactPackageAndOngoingNonSummary() {
        val n = notification()
        assertTrue(isMapsNavigation(sbn(n), null))
        assertFalse(isMapsNavigation(sbn(n, "test.maps"), null))
        assertFalse(isMapsNavigation(sbn(n, user = UserHandle.getUserHandleForUid(10 * 100000)), null))
        n.flags = n.flags or Notification.FLAG_GROUP_SUMMARY
        assertFalse(isMapsNavigation(sbn(n), null))
        n.flags = 0
        assertFalse(isMapsNavigation(sbn(n), null))
        n.flags = Notification.FLAG_ONGOING_EVENT; n.category = Notification.CATEGORY_STATUS
        assertFalse(isMapsNavigation(sbn(n), "Exit navigation"))
    }

    @Test fun literalLocaleSeparatorsPreserveUnitsAndDoNotBecomeRegexes() {
        assertEquals(" [.] ", mapsSeparator("%1\$s [.] %2\$s", " - "))
        assertEquals(" - ", mapsSeparator("%2\$s %1\$s", " - "))
        val n = notification("300 公尺 [.] 中山路")
        val value = mapsInstruction(n, 35, " [.] ", " · ", NavigationProjection.Maneuver.RIGHT)!!
        assertEquals("300 公尺", value.nextStep)
        assertEquals("中山路", value.instruction)
        assertEquals("5 min · 2 km · 18:00", value.remaining)
        assertEquals(NavigationProjection.Maneuver.RIGHT, value.maneuver)
        val fallback = mapsInstruction(notification("300 m"), 35, " - ", " · ", null)!!
        assertEquals("fallback street", fallback.instruction)
        assertNull(fallback.maneuver)
    }

    @Test fun progressStyleUsesCriticalDistanceOnlyOnApi36() {
        val n = notification("5 min · Main Street")
        n.extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$ProgressStyle")
        n.extras.putCharSequence("android.shortCriticalText", "200 m")
        val value = mapsInstruction(n, 36, " - ", " · ", null)!!
        assertEquals("Main Street", value.instruction)
        assertEquals("200 m", value.nextStep)
        assertEquals("fallback street", mapsInstruction(n, 35, " - ", " · ", null)?.instruction)
        n.extras.remove("android.shortCriticalText")
        assertNull(mapsInstruction(n, 36, " - ", " · ", null)?.nextStep)
    }

    @Test fun unsupportedOrOversizeTextDoesNotInventAnInstruction() {
        val n = notification()
        n.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "other Maps content")
        assertNull(mapsInstruction(n, 35, " - ", " · ", null))
        n.extras.remove(Notification.EXTRA_BIG_TEXT)
        n.extras.putCharSequence(Notification.EXTRA_TITLE, "x".repeat(4097))
        assertNull(mapsInstruction(n, 35, " - ", " · ", null))
        n.extras.remove(Notification.EXTRA_TITLE)
        assertNull(mapsInstruction(n, 35, " - ", " · ", null))
    }

    @Test fun exactSnapshotAndUniqueLocalizedExitAreRequired() {
        val n = notification().apply { actions = arrayOf(exit(1)) }
        val original = checkNotNull(mapsSource(sbn(n), "Exit navigation", " - ", " · ", null))
        val first = checkNotNull(reconcileMapsSource(null, original))
        assertEquals(first.id, reconcileMapsSource(first, original)?.id)
        assertNull(reconcileMapsSource(first, null))
        assertNull("test-app PendingIntent cannot act as Maps", original.exitAction)
        assertEquals(pending(1), uniqueExitAction(listOf("Exit navigation" to pending(1)),
            "Exit navigation", context.packageName, Process.myUid(), Process.myUserHandle()))
        assertNotNull(original.instruction)
        n.extras.putCharSequence(Notification.EXTRA_TEXT, "Changed street")
        val changed = checkNotNull(mapsSource(sbn(n), "Exit navigation", " - ", " · ", null))
        assertNotEquals(original.signature, changed.signature)
        assertNotEquals(first.id, reconcileMapsSource(first, changed)?.id)
        n.extras.putCharSequence(Notification.EXTRA_TEXT, "fallback street")
        n.actions = arrayOf(exit(2))
        assertNotEquals(original.signature,
            mapsSource(sbn(n), "Exit navigation", " - ", " · ", null)?.signature)
        n.actions = arrayOf(exit(1), exit(2))
        assertNull(uniqueExitAction(n.actions.map { it.title.toString() to it.actionIntent },
            "Exit navigation", context.packageName, Process.myUid(), Process.myUserHandle()))
        n.actions = arrayOf(exit(1, "Other action"))
        assertNull(uniqueExitAction(n.actions.map { it.title.toString() to it.actionIntent },
            "Exit navigation", context.packageName, Process.myUid(), Process.myUserHandle()))
        n.actions = arrayOf(exit(1))
        assertNull(uniqueExitAction(listOf("Exit navigation" to pending(1)),
            "Exit navigation", context.packageName, Process.myUid() + 1, Process.myUserHandle()))
        assertNull(uniqueExitAction(listOf("Exit navigation" to pending(1)),
            "Exit navigation", MAPS_PACKAGE, Process.myUid(), Process.myUserHandle()))
        assertNull(mapsSource(sbn(n, user = UserHandle.getUserHandleForUid(1_000_000)),
            "Exit navigation", " - ", " · ", null))
    }

    @Test fun exitDispatchIsOnceAndNeverUsesAnUnrelatedAction() {
        var deliveries = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { deliveries++ }
        }
        context.registerReceiver(receiver, IntentFilter("maps.synthetic.3"), Context.RECEIVER_NOT_EXPORTED)
        try {
            val n = notification().apply { actions = arrayOf(exit(4, "Other action"), exit(3)) }
            val selected = checkNotNull(uniqueExitAction(n.actions.map { it.title.toString() to it.actionIntent },
                "Exit navigation", context.packageName, Process.myUid(), Process.myUserHandle()))
            val spent = mutableSetOf<String>()
            assertEquals(NavigationCancelResult.DISPATCHED, dispatchMapsExit(selected, spent, "source"))
            assertEquals(NavigationCancelResult.STALE, dispatchMapsExit(selected, spent, "source"))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, deliveries)
            val canceled = pending(5).also { it.cancel() }
            assertEquals(NavigationCancelResult.STALE, dispatchMapsExit(canceled, spent, "canceled"))
            assertTrue("canceled action is consumed", "canceled" in spent)
        } finally { context.unregisterReceiver(receiver) }
    }
}
