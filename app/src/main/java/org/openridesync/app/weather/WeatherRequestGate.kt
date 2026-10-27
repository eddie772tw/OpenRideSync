package org.openridesync.app.weather

/** Phone-page intent only. Lifecycle/access observations never authorize a weather request. */
internal class WeatherRequestGate {
    private var visible = false
    private var requested = false
    private var generation = 0L

    @Synchronized fun onStart() { visible = true }
    @Synchronized fun onStop() { visible = false; cancel() }
    @Synchronized fun cancel() { requested = false; generation++ }

    /** Called only by an explicit query, or the timer of that query while continuously visible. */
    @Synchronized fun begin(signedIn: Boolean, locationGranted: Boolean): Long? {
        if (!visible || !signedIn || !locationGranted) return null
        requested = true
        return ++generation
    }

    @Synchronized fun onFailure(code: String): Boolean {
        val requiresNewQuery = code in setOf("AUTH_REQUIRED", "UNAUTHENTICATED", "LOCATION_PERMISSION", "PERMISSION_DENIED")
        if (requiresNewQuery) cancel()
        return requiresNewQuery
    }

    @Synchronized fun accepts(ticket: Long): Boolean = visible && requested && ticket == generation
    @Synchronized fun mayRefreshAutomatically(): Boolean = visible && requested
}
