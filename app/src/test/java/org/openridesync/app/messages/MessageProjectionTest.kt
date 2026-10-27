package org.openridesync.app.messages

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.Gen1Projection
import org.openridesync.app.runtime.MessageReadStatus
import org.openridesync.app.runtime.RidePageLabels

class MessageProjectionTest {
    @Test fun incomingUsesMessageLivePaletteAndOnlyAskAdvertisesPlayWithoutLeakingBody() {
        val item = MessageItem("id", "Chat", "synthetic.chat", "Conversation", "PRIVATE_BODY_MARKER", 1, false,
            sender = "😀寄件者".repeat(100))
        val prompt = MessageProjection.incoming(item, "Messages", canPlay = true, reading = false)
        assertEquals(11, prompt.color); assertEquals(12, prompt.colorDark)
        assertEquals(8, prompt.left.contentIcon); assertEquals(3, prompt.right.contentIcon)
        assertEquals(10, prompt.left.background); assertEquals(3, prompt.right.background)
        assertTrue(prompt.line1!!.startsWith("😀寄件者"))
        assertFalse(prompt.line1!!.contains("Conversation"))
        val frame = Gen1Projection.encode(prompt, 1)
        assertTrue(frame.size <= 512)
        assertFalse(frame.toString(Charsets.UTF_8).contains("PRIVATE_BODY_MARKER"))
        val read = MessageProjection.incoming(item, "Messages", canPlay = false, reading = true)
        assertNull(read.right.arrow); assertNull(read.right.contentIcon)
        assertEquals(8, read.left.contentIcon)
        val automatic = MessageProjection.incoming(item, "Messages", canPlay = false, reading = false)
        assertEquals(1, automatic.left.contentIcon); assertEquals(2, automatic.left.background)
    }
    @Test fun longUnicodeTitleFitsFrameAndBodyNeverAppears() {
        val item = MessageItem("id", "聊天".repeat(512), "synthetic.chat",
            "😀標題".repeat(512), "PRIVATE_BODY_MARKER".repeat(100), 1L, false)
        val labels = RidePageLabels("Unknown", "Music", "Playing", "Choose player", "Volume")
        val frame = Gen1Projection.encode(MessageProjection.browse(listOf(item), item.id, labels,
            MessageReadStatus.NONE), 1)
        assertTrue(frame.size <= 512)
        assertFalse(frame.toString(Charsets.UTF_8).contains("PRIVATE_BODY_MARKER"))
        assertTrue(Gen1Projection.encode(MessageProjection.menu("😀".repeat(512), true, true), 2).size <= 512)
    }
}
