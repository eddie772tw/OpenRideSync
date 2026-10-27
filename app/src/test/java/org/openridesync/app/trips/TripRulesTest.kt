package org.openridesync.app.trips

import com.google.protobuf.UnknownFieldSet
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.honda.wire.HondaWire.*
import java.time.Instant

class TripRulesTest {
    private fun seconds(value: String) = Instant.parse(value).epochSecond
    private fun day(date: String, start: String, id: String) = DailyTrips.newBuilder()
        .setTripDate(seconds(date)).setStartTime(seconds(start)).setEndTime(seconds(start) + 3600)
        .addTripUuids(id).setTotalDistance(1000f).setTotalTime(3600).setAvgSpeed(10f).build()

    @Test fun fixedOffsetPagingUsesOldestStartDayAndStopsOnShortOrNonAdvancingPage() {
        val cursor = seconds("2026-10-15T13:24:56Z") * 1000 + 999
        val offset = 8 * 3600 * 1000
        val first = day("2026-10-14T16:00:00Z", "2026-10-15T01:00:00Z", "new")
        val second = day("2026-10-13T16:00:00Z", "2026-10-13T23:00:00Z", "old")
        val response = ListDailyTripsResponse.newBuilder().addTrips(first).addTrips(second).build()
        val page = tripPage(response, cursor, offset, 2)
        assertEquals(listOf(first, second), page.days)
        assertEquals(seconds("2026-10-13T15:59:59Z") * 1000 + 999, page.nextCursorMillis)
        val request = tripRequest(page.nextCursorMillis!!, offset)
        assertTrue(request.hasOffsetFromUtc())
        assertEquals(28800, request.offsetFromUtc)
        assertEquals(seconds("2026-10-13T15:59:59Z"), request.startDate)
        assertEquals(100, request.tripsCount)
        assertNull(tripPage(response, cursor, offset, 100).nextCursorMillis)
        assertNull(tripPage(response, page.nextCursorMillis, offset, 2).nextCursorMillis)
        assertNull(tripPage(ListDailyTripsResponse.getDefaultInstance(), cursor, offset).nextCursorMillis)
        assertThrows(TripDataException::class.java) { tripPage(response, cursor, offset, 1) }
        // Refresh merges by the authoritative date key rather than duplicating the same day.
        assertEquals(2, mergeTripDays(listOf(second), page.days).size)
    }

    @Test fun offsetIsFixedAcrossDstAndSummaryRemainsIndependentOfLoadedRows() {
        val cursor = seconds("2026-03-10T00:00:00Z") * 1000
        val old = day("2026-03-07T05:00:00Z", "2026-03-07T12:00:00Z", "old")
        val page = tripPage(ListDailyTripsResponse.newBuilder().addTrips(old).build(), cursor, -4 * 3600 * 1000, 1)
        assertEquals(seconds("2026-03-07T03:59:59Z") * 1000 + 999, page.nextCursorMillis)
        val summary = TripsSummary.newBuilder().setTotalDistance(123456f).setTotalTime(10000L).setTotalDriveDay(-1).build()
        assertEquals(summary, tripSummary(ListDailyTripsResponse.newBuilder().setTripsSummary(summary).addTrips(old).build()))
        assertEquals(TripsSummary.getDefaultInstance(), tripSummary(ListDailyTripsResponse.getDefaultInstance()))
        assertThrows(TripDataException::class.java) {
            tripSummary(ListDailyTripsResponse.newBuilder().setTripsSummary(summary.toBuilder().setTotalTime(-1L)).build())
        }
    }

    @Test fun detailOrderEmptyRoutesAndResponseIdentityAreValidatedWithoutDroppingUnknownFields() {
        val unknown = UnknownFieldSet.newBuilder().addField(100, UnknownFieldSet.Field.newBuilder().addVarint(77).build()).build()
        val day = DailyTrips.newBuilder().addTripUuids("a").addTripUuids("b").addTripUuids("empty").build()
        fun location(time: Long) = TripLocation.newBuilder().setTimestamp(time).setCoordinates(Coord.newBuilder().setLat(25.0).setLon(121.0)).build()
        val a = TripDetail.newBuilder().setTripUuid("a").addTripLocations(location(20)).addTripLocations(location(10)).setUnknownFields(unknown).build()
        val b = TripDetail.newBuilder().setTripUuid("b").addTripLocations(location(5)).build()
        val response = ListTripDetailsResponse.newBuilder().addDailyTripDetails(a).addDailyTripDetails(b)
            .addDailyTripDetails(TripDetail.newBuilder().setTripUuid("empty")).setUnknownFields(unknown).build()
        val ordered = tripDetails(day, response)
        assertEquals(listOf("b", "a"), ordered.dailyTripDetailsList.map { it.tripUuid })
        assertEquals(listOf(10L, 20L), ordered.getDailyTripDetails(1).tripLocationsList.map { it.timestamp })
        assertEquals(unknown, ordered.unknownFields)
        assertEquals(unknown, ordered.getDailyTripDetails(1).unknownFields)
        assertThrows(TripDataException::class.java) { tripDetails(day, ListTripDetailsResponse.getDefaultInstance()) }
        assertThrows(TripDataException::class.java) { tripDetails(day, response.toBuilder().addDailyTripDetails(a).build()) }
        assertEquals(1, tripDetails(day, response.toBuilder().clearDailyTripDetails().addDailyTripDetails(a).build()).dailyTripDetailsCount)
        assertThrows(TripDataException::class.java) {
            tripDetails(day, response.toBuilder().setDailyTripDetails(0, a.toBuilder().setTripUuid("other-account-trip")).build())
        }
        assertThrows(TripDataException::class.java) {
            tripDetails(day, response.toBuilder().setDailyTripDetails(0, a.toBuilder().setTripLocations(0,
                location(10).toBuilder().setCoordinates(Coord.newBuilder().setLat(Double.NaN)))).build())
        }
    }
}
