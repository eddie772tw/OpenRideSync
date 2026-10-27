package org.openridesync.app.messages

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.runtime.IncomingMessageMode
import org.openridesync.app.runtime.MessageReadStatus

class IncomingMessagesTest {
    private val ask = IncomingMessageMode.ASK_TO_LISTEN
    private fun item(id: String, time: Long = 1) = MessageItem(id, "Chat", "synthetic.chat", "Label $id",
        "Private body $id", 999, false, postedAt = time)
    private fun inbox(vararg items: MessageItem) = MessageInboxState(MessageAccess.READY, items.toList())

    @Test fun firstSnapshotRecoversOnlyPendingThenNewItemsEnterInPostingOrder() {
        var saved = setOf("recover", "missing")
        val queue = IncomingMessages(saved) { saved = it; true }
        queue.sync(inbox(item("old"), item("recover")), ask)
        assertEquals("recover", queue.front!!.item.id)
        assertEquals(setOf("recover"), saved)
        queue.yield(forget = true)
        assertNull(queue.front)
        queue.sync(inbox(item("old"), item("recover"), item("newer", 3), item("older", 2)), ask)
        assertEquals("older", queue.front!!.item.id)
        assertEquals(setOf("older", "newer"), saved)
        queue.yield(forget = true)
        assertEquals("newer", queue.front!!.item.id)
    }

    @Test fun replacementInvalidatesExactIdButDoesNotAuthorizeRepeatingSpeech() {
        var saved = emptySet<String>()
        val queue = IncomingMessages(saved) { saved = it; true }
        queue.sync(inbox(), ask)
        queue.sync(inbox(item("first")), ask)
        queue.update(IncomingMessages.Phase.READ, MessageReadStatus.READING)
        queue.sync(inbox(item("replacement").copy(recoveryId = "first")), ask)
        assertEquals("replacement", queue.front!!.item.id)
        assertEquals(MessageReadStatus.FAILED, queue.front!!.status)
        assertEquals(setOf("first"), saved)
        queue.sync(inbox(), ask)
        assertNull(queue.front)
        assertTrue(saved.isEmpty())
    }

    @Test fun backForgetsButTimeoutAndInterruptionLeavePendingForLaterRecovery() {
        var saved = emptySet<String>()
        val queue = IncomingMessages(saved) { saved = it; true }
        queue.sync(inbox(), ask)
        queue.sync(inbox(item("one"), item("two", 2)), ask)
        queue.update(IncomingMessages.Phase.READ, MessageReadStatus.READING)
        queue.interruptSpeech()
        assertEquals("one", queue.front!!.item.id)
        assertEquals(MessageReadStatus.FAILED, queue.front!!.status)
        queue.yield(forget = false)
        assertEquals("two", queue.front!!.item.id)
        assertEquals(setOf("one", "two"), saved)
        queue.yield(forget = true)
        assertEquals(setOf("one"), saved)
        queue.suspendContent()
        assertNull(queue.front)
        queue.sync(inbox(item("one"), item("two", 2)), ask)
        assertEquals("one", queue.front!!.item.id)
        queue.home()
        assertTrue(saved.isEmpty())
        assertNull(queue.front)
    }

    @Test fun ignoreDoesNotConvertUnseenCurrentNotificationsIntoNewMessagesOnEnable() {
        var saved = setOf("pending")
        val queue = IncomingMessages(saved) { saved = it; true }
        queue.sync(inbox(item("old"), item("pending")), IncomingMessageMode.IGNORE)
        assertNull(queue.front)
        queue.sync(inbox(item("old"), item("pending"), item("ignored")), IncomingMessageMode.IGNORE)
        queue.sync(inbox(item("old"), item("pending"), item("ignored")), ask)
        assertEquals("pending", queue.front!!.item.id)
        queue.yield(forget = true)
        assertNull(queue.front)
        assertTrue(saved.isEmpty())
    }

