package org.openridesync.app.calls

import android.app.*
import android.content.*
import android.os.Process
import android.os.Looper
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

/** Robolectric4.17 models creator UID but has no getCreatorUserHandle implementation.
 * Mirror Android's UID-to-user calculation in the fixture; keep production's strict check. */
@Implements(PendingIntent::class)
class CallPendingIntentShadow : ShadowPendingIntent() {
    @RealObject private lateinit var pending: PendingIntent
    @Implementation fun getCreatorUserHandle(): UserHandle = UserHandle.getUserHandleForUid(pending.creatorUid)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CallPendingIntentShadow::class])
class NativeCallsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun pending(id: Int) = PendingIntent.getBroadcast(context, id,
        Intent("synthetic.call.$id").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    private fun notification(ongoing: Boolean = false): Notification = Notification.Builder(context, "test")
        .setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(if (ongoing)
            Notification.CallStyle.forOngoingCall(Person.Builder().setName("Synthetic caller").build(), pending(3))
        else Notification.CallStyle.forIncomingCall(Person.Builder().setName("Synthetic caller").build(), pending(1), pending(2)))
        .build()
    private fun sbn(n: Notification, user: UserHandle = Process.myUserHandle(), pkg: String = context.packageName) =
        StatusBarNotification(pkg, pkg, 1, "synthetic", Process.myUid(), Process.myPid(), 0, n, user, 42L)

    @Test fun publicCallStyleExtrasDetermineExactActionsAndVideo() {
        val incoming = notification()
        assertEquals(Notification.CallStyle::class.java.name, incoming.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(Process.myUserHandle(), pending(2).creatorUserHandle)
        incoming.extras.putBoolean(Notification.EXTRA_CALL_IS_VIDEO, true)
        val parsed = checkNotNull(parseCall(sbn(incoming))).signature
        assertEquals(CallKind.INCOMING, parsed.kind)
        assertEquals(setOf(CallAction.ANSWER, CallAction.DECLINE), parsed.actions.keys)
        assertEquals(pending(2), parsed.actions[CallAction.ANSWER])
        assertTrue(parsed.isVideo)
        val ongoing = checkNotNull(parseCall(sbn(notification(true)))).signature
        assertEquals(CallKind.ONGOING, ongoing.kind)
        assertEquals(setOf(CallAction.HANG_UP), ongoing.actions.keys)
    }

    @Test fun foreignSummaryScreeningMalformedAndAmbiguousSourcesAreRejected() {
        assertNull(parseCall(sbn(notification(), pkg = "foreign.package")))
        assertNull(parseCall(sbn(notification(), user = UserHandle.getUserHandleForUid(1_000_000))))
        assertNull(parseCall(sbn(notification().apply { flags = flags or Notification.FLAG_GROUP_SUMMARY })))
        assertNull(parseCall(sbn(notification().apply { extras.putInt(Notification.EXTRA_CALL_TYPE, 3) })))
        assertNull(parseCall(sbn(notification().apply { extras.remove(Notification.EXTRA_TEMPLATE) })))
        assertNull(parseCall(sbn(notification().apply { extras.putString(Notification.EXTRA_ANSWER_INTENT, "invalid") })))
        assertNull(parseCall(sbn(notification().apply { extras.remove(Notification.EXTRA_ANSWER_INTENT) })))
        assertNull(parseCall(sbn(notification().apply { extras.remove(Notification.EXTRA_DECLINE_INTENT) })))
        assertNull(parseCall(sbn(notification(true).apply { extras.remove(Notification.EXTRA_HANG_UP_INTENT) })))
        assertNull(parseCall(sbn(notification().apply {
            extras.putParcelable(Notification.EXTRA_DECLINE_INTENT, pending(2))
        })))
        assertNull(parseCall(sbn(notification().apply {
            actions = arrayOf(Notification.Action.Builder(android.R.drawable.ic_dialog_info, "Input", pending(2))
                .addRemoteInput(RemoteInput.Builder("unexpected").build()).build())
        })))
    }

    @Test fun exactSourceChangesRemovalAndDuplicateKeysInvalidateOpaqueIds() {
        val n = notification()
        val first = reconcileCalls(emptyMap(), listOf(sbn(n)))
        assertEquals(first.keys, reconcileCalls(first, listOf(sbn(n))).keys)
        val changed = notification().apply { extras.putParcelable(Notification.EXTRA_ANSWER_INTENT, pending(9)) }
        assertNotEquals(first.keys, reconcileCalls(first, listOf(sbn(changed))).keys)
        assertNotEquals(first.keys, reconcileCalls(first, listOf(sbn(notification(true)))).keys)
        assertTrue(reconcileCalls(first, emptyList()).isEmpty())
        assertTrue(reconcileCalls(first, listOf(sbn(n), sbn(n))).isEmpty())
        assertNotEquals(first.keys, reconcileCalls(emptyMap(), listOf(sbn(n))).keys)
    }

    @Test fun dispatchConsumesSourceBeforeSendingAndCanceledIntentCannotRetry() {
        val spent = mutableSetOf<String>()
        val canceled = pending(90).also { it.cancel() }
        assertEquals(CallDispatchResult.STALE, dispatchCall(context, canceled, spent, "source"))
        assertTrue("A canceled dispatch is consumed", "source" in spent)
        assertEquals(CallDispatchResult.STALE, dispatchCall(context, pending(91), spent, "source"))
    }

    @Test fun exactAnswerIntentReachesOnlyOwnReceiverOnceWithoutChangingObservedKind() {
        var deliveries = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { deliveries++ }
        }
        context.registerReceiver(receiver, IntentFilter("synthetic.call.2"), Context.RECEIVER_NOT_EXPORTED)
        try {
            val source = checkNotNull(parseCall(sbn(notification())))
            assertEquals(0, deliveries)
            val spent = mutableSetOf<String>()
            val answer = checkNotNull(source.signature.actions[CallAction.ANSWER])
            assertEquals(CallDispatchResult.DISPATCHED, dispatchCall(context, answer, spent, "source"))
            assertEquals(CallDispatchResult.STALE, dispatchCall(context, answer, spent, "source"))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, deliveries)
            assertEquals(CallKind.INCOMING, source.signature.kind)
        } finally { context.unregisterReceiver(receiver) }
    }
}
