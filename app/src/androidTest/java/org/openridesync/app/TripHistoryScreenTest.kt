package org.openridesync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.trips.TripHistoryContent
import org.openridesync.app.trips.TripState
import org.openridesync.honda.wire.HondaWire.*

/** Static synthetic content only: no authentication, Honda RPC, cache or device data. */
class TripHistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedDaysOpenAndMoreIsExplicit() {
        val day = DailyTrips.newBuilder().setTripDate(1792022400).setStartTime(1792026000)
            .setEndTime(1792029600).addTripUuids("synthetic-trip").setTotalDistance(12000f).setTotalTime(3600).setAvgSpeed(10f).build()
        var opened: DailyTrips? = null
        var more = 0
        compose.setContent { MaterialTheme {
            TripHistoryContent(TripState(days = listOf(day), cached = true, hasMore = true), true,
                {}, {}, {}, { more++ }, { opened = it }, {})
        } }
        compose.onNodeWithText("目前顯示本機快取，更新後才代表最新雲端資料。").assertExists()
        compose.onNodeWithTag("trip_day_${day.tripDate}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(day, opened); assertEquals(0, more) }
        compose.onNodeWithTag("trips_more").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, more) }
    }

    @Test fun detailFailureKeepsBackAndRetryReachable() {
        var back = 0
        var retries = 0
        compose.setContent { MaterialTheme {
            TripHistoryContent(TripState(selectedDay = DailyTrips.newBuilder().setTripDate(1792022400).build(),
                errorCode = "UNAVAILABLE"), true, { back++ }, {}, { retries++ }, {}, {}, {})
        } }
        compose.onNodeWithTag("trips_retry").performScrollTo().performClick()
        compose.onNodeWithTag("trips_back").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, back) }
        compose.onNodeWithText("當日總覽").assertDoesNotExist()
    }

    @Test fun cancelledFirstReadDoesNotClaimAnEmptyCloudHistory() {
        compose.setContent { MaterialTheme {
            TripHistoryContent(TripState(cancelled = true), true, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("已取消讀取，尚未完成此次更新。").assertExists()
        compose.onNodeWithText("尚無騎乘紀錄。").assertDoesNotExist()
    }
}
