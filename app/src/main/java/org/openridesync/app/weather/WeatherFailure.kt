package org.openridesync.app.weather

/** A failed forecast must not discard a real current result; invalid location invalidates both. */
internal fun weatherFailureIsForecastOnly(hasCurrent: Boolean, code: String): Boolean =
    hasCurrent && !code.startsWith("LOCATION_")
