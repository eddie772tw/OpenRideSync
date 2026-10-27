package org.openridesync.app.trips

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.honda.wire.HondaWire.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic production-content interaction tests; no account, backend, device, or location access. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
class TripReviewContentTest {
    @get:Rule val compose = createComposeRule()
    private val day = DailyTrips.newBuilder().setTripDate(1792022400).setStartTime(1792022400)
        .setEndTime(1792026000).addTripUuids("route-one").build()
    private val trip = TripDetail.newBuilder().setTripUuid("route-one").setAvgSpeed(10f)
        .addTripLocations(TripLocation.newBuilder().setTimestamp(1792022400)
            .setCoordinates(Coord.newBuilder().setLat(25.0).setLon(121.0)).setIsCoarse(true))
        .addTripLocations(TripLocation.newBuilder().setTimestamp(1792026000)
            .setCoordinates(Coord.newBuilder().setLat(25.1).setLon(121.1))).build()

    @Test fun headerSeparatesLifetimeSummaryFromLoadedCount() {
        val summary = TripsSummary.newBuilder().setTotalDistance(72000f).setTotalTime(3600).setTotalDriveDay(99).build()
        compose.setContent { MaterialTheme {
            TripHistoryContent(TripState(summary = summary, days = listOf(day), hasLoaded = true, hasMore = true),
                true, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("總距離（全部歷史）：72 km").assertExists()
        compose.onNodeWithText("騎乘次數（已載入範圍）：1").assertExists()
        compose.onNodeWithText("平均速度（全部歷史）：72 km/h").assertExists()
        compose.onNodeWithText("還有更早紀錄尚未載入。").assertExists()
    }

    @Test fun dateExpandsRouteIsLocalAndBackClosesRouteBeforeDate() {
        val parent = TripState(days = listOf(day), hasLoaded = true)
        val state = mutableStateOf(parent)
        var reads = 0; var back = 0
        compose.setContent { MaterialTheme {
            TripHistoryContent(state.value, true, { back++; state.value = parent },
                { reads++ }, { reads++ }, { reads++ }, {
                    reads++
                    state.value = parent.copy(selectedDay = it,
                        details = ListTripDetailsResponse.newBuilder().addDailyTripDetails(trip).build())
                }, {})
        } }
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag("trip_day_${day.tripDate}"))
        compose.onNodeWithTag("trip_day_${day.tripDate}").performClick()
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag("trip_route_route-one"))
        compose.onNodeWithTag("trip_route_route-one").performClick()
        compose.onNodeWithText("路線詳情").assertExists()
        compose.onNodeWithText("路線示意（無街道底圖）").assertExists()
        compose.onNodeWithText("起點：約 25.000°, 121.000°").assertExists()
        compose.onNodeWithText("終點：約 25.100°, 121.100°").assertExists()
        compose.onNodeWithTag("trips_back").performClick()
        compose.onNodeWithText("當日騎乘").assertExists()
        compose.runOnIdle { assertEquals(0, back); assertEquals(1, reads) }
        compose.onNodeWithTag("trips_back").performClick()
        compose.onNodeWithText("騎乘紀錄").assertExists()
        compose.runOnIdle { assertEquals(1, back); assertEquals(1, reads) }
    }

    @Test fun routeDestinationSurvivesHostRestorationWithoutReadingAndBackReturnsToDate() {
        val restoration = StateRestorationTester(compose)
        val parent = TripState(days = listOf(day), hasLoaded = true)
        // Models configuration recreation with the existing ViewModel retaining its day data.
        val state = mutableStateOf(parent.copy(selectedDay = day,
            details = ListTripDetailsResponse.newBuilder().addDailyTripDetails(trip).build()))
        var reads = 0; var parentBack = 0
        restoration.setContent { MaterialTheme {
            TripHistoryContent(state.value, true, { parentBack++; state.value = parent },
                { reads++ }, { reads++ }, { reads++ }, { reads++ }, {})
        } }
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag("trip_route_route-one"))
        compose.onNodeWithTag("trip_route_route-one").performClick()
        compose.onNodeWithText("路線詳情").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("路線詳情").assertExists()
        compose.onNodeWithText("路線示意（無街道底圖）").assertExists()
        compose.onNodeWithText("起點：約 25.000°, 121.000°").assertExists()
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, parentBack) }
        compose.onNodeWithTag("trips_back").performClick()
        compose.onNodeWithText("當日騎乘").assertExists()
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, parentBack) }
        compose.onNodeWithTag("trips_back").performClick()
        compose.onNodeWithText("騎乘紀錄").assertExists()
        compose.runOnIdle { assertEquals(0, reads); assertEquals(1, parentBack) }
    }

    @Test fun invalidRouteShowsNoInventedCanvasOrEndpoints() {
        val invalid = trip.toBuilder().clearTripLocations().addTripLocations(TripLocation.getDefaultInstance()).build()
        compose.setContent { MaterialTheme {
            TripHistoryContent(TripState(days = listOf(day), selectedDay = day, hasLoaded = true,
                details = ListTripDetailsResponse.newBuilder().addDailyTripDetails(invalid).build()),
                true, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag("trip_route_route-one"))
        compose.onNodeWithTag("trip_route_route-one").performClick()
        compose.onNodeWithText("路線資料缺少或無效，無法繪製。").assertExists()
        compose.onNodeWithTag("trip_route_canvas").assertDoesNotExist()
    }
}
