package org.openridesync.app.navigation

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.Gen1Projection

class NavigationProjectionTest {
    @Test fun supportedManeuversKeepExactWireIconsAndReadOnlyActions() {
        val icons = mapOf(
            "LANEGUIDANCE_TICK" to 22,
            "LANE_DOTS" to 18, "LANE_NORMAL" to 24, "LANE_NORMAL_SHORT" to 23,
            "LANE_SHARP" to 26, "LANE_SHARP_SHORT" to 25,
            "LANE_SLIGHT" to 28, "LANE_SLIGHT_TALL" to 27,
            "LANE_STRAIGHT" to 20, "LANE_STRAIGHT_TALL" to 19, "LANE_STUB" to 21,
            "LANE_UTURN" to 30, "LANE_UTURN_SHORT" to 29,
            "ARRIVE" to 40, "ARRIVE_RIGHT" to 52, "DEPART" to 41, "FERRY" to 78,
            "FORK_RIGHT" to 48, "GENERIC_MERGE" to 42, "GENERIC_ROUNDABOUT" to 49,
            "MYLOCATION" to 43, "RAMP_RIGHT" to 50, "RIGHT" to 44,
            "ROUNDABOUT_1" to 62, "ROUNDABOUT_2" to 63, "ROUNDABOUT_3" to 64,
            "ROUNDABOUT_4" to 65, "ROUNDABOUT_5" to 66, "ROUNDABOUT_6" to 67,
            "ROUNDABOUT_7" to 68, "ROUNDABOUT_8" to 69,
            "ROUNDABOUT_EXIT" to 51, "SHARP_RIGHT" to 45, "SLIGHT_RIGHT" to 46,
            "STRAIGHT" to 39, "U_TURN" to 47, "ARRIVE_LEFT" to 61,
            "FORK_LEFT" to 57, "RAMP_LEFT" to 59, "LEFT" to 53,
            "SHARP_LEFT" to 54, "SLIGHT_LEFT" to 55,
            "LANE_NORMAL_LEFT" to 32, "LANE_NORMAL_SHORT_LEFT" to 31,
            "LANE_SHARP_LEFT" to 34, "LANE_SHARP_SHORT_LEFT" to 33,
            "LANE_SLIGHT_LEFT" to 36, "LANE_SLIGHT_TALL_LEFT" to 35,
            "LANE_UTURN_LEFT" to 38, "LANE_UTURN_SHORT_LEFT" to 37,
            "ROUNDABOUT_1_LEFT" to 70, "ROUNDABOUT_2_LEFT" to 71,
            "ROUNDABOUT_3_LEFT" to 72, "ROUNDABOUT_4_LEFT" to 73,
            "ROUNDABOUT_5_LEFT" to 74, "ROUNDABOUT_6_LEFT" to 75,
            "ROUNDABOUT_7_LEFT" to 76, "ROUNDABOUT_8_LEFT" to 77,
            "ROUNDABOUT_EXIT_LEFT" to 60, "GENERIC_ROUNDABOUT_LEFT" to 58,
            "U_TURN_LEFT" to 56, "PLACE" to 40,
        )
        assertEquals(icons.keys, NavigationProjection.Maneuver.values().map { it.name }.toSet())
        for (maneuver in NavigationProjection.Maneuver.values()) {
            val page = checkNotNull(NavigationProjection.active(maneuver, "500 m", "Navigating"))
            assertEquals(5, page.color)
            assertEquals(6, page.colorDark)
            assertEquals(icons[maneuver.name], page.contentIcon)
            assertEquals("500 m", page.line1)
            assertEquals(Gen1Projection.Action(), page.left)
            assertEquals(Gen1Projection.Action(), page.right)
            assertTrue(Gen1Projection.supports(page, 4))
            assertTrue(Gen1Projection.encode(page, 1).size <= 512)
        }
    }

    @Test fun missingManeuverOrHeaderDoesNotInventGuidance() {
        assertNull(NavigationProjection.active(null, "500 m", "Navigating"))
        assertNull(NavigationProjection.active(NavigationProjection.Maneuver.RIGHT, "500 m", " \n "))
        val page = checkNotNull(NavigationProjection.active(NavigationProjection.Maneuver.RIGHT, " \t ", "Navigating"))
        assertNull(page.line1)
        assertNull(NavigationProjection.active(null, null, "Navigating"))
    }

    @Test fun ridePagesExposeOnlyWiredActionsAndUnknownTurnClearsIcon() {
        val menu = NavigationProjection.menu("Navigation", first = false, last = true)
        assertEquals(5, menu.color)
        assertEquals(4, menu.colorLight)
        assertEquals(2, menu.contentIcon)
        assertEquals(Gen1Projection.Arrow.UP, menu.up)
        assertNull(menu.down)
        assertEquals(Gen1Projection.Arrow.RIGHT_BIG, menu.right)
        assertEquals(92, NavigationProjection.menu("Navigating", true, true,
            MapsInstruction("Turn", "300 m", null, null)).contentIcon)
        assertEquals(NavigationProjection.Maneuver.RIGHT.icon,
            NavigationProjection.menu("Navigating", true, true,
                MapsInstruction("Turn", "300 m", null, NavigationProjection.Maneuver.RIGHT)).contentIcon)

        val active = checkNotNull(NavigationProjection.active(NavigationProjection.Maneuver.RIGHT,
            "300 m", "Navigating", canCancel = true, canRepeat = true))
        assertEquals(Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 1, 4), active.left)
        assertEquals(Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 6, 4), active.right)
        val unknown = NavigationProjection.waiting("Navigating", canCancel = true, canRepeat = true)
        assertNull(unknown.contentIcon)
        assertEquals(active.left, unknown.left)
        assertEquals(active.right, unknown.right)
        val confirm = NavigationProjection.cancel("End?")
        assertEquals(101, confirm.contentIcon)
        assertEquals(Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 15, 4), confirm.left)
        assertEquals(Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 9, 4), confirm.right)
        listOf(menu, active, unknown, confirm).forEach {
            assertTrue(Gen1Projection.encode(it, 1).size <= 512)
        }
    }

    @Test fun longUnicodeAndControlsFitUtf8AndWholeFrame() {
        val page = checkNotNull(NavigationProjection.active(NavigationProjection.Maneuver.LEFT,
            "😀距離".repeat(512), "\u202e😀導航".repeat(512)))
        assertTrue(checkNotNull(page.header).toByteArray(Charsets.UTF_8).size <= 120)
        assertNull(page.line1) // No truncated number or unit is displayed.
        assertFalse(page.header!!.contains('\u202e'))
        assertTrue(Gen1Projection.encode(page, 255).size <= 512)
        val alteredNumber = checkNotNull(NavigationProjection.active(NavigationProjection.Maneuver.RIGHT,
            "1\u00002 km", "Navigating"))
        assertNull(alteredNumber.line1) // Sanitization must not silently turn this into "12 km".
        val whole = "1".repeat(27) + " km"
        assertEquals(whole, NavigationProjection.active(NavigationProjection.Maneuver.RIGHT, whole, "N")!!.line1)
        assertNull(NavigationProjection.active(NavigationProjection.Maneuver.RIGHT, "1$whole", "N")!!.line1)
    }
}
