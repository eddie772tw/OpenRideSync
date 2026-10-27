package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.Gen1Projection

class VolumeProjectionTest {
    private fun state(level: Int = 3, current: Int = 5, fixed: Boolean = false,
                      stream: RideVolume.Stream = RideVolume.Stream.MEDIA, filter: Int = 1) =
        RideVolume.State(stream, 3, current, 0, 10, 50, level, fixed, filter)

    @Test fun exactMenuAndLiveFieldsUseVerticalActionArrowsWithoutPercentage() {
        val menu = VolumeProjection.menu("Volume", first = true, last = false)
        assertEquals(Gen1Projection.Menu(17, 16, "Volume", down = Gen1Projection.Arrow.DOWN,
            right = Gen1Projection.Arrow.RIGHT_BIG, contentIcon = 5), menu)
        val page = VolumeProjection.live(state(), "Volume")
        assertEquals(Gen1Projection.Live(17, 18, "Volume",
            Gen1Projection.Action(Gen1Projection.Arrow.DOWN, 10, 16),
            Gen1Projection.Action(Gen1Projection.Arrow.UP, 11, 16), contentIcon = 121), page)
        assertTrue(Gen1Projection.encode(menu, 0).size <= 512)
        assertTrue(Gen1Projection.encode(page, 1).size <= 512)
        for (level in 0..5) assertEquals(118 + level, VolumeProjection.live(state(level), "Volume").contentIcon)
        assertEquals(117, VolumeProjection.live(state(6), "Volume").contentIcon)
    }

    @Test fun missingFixedBoundsAndMutedMediaDoNotInventVolumeOrActions() {
        val missing = VolumeProjection.live(null, "Volume")
        assertEquals(117, missing.contentIcon); assertNull(missing.left.arrow); assertNull(missing.right.arrow)
        val fixed = VolumeProjection.live(state(fixed = true), "Volume")
        assertNull(fixed.left.arrow); assertNull(fixed.right.arrow)
        assertNull(VolumeProjection.live(state(current = 0), "Volume").left.arrow)
        assertNull(VolumeProjection.live(state(current = 10), "Volume").right.arrow)
        assertEquals(118, VolumeProjection.live(state(filter = 3), "Volume").contentIcon)
        assertEquals(121, VolumeProjection.live(state(stream = RideVolume.Stream.CALL, filter = 3), "Volume").contentIcon)
        assertEquals(Gen1Projection.Arrow.UP,
            VolumeProjection.live(state(current = 10, filter = 3), "Volume").right.arrow)
        val mutedCall = VolumeProjection.live(state(current = 10, stream = RideVolume.Stream.CALL, filter = 3), "Volume")
        assertEquals(Gen1Projection.Arrow.UP, mutedCall.right.arrow)
        assertEquals(121, mutedCall.contentIcon)
        assertEquals(Gen1Projection.Arrow.UP,
            VolumeProjection.live(state(current = 10, fixed = true, filter = 3), "Volume").right.arrow)
    }
}
