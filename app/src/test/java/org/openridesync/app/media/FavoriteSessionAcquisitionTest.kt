package org.openridesync.app.media

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.media.FavoriteSessionAcquisition.Decision

class FavoriteSessionAcquisitionTest {
    @Test fun noSourceWaitsUntilTheExistingDeadline() {
        val acquisition = FavoriteSessionAcquisition()
        repeat(10) { assertEquals(Decision.Wait, acquisition.inspect(emptyList(), false, false)) }
        assertEquals(Decision.Unavailable, acquisition.inspect(emptyList(), false, true))
        assertFalse(acquisition.foundSession)
    }

    @Test fun pendingBrowserMustContributeBeforeAnOrdinarySessionIsChosen() {
        val acquisition = FavoriteSessionAcquisition()
        assertEquals(Decision.Wait, acquisition.inspect(listOf("manager"), true, false))
        assertFalse(acquisition.foundSession)
        assertEquals(Decision.Ambiguous, acquisition.inspect(listOf("manager", "browser"), false, false))
    }

    @Test fun deadlineStopsPendingBrowserThenAllowsOrdinarySourceFallback() {
        val acquisition = FavoriteSessionAcquisition()
        assertEquals(Decision.StopBrowser, acquisition.inspect(listOf("manager"), true, true))
        assertFalse(acquisition.foundSession)
        assertEquals(Decision.Select("manager"), acquisition.inspect(listOf("manager"), false, true))
    }

    @Test fun browserFailureDoesNotBlockOrdinarySourceFallback() {
        val acquisition = FavoriteSessionAcquisition()
        assertEquals(Decision.Wait, acquisition.inspect(emptyList(), true, false))
        assertEquals(Decision.Select("notification"), acquisition.inspect(listOf("notification"), false, false))
    }

    @Test fun sameIdentitySurvivesRepeatedSnapshotsAndSourceDeduplication() {
        val acquisition = FavoriteSessionAcquisition()
        repeat(11) { index ->
            assertEquals(Decision.Select("same"), acquisition.inspect(listOf("same", "same"), false, index == 10))
        }
        assertTrue(acquisition.foundSession)
    }

    @Test fun replacementWhileWaitingForPlayCapabilityNeverBecomesTheSelectedSession() {
        val acquisition = FavoriteSessionAcquisition()
        assertEquals(Decision.Select("original"), acquisition.inspect(listOf("original"), false, false))
        assertEquals(Decision.Unavailable, acquisition.inspect(listOf("replacement"), false, false))
        assertEquals(Decision.Unavailable, acquisition.inspect(listOf("replacement"), false, true))
    }

    @Test fun disappearingAndReturningSessionCannotReviveAnEndedAttempt() {
        val acquisition = FavoriteSessionAcquisition()
        acquisition.inspect(listOf("original"), false, false)
        assertEquals(Decision.Unavailable, acquisition.inspect(emptyList(), false, false))
        assertEquals(Decision.Unavailable, acquisition.inspect(listOf("original"), false, false))
    }

    @Test fun browserSuspensionDoesNotHideLossOfAnAlreadySelectedSession() {
        val acquisition = FavoriteSessionAcquisition()
        acquisition.inspect(listOf("browser"), false, false)
        assertEquals(Decision.Unavailable, acquisition.inspect(emptyList(), true, false))
    }

    @Test fun browserSuspensionKeepsExactIdentityAvailableFromAnotherSource() {
        val acquisition = FavoriteSessionAcquisition()
        acquisition.inspect(listOf("same"), false, false)
        assertEquals(Decision.Wait, acquisition.inspect(listOf("same"), true, false))
        assertEquals(Decision.StopBrowser, acquisition.inspect(listOf("same"), true, true))
        assertEquals(Decision.Select("same"), acquisition.inspect(listOf("same"), false, true))
    }

    @Test fun secondDistinctSessionRequiresManualSelectionEvenAfterPinning() {
        val acquisition = FavoriteSessionAcquisition()
        acquisition.inspect(listOf("first"), false, false)
        assertEquals(Decision.Ambiguous, acquisition.inspect(listOf("first", "second"), false, false))
        assertEquals(Decision.Ambiguous, acquisition.inspect(listOf("second"), false, false))
    }

    @Test fun expiredEmptyAttemptCannotRestartFromALateBrowserCallback() {
        val acquisition = FavoriteSessionAcquisition()
        acquisition.inspect(emptyList(), false, true)
        assertEquals(Decision.Unavailable, acquisition.inspect(listOf("late"), false, false))
    }
}
