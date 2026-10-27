package org.openridesync.app.media

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class NativeMediaPolicyTest {
    @Test fun browserOnlyRideSelectionIsControllableWithoutReplacingAnExplicitPhoneSelection() {
        val local = MediaState(access = MediaAccess.GRANTED)
        val ride = local.copy(selectedId = "browser-owned", actions = PlaybackState.ACTION_PLAY)
        assertSame(ride, mediaPageSelection(local, ride))
        val manual = local.copy(selectedId = "phone-selected")
        assertSame(manual, mediaPageSelection(manual, ride))
        assertSame(local, mediaPageSelection(local, ride.copy(selectedId = null)))
        assertSame(local, mediaPageSelection(local, null))
        val denied = local.copy(access = MediaAccess.REQUIRED)
        assertSame(denied, mediaPageSelection(denied, ride))
        assertSame(local, mediaPageSelection(local, ride.copy(access = MediaAccess.UNAVAILABLE)))
    }

    @Test fun toggleUsesObservedStateAndNeverGuessesForAbsentErrorOrFutureState() {
        assertEquals(PlaybackState.ACTION_PAUSE, mediaToggleAction(PlaybackState.STATE_PLAYING))
        assertEquals(PlaybackState.ACTION_PLAY, mediaToggleAction(PlaybackState.STATE_PAUSED))
        assertEquals(PlaybackState.ACTION_PLAY, mediaToggleAction(PlaybackState.STATE_STOPPED))
        assertNull(mediaToggleAction(null))
        assertNull(mediaToggleAction(PlaybackState.STATE_ERROR))
        assertNull(mediaToggleAction(999))
        for (transient in listOf(PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM)) assertNull(mediaToggleAction(transient))
    }
    @Test fun actionAvailabilityRequiresAccessSelectionAndExactAdvertisedCommand() {
        val commands = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SKIP_TO_NEXT
        val chosen = MediaState(MediaAccess.GRANTED, selectedId = "synthetic-session", actions = commands)
        assertTrue(chosen.canPlay)
        assertTrue(chosen.canNext)
        assertFalse(chosen.canPause)
        assertFalse(chosen.canPrevious)
        assertFalse(chosen.copy(selectedId = null).canPlay)
        assertFalse(chosen.copy(access = MediaAccess.REQUIRED).canNext)
        assertFalse(chosen.copy(access = MediaAccess.UNAVAILABLE).canPlay)
        // A toggle capability does not claim distinct play/pause transport support.
        val toggleOnly = chosen.copy(actions = PlaybackState.ACTION_PLAY_PAUSE)
        assertFalse(toggleOnly.canPlay)
        assertFalse(toggleOnly.canPause)
    }

    @Test fun absentPlaybackIsNotPausedAndUnknownPlatformStateRemainsUnknownToUi() {
        assertEquals(MediaPlayback.UNKNOWN, mediaPlayback(null))
        assertEquals(MediaPlayback.NONE, mediaPlayback(PlaybackState.STATE_NONE))
        assertEquals(MediaPlayback.PLAYING, mediaPlayback(PlaybackState.STATE_PLAYING))
        assertEquals(MediaPlayback.PAUSED, mediaPlayback(PlaybackState.STATE_PAUSED))
        assertEquals(MediaPlayback.OTHER, mediaPlayback(999))
        assertEquals(MediaPlayback.UNKNOWN, MediaState().playback)
    }

    @Test fun displayBoundPreservesSupplementaryUnicodeAtBoundary() {
        val value = "a".repeat(511) + "\uD83C\uDFB5" + "tail"
        assertEquals("a".repeat(511) + "\uD83C\uDFB5", boundedMediaText(value))
        assertEquals("", boundedMediaText(""))
        assertEquals("display", mediaTitle(null, "display"))
        assertEquals("", mediaTitle("", "display"))
        assertNull(mediaTitle(null, null))
        assertEquals(boundedMediaText(value), mediaTitle(value, null))
    }

    @Test fun homeControlTargetNeverGuessesAmongAmbiguousSessionsOrReplacesExplicitSelection() {
        val paused = MediaSessionChoice("paused", "A", "synthetic.a", playback = MediaPlayback.PAUSED)
        val playing = MediaSessionChoice("playing", "B", "synthetic.b", playback = MediaPlayback.PLAYING,
            title = "Song", artist = "Artist", album = "Album", actions = PlaybackState.ACTION_PAUSE)
        val granted = MediaState(access = MediaAccess.GRANTED, sessions = listOf(paused, playing))
        assertEquals(playing, granted.homeControlTarget())
        assertEquals(paused, granted.copy(selectedId = paused.id).homeControlTarget())
        assertNull(granted.copy(selectedId = "stale").homeControlTarget())
        assertNull(granted.copy(sessions = listOf(playing, playing.copy(id = "second"))).homeControlTarget())
        assertNull(granted.copy(access = MediaAccess.REQUIRED).homeControlTarget())
        assertNull(granted.copy(access = MediaAccess.UNAVAILABLE).homeControlTarget())
        assertEquals(paused, granted.copy(sessions = listOf(paused)).homeControlTarget())
        assertEquals("Artist", playing.artist)
        assertEquals("Album", playing.album)
    }
}
