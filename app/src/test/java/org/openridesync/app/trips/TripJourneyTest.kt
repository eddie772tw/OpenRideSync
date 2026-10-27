package org.openridesync.app.trips

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.honda.wire.HondaWire.*

class TripJourneyTest {
    private val day = DailyTrips.newBuilder().setTripDate(100).setStartTime(200).addTripUuids("synthetic").build()

    @Test fun failedTerminalPageOutcomeSurvivesDetailReturn() {
        val parent = TripState(days = listOf(day), hasLoaded = true, cached = true, hasMore = false,
            errorCode = "UNAVAILABLE", storageError = true, summaryErrorCode = "DATA_LOSS")
        val restored = restoreTripHistory(parent)
        assertEquals(parent, restored)
        assertEquals(TripRetryTarget.PAGE, tripRetryTarget(restored, true))
    }
    @Test fun cancelledPageIsNotConvertedToSuccessfulHistory() {
        val parent = TripState(days = listOf(day), cancelled = true, hasMore = false)
        assertTrue(restoreTripHistory(parent).cancelled)
        assertEquals(TripRetryTarget.PAGE, tripRetryTarget(parent, true))
    }
    @Test fun detailOnlyOutcomesDoNotLeakIntoParent() {
        val parent = TripState(days = listOf(day), hasLoaded = true)
        val detail = parent.copy(selectedDay = day, busy = true, storageError = true, cancelled = true, errorCode = "UNAVAILABLE")
        assertNotEquals(parent, detail)
        val restored = restoreTripHistory(parent)
        assertFalse(restored.busy); assertFalse(restored.storageError); assertFalse(restored.cancelled)
        assertNull(restored.errorCode); assertNull(restored.selectedDay); assertNull(restored.details)
    }
    @Test fun retryPrioritySeparatesDetailPageAndInitialHistory() {
        assertEquals(TripRetryTarget.DETAIL, tripRetryTarget(TripState(selectedDay = day), true))
        assertEquals(TripRetryTarget.PAGE, tripRetryTarget(TripState(), true))
        assertEquals(TripRetryTarget.HISTORY, tripRetryTarget(TripState(), false))
    }
    @Test fun onlySuccessfulLoadedHistoryMayBeEmpty() {
        assertTrue(tripListIsEmpty(TripState(hasLoaded = true)))
        for (state in listOf(TripState(), TripState(hasLoaded = true, busy = true),
            TripState(hasLoaded = true, cancelled = true), TripState(hasLoaded = true, errorCode = "UNAVAILABLE"),
            TripState(hasLoaded = true, selectedDay = day), TripState(hasLoaded = true, days = listOf(day))))
            assertFalse(tripListIsEmpty(state))
    }
    @Test fun accountErrorsAreNotOrdinaryRetryFailures() {
        assertTrue(tripNeedsAccount("AUTH_REQUIRED")); assertTrue(tripNeedsAccount("UNAUTHENTICATED"))
        assertFalse(tripNeedsAccount("UNAVAILABLE")); assertFalse(tripNeedsAccount(null))
    }
    @Test fun entryAndForegroundDoNotAuthorizeReads() {
        val gate = TripReadGate()
        assertNull(gate.begin(true)); gate.onStart()
        assertFalse(gate.accepts(0)); assertNull(gate.begin(false))
        val ticket = gate.begin(true)!!; assertTrue(gate.accepts(ticket))
        gate.onStop(); gate.onStart(); assertFalse(gate.accepts(ticket))
    }
    @Test fun cancelOrIdentityChangeInvalidatesLateAndTokenRetryCallbacks() {
        val gate = TripReadGate(); gate.onStart()
        val ticket = gate.begin(true)!!; gate.cancel()
        var requests = 0
        if (gate.accepts(ticket)) requests++
        assertEquals(0, requests)
        gate.onStart(); assertFalse(gate.accepts(ticket))
    }
    @Test fun newerExplicitOperationOwnsPublication() {
        val gate = TripReadGate(); gate.onStart()
        val old = gate.begin(true)!!; val current = gate.begin(true)!!
        assertFalse(gate.accepts(old)); assertTrue(gate.accepts(current))
        gate.onStop(); assertFalse(gate.accepts(current)); assertNull(gate.begin(true))
    }
}
