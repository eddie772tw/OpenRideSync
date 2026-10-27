package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.honda.PairedVehicleStore.State

class RidePolicyTest {
    @Test fun onlyConfirmedGen1EnrollmentCanStartWithoutAdvancingGarageState() {
        assertEquals(1, rideGeneration(State.PAIRED, 1))
        assertEquals(2, rideGeneration(State.READY, 2))
        for (state in State.values()) if (state !in setOf(State.PAIRED, State.READY)) {
            assertEquals("INVALID_STATE", assertThrows(RideFailure::class.java) { rideGeneration(state, 1) }.code)
        }
        for (generation in listOf(-1, 0, 3, 99)) {
            assertEquals("UNSUPPORTED", assertThrows(RideFailure::class.java) { rideGeneration(State.PAIRED, generation) }.code)
        }
    }
}
