package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test

class RideVolumeTest {
    @Test fun recoveredStreamStepPercentageAndManufacturerBoundaries() {
        assertEquals(RideVolume.Stream.MEDIA, RideVolume.selectStream(true, false))
        assertEquals(RideVolume.Stream.CALL, RideVolume.selectStream(false, false))
        assertEquals(RideVolume.Stream.CALL, RideVolume.selectStream(true, true))
        assertEquals(46, RideVolume.percentage(7, 15))
        assertEquals(0, RideVolume.percentage(0, 0))
        assertEquals(listOf(0,1,1,2,3,4,5,5), listOf(0,1,20,21,41,61,81,100).map(RideVolume::level))
        assertEquals(9, RideVolume.target(7, 0, 25, RideVolume.Direction.UP))
        assertEquals(5, RideVolume.target(7, 0, 25, RideVolume.Direction.DOWN))
        assertEquals(1, RideVolume.target(1, 1, 5, RideVolume.Direction.DOWN))
        assertEquals(5, RideVolume.target(5, 1, 5, RideVolume.Direction.UP))
        assertTrue(RideVolume.needsAdjustWorkaround("Realme", 35))
        assertFalse(RideVolume.needsAdjustWorkaround("realme", 36))
        assertTrue(RideVolume.needsAdjustWorkaround("OnePlus", 34))
        assertFalse(RideVolume.needsAdjustWorkaround("oppo", 35))
        assertFalse(RideVolume.needsAdjustWorkaround("google", 34))
    }
}
