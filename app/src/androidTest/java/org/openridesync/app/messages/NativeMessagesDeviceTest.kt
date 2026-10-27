package org.openridesync.app.messages

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Own synthetic notification only; this is not Discord or vehicle acceptance.
 * Native listing may transiently read other notifications in bounded RAM. The test selects only
 * its package and UUID title, and never logs notification content, source keys or inbox dumps. */
@RunWith(AndroidJUnit4::class)
class NativeMessagesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }
    private fun awaitCondition(timeoutMs: Long = 5_000, check: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return true
            SystemClock.sleep(25) // Instrumentation thread; main remains available for callbacks.
        }
        return check()
    }

    @Test fun ownSyntheticMessageUpdateInvalidatesIdAndReplyDispatchesOnlyOnce() {
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Existing notification posting and listener grants required",
            notifications?.areNotificationsEnabled() == true && notifications.isNotificationListenerAccessGranted(
                ComponentName(context, MediaAccessService::class.java)))
        assumeTrue("Device must already be unlocked",
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false)
        assumeTrue("Ride must already be idle", main {
            (context.applicationContext as OpenRideSyncApplication).ride.state.value.stage == RideStage.IDLE
        })
        val uuid = UUID.randomUUID().toString()
        val channel = "ors-synthetic-message-$uuid"
        val title = "Synthetic message $uuid"
        val action = "${context.packageName}.SYNTHETIC_REPLY.$uuid"
        val resultKey = "synthetic_reply"
        val reply = "Synthetic reply only"
        val received = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val correctText = AtomicBoolean(true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != action || intent.`package` != context.packageName) return
                if (RemoteInput.getResultsFromIntent(intent)?.getCharSequence(resultKey)?.toString() != reply)
                    correctText.set(false)
                deliveries.incrementAndGet()
                received.countDown()
            }
        }
        var registered = false
        var pending: PendingIntent? = null
        var backend: NativeMessages? = null
        try {
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, IntentFilter(action))
            registered = true
            val intent = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            pending = intent
            notifications!!.createNotificationChannel(NotificationChannel(channel, "Synthetic message test", NotificationManager.IMPORTANCE_LOW))
            val input = RemoteInput.Builder(resultKey).setLabel("Synthetic reply").build()
            val replyAction = Notification.Action.Builder(android.R.drawable.ic_menu_send, "Reply", intent)
                .addRemoteInput(input).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build()
            fun notification(text: String) = Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
                .setCategory(Notification.CATEGORY_MESSAGE).addAction(replyAction).build()
            val messages = main { NativeMessages(context).also { it.start() } }
            backend = messages
            // Instrumentation restarts the target; Android may defer rebinding a dead listener.
            // Keep event/reply assertions at 5 seconds; allow only initial binding to recover.
            assertTrue("Granted listener must connect", awaitCondition(20_000) {
                main { MediaAccessService.callSummary.value.listenerConnected }
            })
            fun owned() = main {
                // Require the real listener event to refresh the page, not a test-side poll.
                messages.state.value.messages.singleOrNull { it.packageName == context.packageName && it.title == title }
            }
            notifications.notify(uuid, 1, notification("Synthetic initial"))
            assertTrue("Owned message must expose its reply action", awaitCondition { owned()?.canReply == true })
            val oldId = checkNotNull(owned()).id
            val oldRecovery = checkNotNull(owned()).recoveryId
            assertTrue(oldRecovery.matches(Regex("[a-f0-9]{64}")))
            assertTrue(checkNotNull(owned()).postedAt > 0)
            assertNull(checkNotNull(owned()).sender) // An ordinary title does not prove a sender.
            notifications.notify(uuid, 1, notification("Synthetic updated"))
            assertTrue("Content update must replace the opaque ID", awaitCondition {
                owned()?.let { it.text == "Synthetic updated" && it.id != oldId && it.canReply } == true
            })
            val newId = checkNotNull(owned()).id
            assertNotEquals(oldRecovery, checkNotNull(owned()).recoveryId)
            assertEquals(MessageReplyResult.STALE, main { messages.reply(oldId, reply) })
            assertEquals(0, deliveries.get())
            assertEquals(MessageReplyResult.DISPATCHED, main { messages.reply(newId, reply) })
            assertTrue("Own receiver must receive the synthetic reply", received.await(5, TimeUnit.SECONDS))
            assertTrue("Reply text must match the synthetic input", correctText.get())
            assertEquals(MessageReplyResult.STALE, main { messages.reply(newId, reply) })
            SystemClock.sleep(250)
            assertEquals("Synthetic reply must be delivered once", 1, deliveries.get())
            notifications.cancel(uuid, 1)
            assertTrue("Removing the owned source must remove its message", awaitCondition { owned() == null })
            main { messages.close(); assertTrue(messages.state.value.messages.isEmpty()) }
        } finally {
            try { notifications?.cancel(uuid, 1) }
            finally {
                try { main { backend?.close() } }
                finally {
                    try { if (registered) context.unregisterReceiver(receiver) }
                    finally { try { pending?.cancel() } finally { notifications?.deleteNotificationChannel(channel) } }
                }
            }
        }
    }
}
