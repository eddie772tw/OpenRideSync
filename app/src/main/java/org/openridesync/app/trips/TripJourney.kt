package org.openridesync.app.trips

internal fun tripNeedsAccount(code: String?): Boolean = code in setOf("AUTH_REQUIRED", "UNAUTHENTICATED")
internal fun tripListIsEmpty(state: TripState): Boolean = state.selectedDay == null && state.hasLoaded &&
    !state.busy && !state.cancelled && state.errorCode == null && state.days.isEmpty()

/** Detail status never overwrites the history outcome or its pending pagination recovery. */
internal fun restoreTripHistory(parent: TripState): TripState = parent.copy(busy = false,
    selectedDay = null, details = null, detailsCached = false)

/** Visibility is necessary, never sufficient: only an explicit read creates a ticket. */
internal class TripReadGate {
    private var visible = false
    private var generation = 0L
    private var ticket: Long? = null
    @Synchronized fun onStart() { visible = true }
    @Synchronized fun onStop() { visible = false; cancel() }
    @Synchronized fun cancel() { ticket = null; generation++ }
    @Synchronized fun canBegin(signedIn: Boolean): Boolean = visible && signedIn
    @Synchronized fun begin(signedIn: Boolean): Long? {
        if (!canBegin(signedIn)) return null
        return (++generation).also { ticket = it }
    }
    @Synchronized fun accepts(value: Long): Boolean = visible && ticket == value
}

internal enum class TripRetryTarget { HISTORY, PAGE, DETAIL }
internal fun tripRetryTarget(state: TripState, hasRetryCursor: Boolean): TripRetryTarget = when {
    state.selectedDay != null -> TripRetryTarget.DETAIL
    hasRetryCursor -> TripRetryTarget.PAGE
    else -> TripRetryTarget.HISTORY
}
