package org.openridesync.app.messages

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

/** Real native action selection/refresh with an own synthetic RemoteInput receiver. No vehicle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeRideReplyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var listener: MediaAccessService
    private lateinit var messages: NativeMessages
    private val replies = mutableListOf<String?>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            replies += RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString()
        }
    }

    private fun notification(text: String = "Synthetic source"): StatusBarNotification {
        val target = PendingIntent.getBroadcast(context, 1, Intent("ride.reply.synthetic").setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = Notification.Action.Builder(android.R.drawable.ic_menu_send, "Reply", target)
            .addRemoteInput(RemoteInput.Builder("reply").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build()
        val notification = Notification.Builder(context, "synthetic").setWhen(42)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Synthetic recipient")
            .setContentText(text).setCategory(Notification.CATEGORY_MESSAGE).addAction(action).build()
        return StatusBarNotification(context.packageName, context.packageName, 1, "message", Process.myUid(),
            Process.myPid(), 0, notification, Process.myUserHandle(), 42L)
    }

    @Before fun setup() {
        context.registerReceiver(receiver, IntentFilter("ride.reply.synthetic"), Context.RECEIVER_NOT_EXPORTED)
        listener = Robolectric.buildService(MediaAccessService::class.java).create().get()
        Shadows.shadowOf(listener).addActiveNotification(notification())
        listener.onListenerConnected()
        access(true)
        Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        messages = NativeMessages(context).also { it.start() }
    }

    private fun access(granted: Boolean) = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        .setNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java), granted)

    @After fun cleanup() {
        messages.close()
        listener.onListenerDisconnected()
        listener.onDestroy()
        context.unregisterReceiver(receiver)
    }

    @Test fun phoneStaysLockedAndOnlyLiveRideAuthorityCanConsumeTheExactSourceOnce() {
        val id = messages.state.value.messages.single().id
        assertEquals(MessageReplyResult.LOCKED, messages.reply(id, "Synthetic reply"))
        assertEquals(MessageReplyResult.STALE, messages.replyFromRide(id, "Synthetic reply") { false })
        assertTrue(messages.state.value.messages.single().canReply)
        assertEquals(MessageReplyResult.DISPATCHED, messages.replyFromRide(id, "Synthetic reply") { true })
        assertFalse(messages.state.value.messages.single().canReply)
        assertEquals(MessageReplyResult.STALE, messages.replyFromRide(id, "Synthetic reply") { true })
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Synthetic reply"), replies)
    }

    @Test fun rideAuthorityCannotBypassChangedSourceRevokedAccessOrClosedOwner() {
        val id = messages.state.value.messages.single().id
        val changed = notification("Changed synthetic source")
        listener.cancelNotification(changed.key)
        Shadows.shadowOf(listener).addActiveNotification(changed)
        assertEquals(MessageReplyResult.STALE, messages.replyFromRide(id, "Synthetic reply") { true })
        val replacement = messages.state.value.messages.single().id
        assertNotEquals(id, replacement)
        access(false)
        assertEquals(MessageReplyResult.ACCESS_REQUIRED, messages.replyFromRide(replacement, "Synthetic reply") { true })
        messages.close()
        assertEquals(MessageReplyResult.UNAVAILABLE, messages.replyFromRide(replacement, "Synthetic reply") { true })
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(replies.isEmpty())
    }

    @Test fun invalidTranscriptDoesNotConsumeTheSourceAndTheOwnerIsCheckedAfterRefresh() {
        val id = messages.state.value.messages.single().id
        assertEquals(MessageReplyResult.INVALID_TEXT, messages.replyFromRide(id, " ") { true })
        assertEquals(MessageReplyResult.INVALID_TEXT, messages.replyFromRide(id, "x".repeat(2001)) { true })
        assertTrue(messages.state.value.messages.single().canReply)
        var checks = 0
        assertEquals(MessageReplyResult.STALE, messages.replyFromRide(id, "Synthetic reply") {
            checks++
            // Callback sees the newly reconciled state, not just the transcript's earlier source.
            assertEquals(MessageAccess.READY, messages.state.value.access)
            false
        })
        assertEquals(1, checks)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(replies.isEmpty())
    }
}
