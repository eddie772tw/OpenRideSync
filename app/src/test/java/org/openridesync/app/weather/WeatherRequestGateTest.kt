package org.openridesync.app.weather

import org.junit.Assert.*
import org.junit.Test

class WeatherRequestGateTest {
    @Test fun firstEntryAndRepeatedResumeDoNotAuthorizeQueries() {
        val gate = WeatherRequestGate()
        repeat(3) { gate.onStart() }
        assertFalse(gate.mayRefreshAutomatically())
        assertFalse(gate.accepts(0))
    }

    @Test fun signedOutDeniedOrBackgroundRequestsDoNotStart() {
        val gate = WeatherRequestGate()
        assertNull(gate.begin(true, true))
        gate.onStart()
        assertNull(gate.begin(false, true))
        assertNull(gate.begin(true, false))
        assertFalse(gate.mayRefreshAutomatically())
    }

    @Test fun explicitQueryAuthorizesOneCurrentTicketAndVisibleTimer() {
        val gate = WeatherRequestGate()
        gate.onStart()
        val first = gate.begin(true, true)!!
        assertTrue(gate.accepts(first))
        assertTrue(gate.mayRefreshAutomatically())
        val replacement = gate.begin(true, true)!!
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(replacement))
    }

    @Test fun cancelRejectsLateLocationAndCurrentWeatherCallbacks() {
        val gate = WeatherRequestGate()
        gate.onStart()
        val ticket = gate.begin(true, true)!!
        var remoteCalls = 0
        val delayedLocation = { if (gate.accepts(ticket)) remoteCalls++ }
        val delayedCurrentWeather = { if (gate.accepts(ticket)) remoteCalls++ }
        gate.cancel()
        delayedLocation()
        delayedCurrentWeather()
        assertEquals(0, remoteCalls)
        assertFalse(gate.mayRefreshAutomatically())
    }

    @Test fun authOrPermissionFailureStopsAuthorizedTimerWithoutChangingAuthSession() {
        val gate = WeatherRequestGate()
        gate.onStart()
        for (code in listOf("AUTH_REQUIRED", "UNAUTHENTICATED", "LOCATION_PERMISSION", "PERMISSION_DENIED")) {
            val old = gate.begin(true, true)!!
            assertTrue(gate.onFailure(code))
            assertFalse(gate.accepts(old))
            assertFalse(gate.mayRefreshAutomatically())
        }
    }

    @Test fun transientServiceFailureDoesNotClaimLostPermissionOrAccount() {
        val gate = WeatherRequestGate()
        gate.onStart()
        val ticket = gate.begin(true, true)!!
        assertFalse(gate.onFailure("NETWORK"))
        assertTrue(gate.accepts(ticket))
        assertTrue(gate.mayRefreshAutomatically())
    }

    @Test fun backgroundAndReturnRequireNewExplicitQuery() {
        val gate = WeatherRequestGate()
        gate.onStart()
        val old = gate.begin(true, true)!!
        gate.onStop()
        gate.onStart()
        assertFalse(gate.accepts(old))
        assertFalse(gate.mayRefreshAutomatically())
        assertTrue(gate.accepts(gate.begin(true, true)!!))
    }

    @Test fun identityOrPermissionInvalidationCannotReviveOldRequest() {
        val gate = WeatherRequestGate()
        gate.onStart()
        val old = gate.begin(true, true)!!
        gate.cancel()
        gate.onStart()
        assertFalse(gate.accepts(old))
        assertFalse(gate.mayRefreshAutomatically())
        val next = gate.begin(true, true)!!
        assertFalse(gate.accepts(old))
        assertTrue(gate.accepts(next))
    }
}
