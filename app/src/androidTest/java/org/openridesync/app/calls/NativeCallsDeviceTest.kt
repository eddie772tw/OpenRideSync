package org.openridesync.app.calls

import android.app.*
import android.content.*
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.media.MediaAccessService
import org.openridesync.app.runtime.RideStage
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Self-owned notification and receiver only. No real call, remote app action or audio. */
@RunWith(AndroidJUnit4::class)
class NativeCallsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }
    private fun awaitCondition(timeout: Long = 5_000, check: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return true
            SystemClock.sleep(25)
        }
        return check()
    }

    @Test fun ownCallStyleActionsDispatchOnlyAfterExplicitRequestAndExpireWithSource() {
        assumeTrue("Public CallStyle type metadata requires API34", android.os.Build.VERSION.SDK_INT >= 34)
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Existing posting/listener grants required", manager?.areNotificationsEnabled() == true &&
            manager.isNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java)))
        assumeTrue("Device must already be unlocked", context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false)
        assumeTrue("Ride must already be idle", main {
            (context.applicationContext as OpenRideSyncApplication).ride.state.value.stage == RideStage.IDLE
        })
        val uuid = UUID.randomUUID().toString()
        val channel = "ors-synthetic-call-$uuid"
        val caller = "Synthetic caller $uuid"
        val action = "${context.packageName}.SYNTHETIC_CALL.$uuid"
        val deliveries = AtomicInteger()
        val received = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action == action && intent.`package` == context.packageName) {
                    deliveries.incrementAndGet(); received.countDown()
                }
            }
        }
        var registered = false
        var backend: NativeCalls? = null
        var pending: PendingIntent? = null
        var decline: PendingIntent? = null
        var scenario: ActivityScenario<CallFixtureActivity>? = null
        try {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            registered = true
            val answer = PendingIntent.getBroadcast(context, 1, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            pending = answer
            val reject = PendingIntent.getBroadcast(context, 2, Intent("$action.decline").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            decline = reject
            manager!!.createNotificationChannel(NotificationChannel(channel, "Synthetic call test", NotificationManager.IMPORTANCE_LOW))
            fun notification(name: String) = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName(name).build(), reject, answer)).build()
            scenario = ActivityScenario.launch(CallFixtureActivity::class.java)
            fun publish(name: String) {
                scenario!!.onActivity { activity ->
                    activity.startForegroundService(Intent(activity, CallFixtureService::class.java)
                        .putExtra(CallFixtureService.NOTIFICATION, notification(name)))
                }
            }
            val calls = main { NativeCalls(context).also { it.start() } }
            backend = calls
            assertTrue("Granted listener must connect", awaitCondition(20_000) {
                main { MediaAccessService.callSummary.value.listenerConnected }
            })
            fun owned() = main { calls.state.value.calls.singleOrNull { it.packageName == context.packageName && it.caller.startsWith(caller) } }
            publish(caller)
            assertTrue(awaitCondition { owned()?.actions?.contains(CallAction.ANSWER) == true })
            assertEquals(0, deliveries.get())
            val old = checkNotNull(owned()).id
            publish("$caller updated")
            assertTrue(awaitCondition { owned()?.let { it.id != old && it.caller == "$caller updated" } == true })
            assertEquals(CallDispatchResult.STALE, main { calls.dispatch(old, CallAction.ANSWER) })
            assertEquals(0, deliveries.get())
            val current = checkNotNull(owned()).id
            assertEquals(CallDispatchResult.DISPATCHED, main { calls.dispatch(current, CallAction.ANSWER) })
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertEquals(CallDispatchResult.STALE, main { calls.dispatch(current, CallAction.ANSWER) })
            SystemClock.sleep(250)
            assertEquals(1, deliveries.get())
            // No fake ongoing call: the unchanged synthetic source stays INCOMING.
            assertEquals(CallKind.INCOMING, owned()?.kind)
            context.stopService(Intent(context, CallFixtureService::class.java))
            assertTrue("Synthetic foreground service must stop", awaitCondition { !CallFixtureService.running })
            assertTrue(awaitCondition { owned() == null })
            main { calls.close(); assertTrue(calls.state.value.calls.isEmpty()) }
        } finally {
            try {
                context.stopService(Intent(context, CallFixtureService::class.java))
                assertTrue("Fixture must not leave a foreground service", awaitCondition { !CallFixtureService.running })
            }
            finally {
                try { main { backend?.close() } }
                finally {
                    try { if (registered) context.unregisterReceiver(receiver) }
                    finally {
                        try { pending?.cancel(); decline?.cancel(); manager?.deleteNotificationChannel(channel) }
                        finally { scenario?.close() }
                    }
                }
            }
        }
    }
}