    @Test fun persistenceFailurePreventsPresentationAndCorruptLedgerIsNotAnEmptyLedger() {
        val queue = IncomingMessages(emptySet()) { false }
        queue.sync(inbox(), ask)
        queue.sync(inbox(item("new")), ask)
        assertFalse(queue.available)
        assertNull(queue.front)
        val corrupt = IncomingMessages(null) { fail("Cannot publish an invented empty ledger"); true }
        corrupt.sync(inbox(item("current")), ask)
        corrupt.sync(inbox(item("current"), item("new")), ask)
        assertFalse(corrupt.available)
        assertNull(corrupt.front)
    }

    @Test fun unavailableSnapshotRetainsPendingAndAmbiguousIdentityIsNotChosen() {
        var saved = emptySet<String>()
        val queue = IncomingMessages(saved) { saved = it; true }
        queue.sync(inbox(), ask)
        queue.sync(inbox(item("one")), ask)
        queue.sync(MessageInboxState(MessageAccess.UNAVAILABLE), ask)
        assertEquals(setOf("one"), saved)
        assertNull(queue.front)
        queue.sync(inbox(item("one")), ask)
        assertEquals("one", queue.front!!.item.id)
        queue.sync(inbox(item("one"), item("duplicate").copy(recoveryId = "one")), ask)
        assertNull(queue.front)
        assertTrue(saved.isEmpty())
    }

    @Test fun settingChangeRevokesAutoReadAndTemporaryLossRemembersOnlyInterruptedPhase() {
        val queue = IncomingMessages(emptySet()) { true }
        queue.sync(inbox(), IncomingMessageMode.AUTO_READ)
        queue.sync(inbox(item("one")), IncomingMessageMode.AUTO_READ)
        queue.update(IncomingMessages.Phase.PROMPT, MessageReadStatus.READING)
        queue.sync(inbox(item("one")), ask)
        assertEquals(ask, queue.front!!.mode)
        assertEquals(MessageReadStatus.NONE, queue.front!!.status)
        queue.update(IncomingMessages.Phase.READ, MessageReadStatus.READING)
        queue.suspendContent()
        assertNull(queue.front)
        queue.sync(inbox(item("one")), ask)
        assertEquals(IncomingMessages.Phase.READ, queue.front!!.phase)
        assertEquals(MessageReadStatus.FAILED, queue.front!!.status)
    }

    @Test fun clearingPendingAlsoForgetsInterruptedPhaseForTheSameLaterSource() {
        val queue = IncomingMessages(emptySet()) { true }
        queue.sync(inbox(), ask)
        queue.sync(inbox(item("one")), ask)
        queue.update(IncomingMessages.Phase.READ, MessageReadStatus.READING)
        queue.suspendContent()
        queue.home()
        queue.sync(inbox(), ask)
        queue.sync(inbox(item("one")), ask)
        assertEquals(IncomingMessages.Phase.PROMPT, queue.front!!.phase)
        assertEquals(MessageReadStatus.NONE, queue.front!!.status)
    }

    @Test fun temporaryLossRetainsEstablishedFifoAheadOfLateOldPostingTimes() {
        val queue = IncomingMessages(emptySet()) { true }
        queue.sync(inbox(), ask)
        queue.sync(inbox(item("first", 100)), ask)
        queue.update(IncomingMessages.Phase.READ, MessageReadStatus.READING)
        queue.sync(inbox(item("first", 100), item("late", 50)), ask)
        queue.suspendContent()
        queue.sync(inbox(item("late", 50), item("first", 100)), ask)
        queue.sync(inbox(item("late", 50), item("new", 1), item("first", 100)), ask)
        assertEquals("first", queue.front!!.item.id)
        assertEquals(IncomingMessages.Phase.READ, queue.front!!.phase)
        assertEquals(MessageReadStatus.FAILED, queue.front!!.status)
        queue.yield(forget = true)
        assertEquals("late", queue.front!!.item.id)
        queue.yield(forget = true)
        assertEquals("new", queue.front!!.item.id)
    }
}
