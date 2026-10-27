package org.openridesync.app.ble

import org.junit.Assert.*
import org.junit.Test

class Gen1ProjectionTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun splashSemanticPaletteAndNullEmptyTextUseSourceDefaults() {
        val splash = Gen1Projection.Menu(4, 4, line1 = "Honda", line2 = "RoadSync")
        assertEquals("010704000104000000000005486f6e64610008526f616453796e6300",
            hex(Gen1Projection.encode(splash, 7)))
        assertArrayEquals(Gen1Projection.encode(Gen1Projection.Menu(4, 4), 0),
            Gen1Projection.encode(Gen1Projection.Menu(4, 4, "", contentIcon = 0, line1 = "", line2 = ""), 0))
    }

    @Test fun menuUsesWhiteContentAndBlackArrowsWithExplicitQueueRequestId() {
        val page = Gen1Projection.Menu(5, 4, "H", up = Gen1Projection.Arrow.UP,
            right = Gen1Projection.Arrow.RIGHT_BIG, contentIcon = 17, line1 = "A", line2 = "B")
        assertEquals("01ff0501480001040301000005001100014100014200", hex(Gen1Projection.encode(page, 255)))
        assertTrue(Gen1Projection.supports(page, 4))
        assertFalse(Gen1Projection.supports(page, 2))
        assertFalse(Gen1Projection.supports(page, null))
    }

    @Test fun liveAndPopupPreserveActionAbsenceAndPopupRightOnlyAsymmetry() {
        val live = Gen1Projection.Live(5, 6, left = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 19, 4), line1 = "A")
        assertEquals("010005000205010113000400060001410000", hex(Gen1Projection.encode(live, 0)))
        val popup = Gen1Projection.Popup(4, "A", left = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 19, 5),
            right = Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 20, 6))
        assertEquals("0200040001410001011300050201140006", hex(Gen1Projection.encode(popup, 0)))
        // OEM popup encoder emits action groups only when a nonzero left action exists.
        assertEquals("02000400014100", hex(Gen1Projection.encode(popup.copy(left = Gen1Projection.Action()), 0)))
        assertTrue(Gen1Projection.supports(popup, 2))
        assertFalse(Gen1Projection.supports(popup, 4))
    }

    @Test fun specBudgetsFitUtf8ScalarsAndRemoveOnlyRecoveredBidiControls() {
        assertEquals("車".repeat(10), Gen1Projection.prepareText("車".repeat(100)))
        assertEquals("a".repeat(29), Gen1Projection.prepareText("a".repeat(29) + "😀"))
        assertEquals("😀".repeat(7), Gen1Projection.prepareText("😀".repeat(100)))
        assertEquals(" A\u200c\u200d B ", Gen1Projection.prepareText(" \u202eA\u2066\u200c\u200d\u200f B\u061c "))
        assertEquals("", Gen1Projection.prepareText(null))
        assertEquals("", Gen1Projection.prepareText("\u202e"))
        val popup = Gen1Projection.encode(Gen1Projection.Popup(0, "車".repeat(100)), 0)
        assertEquals(90, popup[4].toInt())
        assertEquals(96, popup.size)
        val arrow = Gen1Projection.Arrow.LEFT
        val text = "a".repeat(1000)
        val menu = Gen1Projection.Menu(0, 0, text, arrow, arrow, arrow, arrow, 94, text, text)
        val action = Gen1Projection.Action(arrow, 1, 0)
        val live = Gen1Projection.Live(0, 0, text, action, action, 94, text, text)
        val fullPopup = Gen1Projection.Popup(0, text, 94, action, action)
        assertEquals(111, Gen1Projection.encode(menu, 0).size)
        assertEquals(114, Gen1Projection.encode(live, 0).size)
        assertEquals(107, Gen1Projection.encode(fullPopup, 0).size)
        assertThrows(IllegalArgumentException::class.java) { Gen1Projection.encode(Gen1Projection.Menu(0, 0), 256) }
        assertThrows(IllegalArgumentException::class.java) { Gen1Projection.encode(Gen1Projection.Menu(0, 0, contentIcon = 256), 0) }
    }
}
