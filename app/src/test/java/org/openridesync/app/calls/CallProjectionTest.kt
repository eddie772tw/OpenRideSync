package org.openridesync.app.calls

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.Gen1Projection

class CallProjectionTest {
    @Test fun huIncomingPopupHasVerifiedPaletteActionsAndBoundedText() {
        val incoming = CallItem("id", "Dialer", "synthetic.dialer", "來電者".repeat(200),
            CallKind.INCOMING, setOf(CallAction.ANSWER, CallAction.DECLINE), false)
        val popup = CallProjection.popup(incoming, "Incoming call")!!
        assertEquals(16, popup.background)
        assertNull(popup.contentIcon)
        assertEquals(12, popup.left.contentIcon)
        assertEquals(2, popup.left.background)
        assertEquals(13, popup.right.contentIcon)
        assertEquals(3, popup.right.background)
        assertTrue(popup.text.toByteArray(Charsets.UTF_8).size <= 120)
        assertTrue(Gen1Projection.encode(popup, 1).size <= 512)
        assertEquals("Incoming call", CallProjection.popup(incoming.copy(caller = ""),
            "Incoming call")!!.text)
        val spent = CallProjection.popup(incoming.copy(actions = emptySet()), "Incoming call")!!
        assertNull(spent.left.contentIcon)
        assertNull(spent.right.contentIcon)
        assertNull(CallProjection.popup(incoming.copy(kind = CallKind.ONGOING), "Incoming call"))
    }

    @Test fun verifiedCallIconsActionsAndBoundedCallerEncode() {
        val incoming = CallItem("id", "Dialer", "synthetic.dialer", "來電者".repeat(200),
            CallKind.INCOMING, setOf(CallAction.ANSWER, CallAction.DECLINE), false)
        val live = CallProjection.live(incoming, "Ringing", "Active")
        assertEquals(8, live.color)
        assertEquals(9, live.colorDark)
        assertEquals(12, live.left.contentIcon)
        assertEquals(2, live.left.background)
        assertEquals(13, live.right.contentIcon)
        assertEquals(3, live.right.background)
        assertTrue(live.line1!!.toByteArray(Charsets.UTF_8).size <= 120)
        assertTrue(Gen1Projection.encode(live, 1).size <= 512)

        val legacy = incoming.copy(caller = "", kind = CallKind.ONGOING,
            actions = setOf(CallAction.HANG_UP))
        assertEquals(93, CallProjection.live(legacy, "Ringing", "Active").contentIcon)
        assertEquals(2, CallProjection.live(legacy, "Ringing", "Active").left.contentIcon)
        assertEquals(93, CallProjection.menu(legacy, "Call", true, true).contentIcon)
        val spent = legacy.copy(actions = emptySet())
        assertNull(CallProjection.live(spent, "Ringing", "Active").left.contentIcon)

        val cancel = CallProjection.cancel("End call?")
        assertEquals(106, cancel.contentIcon)
        assertEquals(15, cancel.left.contentIcon)
        assertEquals(9, cancel.right.contentIcon)
        assertEquals(7, cancel.left.background)
        assertEquals(7, cancel.right.background)
        assertTrue(Gen1Projection.encode(cancel, 2).size <= 512)
    }
}
