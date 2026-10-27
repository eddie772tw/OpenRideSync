package org.openridesync.app.trips

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.honda.wire.HondaWire.*

class TripRouteTest {
    private fun point(lat: Double, lon: Double, time: Long = 1, coarse: Boolean = false) =
        TripLocation.newBuilder().setCoordinates(Coord.newBuilder().setLat(lat).setLon(lon))
            .setTimestamp(time).setIsCoarse(coarse).build()

    @Test fun summaryAverageNeverUsesDayCountOrLoadedPageMetrics() {
        val summary = TripsSummary.newBuilder().setTotalDistance(72000f).setTotalTime(3600).setTotalDriveDay(99).build()
        assertEquals(20f, tripSummarySpeed(summary)!!, 0f)
        assertNull(tripSummarySpeed(null))
        for (time in listOf(0L, -1L)) assertNull(tripSummarySpeed(summary.toBuilder().setTotalTime(time).build()))
        for (distance in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f))
            assertNull(tripSummarySpeed(summary.toBuilder().setTotalDistance(distance).build()))
        assertEquals(0f, tripSummarySpeed(summary.toBuilder().setTotalDistance(0f).build())!!, 0f)
    }

    @Test fun countIsDistinctAcrossLoadedDatesAndExcludesBlankIdentifiers() {
        val a = DailyTrips.newBuilder().addTripUuids("a").addTripUuids("b").build()
        val b = DailyTrips.newBuilder().addTripUuids("b").addTripUuids("c").addTripUuids(" ").build()
        assertEquals(3, loadedTripCount(listOf(a, b)))
        assertEquals(0, loadedTripCount(emptyList()))
    }

    @Test fun routeIsSortedByTimeAndPreservesCoarseEndpoints() {
        val route = tripRoute(listOf(point(25.1, 121.1, 20), point(25.0, 121.0, 10, true)))!!
        assertEquals(listOf(10L, 20L), route.locations.map { it.timestamp })
        assertTrue(route.coarse)
        assertTrue(route.points.first().x < route.points.last().x)
        assertTrue(route.points.first().y > route.points.last().y)
        assertEquals("25.000°, 121.000°", routeCoordinate(route.locations.first()))
    }

    @Test fun antimeridianUsesShortContinuousSegmentsInEitherDirection() {
        for (sign in listOf(1, -1)) {
            val route = tripRoute(listOf(point(0.0, 179.0 * sign, 1), point(0.0, -179.0 * sign, 2),
                point(0.0, -177.0 * sign, 3)))!!
            val xs = route.points.map { it.x }
            assertEquals(0.5f, xs[1], 0.0001f)
            assertEquals(xs[1] - xs[0], xs[2] - xs[1], 0.0001f)
            assertTrue(route.points.all { it.x in 0f..1f && it.y in 0f..1f })
        }
    }

    @Test fun singletonRepeatedPointsAndZeroAxisSpansStayFiniteAndCentered() {
        for (locations in listOf(listOf(point(90.0, 180.0)), List(3) { point(0.0, 0.0) })) {
            assertTrue(tripRoute(locations)!!.points.all { it == RoutePoint(0.5f, 0.5f) })
        }
        assertTrue(tripRoute(listOf(point(1.0, 5.0), point(2.0, 5.0, 2)))!!.points.all { it.x == 0.5f })
        assertTrue(tripRoute(listOf(point(1.0, 5.0), point(1.0, 6.0, 2)))!!.points.all { it.y == 0.5f })
    }

    @Test fun invalidRouteIsRejectedRatherThanBridgedAcrossMissingPoint() {
        assertNull(tripRoute(emptyList()))
        for (invalid in listOf(TripLocation.getDefaultInstance(), point(Double.NaN, 0.0),
            point(0.0, Double.POSITIVE_INFINITY), point(91.0, 0.0), point(0.0, -181.0),
            point(0.0, 0.0, -1), point(0.0, 0.0, Long.MAX_VALUE))) {
            assertNull(tripRoute(listOf(point(1.0, 1.0), invalid, point(2.0, 2.0, 3))))
        }
    }
}
