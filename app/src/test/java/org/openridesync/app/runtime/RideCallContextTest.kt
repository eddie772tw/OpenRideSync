package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.media.CallNotificationKind
import org.openridesync.app.media.classifyCallNotification

/** Pure metadata/policy checks only; no platform callback or app-specific VoIP validation. */
class RideCallContextTest {
    @Test fun onlyRecognizedModernCallStyleOngoingIsActive() {
        assertEquals(CallNotificationKind.ACTIVE, classifyCallNotification(34, true, true, 2))
        for (type in listOf(1, 3)) {
            assertEquals(CallNotificationKind.INACTIVE, classifyCallNotification(34, true, true, type))
        }
        for (type in listOf(null, 0, 4, -1)) {
            assertEquals(CallNotificationKind.UNKNOWN, classifyCallNotification(34, true, false, type))
        }
        for (sdk in 30..33) {
            assertEquals(CallNotificationKind.UNKNOWN, classifyCallNotification(sdk, true, true, 2))
        }
        // Category alone (even with a purported call type) cannot establish Active.
        assertEquals(CallNotificationKind.UNKNOWN, classifyCallNotification(36, false, true, 2))
        assertEquals(CallNotificationKind.IGNORE, classifyCallNotification(36, false, false, 2))
    }

    @Test fun cellularObservationFallbackAndEstablishedBoundaryAreExplicit() {
        val notifications = CallContext(notificationActive = true, notificationUnknown = false, listenerConnected = true)
        for (value in listOf(null, -1, 3)) {
            val fallback = cellularCallContext(value, notifications)
            assertTrue(fallback.cellularIdle)
            assertFalse(fallback.cellularEstablished)
            assertFalse(fallback.phoneObserved)
            assertTrue(fallback.notificationActive) // Phone refresh never erases notification state.
        }
        val idle = cellularCallContext(0)
        assertTrue(idle.phoneObserved)
        assertTrue(idle.cellularIdle)
        val ringing = cellularCallContext(1)
        assertTrue(ringing.phoneObserved)
        assertFalse(ringing.cellularIdle)
        assertFalse(ringing.musicBlocked)
        assertTrue(ringing.musicPopupBlocked)
        val established = cellularCallContext(2)
        assertTrue(established.phoneObserved)
        assertTrue(established.cellularEstablished)
        assertTrue(established.musicBlocked)
    }

    @Test fun streamIsUnknownUnlessMediaOrCallIsEstablishedByAvailableContext() {
        assertNull(CallContext().volumeStream)
        val observedNoCall = CallContext(notificationUnknown = false, listenerConnected = true)
        assertEquals(RideVolume.Stream.MEDIA, observedNoCall.volumeStream)
        // Explicit phone permission-denied IDLE fallback can use MEDIA when notifications are known.
        assertFalse(observedNoCall.phoneObserved)
        assertEquals(RideVolume.Stream.CALL, observedNoCall.copy(notificationActive = true).volumeStream)
        assertTrue(observedNoCall.copy(notificationActive = true).musicBlocked)
        assertEquals(RideVolume.Stream.CALL, CallContext(cellularIdle = false).volumeStream)
        assertFalse(observedNoCall.musicBlocked)
        assertFalse(observedNoCall.musicPopupBlocked)
        assertTrue(observedNoCall.copy(notificationIncoming = true).musicPopupBlocked)
        assertFalse(observedNoCall.copy(notificationIncoming = true).musicBlocked)
        assertTrue(observedNoCall.copy(notificationUnknown = true).musicPopupBlocked)
        assertNull(observedNoCall.copy(notificationUnknown = true).volumeStream)
    }
}
