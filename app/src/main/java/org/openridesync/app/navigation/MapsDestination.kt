package org.openridesync.app.navigation

import android.content.Intent
import android.net.Uri

/** User-entered destination only; never persisted or sent to diagnostics. */
internal fun mapsDestinationIntent(query: String): Intent? {
    val destination = query.trim()
    if (destination.isEmpty() || query.length > 512 || query.any { Character.isISOControl(it) }) return null
    return Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d"))
        .setPackage(MAPS_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal enum class NavigationLaunchResult { DISPATCHED, INVALID, UNAVAILABLE, LOCKED }
