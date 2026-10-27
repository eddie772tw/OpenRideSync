package org.openridesync.app.trips

import org.openridesync.honda.wire.HondaWire.DailyTrips
import org.openridesync.honda.wire.HondaWire.TripLocation
import org.openridesync.honda.wire.HondaWire.TripsSummary
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/** Summary numerator and denominator belong to the same all-history response, never loaded pages. */
internal fun tripSummarySpeed(summary: TripsSummary?): Float? = summary?.let {
    if (!it.totalDistance.isFinite() || it.totalDistance < 0 || it.totalTime <= 0) null
    else (it.totalDistance.toDouble() / it.totalTime).toFloat().takeIf(Float::isFinite)
}

internal fun loadedTripCount(days: List<DailyTrips>): Int = days.asSequence()
    .flatMap { it.tripUuidsList.asSequence() }.filter { it.isNotBlank() }.distinct().count()

internal data class RoutePoint(val x: Float, val y: Float)
internal data class TripRoute(val locations: List<TripLocation>, val points: List<RoutePoint>) {
    val coarse: Boolean get() = locations.any { it.isCoarse }
}

/** Reject an invalid route as a whole: dropping a bad point could invent a connecting segment. */
internal fun tripRoute(locations: List<TripLocation>): TripRoute? {
    if (locations.isEmpty() || locations.any {
        !it.hasCoordinates() || !it.coordinates.lat.isFinite() || !it.coordinates.lon.isFinite() ||
            it.coordinates.lat !in -90.0..90.0 || it.coordinates.lon !in -180.0..180.0 ||
            it.timestamp < 0 || it.timestamp > Long.MAX_VALUE / 1000
    }) return null
    val ordered = locations.sortedBy { it.timestamp }
    // Unwrap adjacent longitudes across +/-180, keeping a local journey continuous.
    val longitude = ArrayList<Double>(ordered.size)
    ordered.forEach { location ->
        val raw = location.coordinates.lon
        val previous = longitude.lastOrNull()
        longitude += if (previous == null) raw else {
            val delta = ((raw - previous + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            previous + delta
        }
    }
    val minLat = ordered.minOf { it.coordinates.lat }
    val maxLat = ordered.maxOf { it.coordinates.lat }
    val longitudeScale = cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.000001)
    val x = longitude.map { it * longitudeScale }
    val minX = x.min(); val maxX = x.max()
    val span = max(maxX - minX, maxLat - minLat)
    val points = if (span <= 1e-12) ordered.map { RoutePoint(0.5f, 0.5f) } else ordered.indices.map {
        RoutePoint((0.5 + (x[it] - (minX + maxX) / 2) / span).toFloat(),
            (0.5 - (ordered[it].coordinates.lat - (minLat + maxLat) / 2) / span).toFloat())
    }
    return TripRoute(ordered, points)
}

internal fun routeCoordinate(location: TripLocation): String = String.format(Locale.ROOT,
    "%.3f°, %.3f°", location.coordinates.lat, location.coordinates.lon)
