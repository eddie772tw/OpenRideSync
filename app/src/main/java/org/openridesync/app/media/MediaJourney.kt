package org.openridesync.app.media

/** Presentation states only. A chosen preference is never evidence of an active session. */
internal enum class MediaJourney { ACCOUNT, PAUSED, ACCESS_REQUIRED, UNAVAILABLE, EMPTY, CHOOSE, STALE, READY }

internal fun mediaJourney(state: MediaPageState): MediaJourney = when {
    !state.signedIn -> MediaJourney.ACCOUNT
    !state.observing -> MediaJourney.PAUSED
    state.media.access == MediaAccess.REQUIRED -> MediaJourney.ACCESS_REQUIRED
    state.media.access == MediaAccess.UNAVAILABLE -> MediaJourney.UNAVAILABLE
    state.media.selectedId != null && state.media.sessions.count { it.id == state.media.selectedId } != 1 -> MediaJourney.STALE
    state.media.sessions.isEmpty() -> MediaJourney.EMPTY
    state.media.selectedId == null -> MediaJourney.CHOOSE
    else -> MediaJourney.READY
}
