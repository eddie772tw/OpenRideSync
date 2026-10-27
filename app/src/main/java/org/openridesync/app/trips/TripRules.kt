package org.openridesync.app.trips

import org.openridesync.honda.wire.HondaWire.*
import java.util.concurrent.TimeUnit

internal class TripDataException : Exception("invalid trip data")
internal data class TripPage(val days: List<DailyTrips>, val nextCursorMillis: Long?)

internal fun tripRequest(cursorMillis: Long, offsetMillis: Int, count: Int = 100): ListDailyTripsRequest =
    ListDailyTripsRequest.newBuilder().setStartDate(TimeUnit.MILLISECONDS.toSeconds(cursorMillis))
        .setOffsetFromUtc(TimeUnit.MILLISECONDS.toSeconds(offsetMillis.toLong()).toInt()).setTripsCount(count).build()

private fun valid(value: Boolean) { if (!value) throw TripDataException() }
private fun metric(value: Float) = valid(value.isFinite() && value >= 0)
private fun identifier(value: String) = valid(value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() })
private fun millis(seconds: Long): Long = try { valid(seconds >= 0); Math.multiplyExact(seconds, 1000) }
catch (_: ArithmeticException) { throw TripDataException() }

internal fun tripSummary(value: ListDailyTripsResponse): TripsSummary {
    return value.tripsSummary.also { metric(it.totalDistance); valid(it.totalTime >= 0) }
}

internal fun tripPage(response: ListDailyTripsResponse, cursorMillis: Long, offsetMillis: Int, count: Int = 100): TripPage {
    valid(count > 0 && response.tripsCount <= count)
    val dates = HashSet<Long>()
    response.tripsList.forEach {
        valid(dates.add(it.tripDate))
        millis(it.tripDate); millis(it.startTime); millis(it.endTime)
        valid(it.totalTime >= 0); millis(it.totalTime)
        metric(it.totalDistance); metric(it.avgSpeed)
        it.tripUuidsList.forEach(::identifier)
        valid(it.tripUuidsList.distinct().size == it.tripUuidsCount)
    }
    val ascending = response.tripsList.sortedBy { it.tripDate }
    val next = if (ascending.size == count && ascending.isNotEmpty()) {
        try {
            val local = Math.addExact(millis(ascending.first().startTime), offsetMillis.toLong())
            val midnight = Math.subtractExact(Math.multiplyExact(Math.floorDiv(local, 86_400_000L), 86_400_000L), offsetMillis.toLong())
            Math.subtractExact(midnight, 1).takeIf { it < cursorMillis }
        } catch (_: ArithmeticException) { throw TripDataException() }
    } else null
    return TripPage(ascending.reversed(), next)
}

internal fun mergeTripDays(previous: List<DailyTrips>, page: List<DailyTrips>): List<DailyTrips> =
    (previous + page).associateBy { it.tripDate }.values.sortedByDescending { it.tripDate }

internal fun tripDetails(day: DailyTrips, response: ListTripDetailsResponse): ListTripDetailsResponse {
    valid(response.totalTime >= 0); millis(response.totalTime)
    metric(response.totalDistance); metric(response.avgSpeed)
    val requested = day.tripUuidsList.toSet()
    val received = HashSet<String>()
    val details = response.dailyTripDetailsList.map { trip ->
        identifier(trip.tripUuid)
        valid(trip.tripUuid in requested && received.add(trip.tripUuid))
        metric(trip.totalDistance); metric(trip.avgSpeed)
        trip.tripLocationsList.forEach {
            millis(it.timestamp)
            valid(it.hasCoordinates() && it.coordinates.lat.isFinite() && it.coordinates.lon.isFinite() &&
                it.coordinates.lat in -90.0..90.0 && it.coordinates.lon in -180.0..180.0)
        }
        trip.toBuilder().clearTripLocations().addAllTripLocations(trip.tripLocationsList.sortedBy { it.timestamp }).build()
    }
    val paths = details.filter { it.tripLocationsCount > 0 }.sortedBy { it.getTripLocations(0).timestamp }
    // OEM has a distinct EmptyTripDetailException; absence of a path is not a successful empty route.
    valid(paths.isNotEmpty())
    return response.toBuilder().clearDailyTripDetails().addAllDailyTripDetails(paths).build()
}
