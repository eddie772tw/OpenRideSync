package org.openridesync.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaJourneyTest {
    private val choice = MediaSessionChoice("one", "Player", "synthetic.invalid")
    private val ready = MediaPageState(signedIn = true, observing = true,
        media = MediaState(access = MediaAccess.GRANTED, sessions = listOf(choice), selectedId = choice.id))

    @Test fun signedOutAlwaysOffersAccountEvenWithOldMetadata() {
        assertEquals(MediaJourney.ACCOUNT, mediaJourney(ready.copy(signedIn = false)))
    }
    @Test fun stoppedObservationNeverOffersTransportControls() {
        assertEquals(MediaJourney.PAUSED, mediaJourney(ready.copy(observing = false)))
    }
    @Test fun permissionAndUnavailableAreDifferentFromEmpty() {
        assertEquals(MediaJourney.ACCESS_REQUIRED, mediaJourney(ready.copy(media = ready.media.copy(access = MediaAccess.REQUIRED))))
        assertEquals(MediaJourney.UNAVAILABLE, mediaJourney(ready.copy(media = ready.media.copy(access = MediaAccess.UNAVAILABLE))))
        assertEquals(MediaJourney.EMPTY, mediaJourney(ready.copy(media = MediaState(access = MediaAccess.GRANTED))))
    }
    @Test fun actualSessionsNeedExplicitSelection() {
        assertEquals(MediaJourney.CHOOSE, mediaJourney(ready.copy(media = ready.media.copy(selectedId = null))))
    }
    @Test fun onlyUniqueCurrentSelectionIsReady() {
        assertEquals(MediaJourney.READY, mediaJourney(ready))
        assertEquals(MediaJourney.STALE, mediaJourney(ready.copy(media = ready.media.copy(sessions = emptyList()))))
        assertEquals(MediaJourney.STALE, mediaJourney(ready.copy(media = ready.media.copy(selectedId = "old"))))
        assertEquals(MediaJourney.STALE, mediaJourney(ready.copy(media = ready.media.copy(sessions = listOf(choice, choice)))))
    }
    @Test fun ridePreferenceOrConnectionDoesNotCreatePhoneSelection() {
        assertEquals(MediaJourney.EMPTY, mediaJourney(MediaPageState(signedIn = true, observing = true,
            media = MediaState(access = MediaAccess.GRANTED), rideConnected = true, rideSelection = choice)))
    }
}
