package org.openridesync.app.messages

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeMessagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun notification(message: Boolean = true): Notification = Notification.Builder(context, "test")
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setCategory(if (message) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_STATUS)
        .setContentTitle("Alice")
        .setContentText("Hello")
        .build()

    private fun sbn(n: Notification, postTime: Long = 42L, user: UserHandle = Process.myUserHandle(),
        packageName: String = context.packageName): StatusBarNotification =
        StatusBarNotification(packageName, packageName, 7, "thread", Process.myUid(),
            Process.myPid(), 0, n, user, postTime)

    private fun action(request: Int, semantic: Boolean = true): Notification.Action {
        val pending = PendingIntent.getBroadcast(context, request, Intent("reply.$request").setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val input = RemoteInput.Builder("reply").setAllowFreeFormInput(true).build()
        return Notification.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pending)
            .addRemoteInput(input)
            .setSemanticAction(if (semantic) Notification.Action.SEMANTIC_ACTION_REPLY else Notification.Action.SEMANTIC_ACTION_NONE)
            .build()
    }

    @Test fun onlyPublicMessagePayloadsAreAdmitted() {
        assertEquals("Hello", parseMessage(sbn(notification()))?.text)
        assertNull(parseMessage(sbn(notification()))?.sender)
        assertNull(parseMessage(sbn(notification(false))))
        val style = Notification.MessagingStyle("Me").addMessage("new text", 41L, "Alice")
        val styled = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Alice")
            .setStyle(style).build()
        assertEquals("new text", parseMessage(sbn(styled))?.text)
        styled.extras.putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(Bundle()))
        assertNull(parseMessage(sbn(styled)))
    }

    @Test fun groupConversationIsNotSenderAndPostTimeIsNotNotificationWhen() {
        val styled = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setWhen(7L)
            .setStyle(Notification.MessagingStyle("Me").setConversationTitle("Friends")
                .addMessage("Hello group", 41L, "Alice"))
            .build()
        styled.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Friends")
        val source = parseMessage(sbn(styled, postTime = 99L))!!
        assertEquals("Friends", source.title)
        assertEquals("Alice", source.sender)
        assertEquals(7L, source.timestamp)
        assertEquals(99L, source.signature.postTime)
        val longSender = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(Notification.MessagingStyle("Me").setConversationTitle("Friends")
                .addMessage("Hello group", 41L, "A".repeat(600)))
            .build()
        longSender.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Friends")
        assertEquals(512, parseMessage(sbn(longSender))!!.sender?.length)
    }

    @Test fun discordOwnPreviewShapesRemainReadableWithoutCategory() {
        val n = notification(false)
        n.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Discord preview")
        assertNull(parseMessage(sbn(n)))
        assertEquals("Discord preview", parseMessage(sbn(n, packageName = "com.discord"))?.text)
        val styled = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(Notification.MessagingStyle("Me").addMessage("Hello group", 42L, "Alice"))
            .build()
        styled.extras.remove(Notification.EXTRA_TEMPLATE)
        styled.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true)
        styled.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Friends")
        val group = parseMessage(sbn(styled, packageName = "com.discord"))
        assertEquals("Friends", group?.title)
        assertEquals("Alice: Hello group", group?.text)
        n.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Friends")
        n.extras.putCharSequence(Notification.EXTRA_TITLE, "x".repeat(4097))
        assertNull(parseMessage(sbn(n, packageName = "com.discord")))
        n.extras.putCharSequence(Notification.EXTRA_TITLE, "Discord")
        n.extras.putCharSequence(Notification.EXTRA_TEXT, "x".repeat(4097))
        assertNull(parseMessage(sbn(n, packageName = "com.discord")))
    }

    @Test fun summaryOngoingCallAndForeignProfileAreRejected() {
        val n = notification()
        n.flags = n.flags or Notification.FLAG_GROUP_SUMMARY
        assertNull(parseMessage(sbn(n)))
        n.flags = Notification.FLAG_ONGOING_EVENT
        assertNull(parseMessage(sbn(n)))
        n.flags = 0
        n.category = Notification.CATEGORY_CALL
        assertNull(parseMessage(sbn(n)))
        assertNull(parseMessage(sbn(notification(), user = UserHandle.getUserHandleForUid(10 * 100000))))
    }

    @Test fun recoveryIdentityIgnoresActionAndPostingTimeButFollowsActualContent() {
        val original = parseMessage(sbn(notification()))!!
        val reposted = parseMessage(sbn(notification().apply { actions = arrayOf(action(9)) }, 43L))!!
        assertNotEquals(original.signature, reposted.signature)
        assertEquals(messageRecoveryId(original), messageRecoveryId(reposted))
        assertTrue(messageRecoveryId(original).matches(Regex("[a-f0-9]{64}")))
        val changed = parseMessage(sbn(notification().apply { extras.putCharSequence(Notification.EXTRA_TEXT, "New content") }))!!
        assertNotEquals(messageRecoveryId(original), messageRecoveryId(changed))
        assertNotEquals(messageRecoveryId(original), messageRecoveryId(original.copy(sender = "New sender")))
    }

    @Test fun changedRemovedAndDuplicatedSourceCannotKeepReplyId() {
        val original = notification().apply { actions = arrayOf(action(1)) }
        val first = reconcileMessages(emptyMap(), listOf(sbn(original)))
        val unchanged = reconcileMessages(first, listOf(sbn(original)))
        assertEquals(first.keys, unchanged.keys)
        val changed = notification().apply {
            extras.putCharSequence(Notification.EXTRA_TEXT, "Changed")
            actions = arrayOf(action(1))
        }
        val replaced = reconcileMessages(first, listOf(sbn(changed)))
        assertNotEquals(first.keys.single(), replaced.keys.single())
        assertTrue(reconcileMessages(first, emptyList()).isEmpty())
        assertNotEquals(first.keys.single(), reconcileMessages(emptyMap(), listOf(sbn(original))).keys.single())
        assertTrue(reconcileMessages(first, listOf(sbn(original), sbn(original))).isEmpty())
        assertNotEquals(first.keys.single(), reconcileMessages(first, listOf(sbn(original, 43L))).keys.single())
        val actionChanged = notification().apply { actions = arrayOf(action(10)) }
        assertNotEquals(first.keys.single(), reconcileMessages(first, listOf(sbn(actionChanged))).keys.single())
    }

    @Test fun ambiguousReplyActionsAreDisabledAndOneSemanticReplyWins() {
        val n = notification().apply { actions = arrayOf(action(2, false), action(3, false)) }
        n.actions = arrayOf(action(2))
        assertNotNull(selectReplyAction(sbn(n))) // Ordinary text-only RemoteInput has no data MIME types.
        n.actions = arrayOf(action(2, false), action(3, false))
        assertNull(selectReplyAction(sbn(n)))
        n.actions = arrayOf(action(2, false), action(3))
        assertEquals(n.actions[1].actionIntent, selectReplyAction(sbn(n))?.pendingIntent)
        n.actions = arrayOf(action(3), action(4))
        assertNull(selectReplyAction(sbn(n)))
        val immutable = PendingIntent.getBroadcast(context, 5, Intent("immutable").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        n.actions = arrayOf(Notification.Action.Builder(android.R.drawable.ic_menu_send, "Reply", immutable)
            .addRemoteInput(RemoteInput.Builder("reply").build()).build())
        assertNull(selectReplyAction(sbn(n)))
        val delete = PendingIntent.getBroadcast(context, 6, Intent("delete").setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        n.actions = arrayOf(Notification.Action.Builder(android.R.drawable.ic_menu_delete, "Delete", delete)
            .addRemoteInput(RemoteInput.Builder("reply").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_DELETE).build())
        assertNull(selectReplyAction(sbn(n)))
    }

    @Test fun sendsFreeformInputToOwnReplyPendingIntent() {
        val received = mutableListOf<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                received += RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString()
                assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT, RemoteInput.getResultsSource(intent))
            }
        }
        context.registerReceiver(receiver, IntentFilter("reply.8"), Context.RECEIVER_EXPORTED)
        try {
            val n = notification().apply { actions = arrayOf(action(8)) }
            val reply = selectReplyAction(sbn(n))!!
            val spent = mutableSetOf<String>()
            assertEquals(MessageReplyResult.DISPATCHED, dispatchReply(context, reply, "Synthetic response", spent, "source"))
            assertEquals(MessageReplyResult.STALE, dispatchReply(context, reply, "Synthetic response", spent, "source"))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("Synthetic response"), received)
        } finally { context.unregisterReceiver(receiver) }
    }
}
