package org.openridesync.app.trips

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.openridesync.honda.wire.HondaWire.DailyTrips
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Synthetic content callbacks only; no authenticated ViewModel, RPC, lifecycle or device acceptance. */
class TripJourneyScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun day(index: Int) = DailyTrips.newBuilder().setTripDate(1792022400L + index * 86400L)
        .setStartTime(1792026000L + index * 86400L).addTripUuids("synthetic-$index").build()

    @Test fun signedOutAccountIsExplicitAndDoesNotQuery() {
        var account = 0; var query = 0
        compose.setContent { MaterialTheme {
            TripHistoryContent(TripState(), false, {}, { query++ }, { query++ }, { query++ }, {}, {}, { account++ })
        } }
        compose.runOnIdle { assertEquals(0, account); assertEquals(0, query) }
        compose.onNodeWithTag("trips_account").performClick()
        compose.runOnIdle { assertEquals(1, account); assertEquals(0, query) }
    }
    @Test fun listAndSummaryAuthFailuresOpenAccountRatherThanRetry() {
        val state = mutableStateOf(TripState(errorCode = "AUTH_REQUIRED"))
        var account = 0; var retry = 0
        compose.setContent { MaterialTheme {
            TripHistoryContent(state.value, true, {}, {}, { retry++ }, {}, {}, {}, { account++ })
        } }
        compose.onNodeWithTag("trips_account").performClick()
        compose.runOnIdle { state.value = TripState(hasLoaded = true, summaryErrorCode = "UNAUTHENTICATED") }
        compose.onNodeWithTag("trips_summary_account").performClick()
        compose.runOnIdle { assertEquals(2, account); assertEquals(0, retry) }
    }
    @Test fun unreadAndCancelledAreNotSuccessfulEmptyAndStateChangesDoNotRead() {
        val state = mutableStateOf(TripState()); val signedIn = mutableStateOf(false)
        var reads = 0
        compose.setContent { MaterialTheme {
            TripHistoryContent(state.value, signedIn.value, {}, { reads++ }, { reads++ }, { reads++ }, {}, {})
        } }
        compose.runOnIdle { signedIn.value = true }
        compose.onNodeWithText("尚未讀取紀錄。按下讀取紀錄後才會查詢。").assertExists()
        compose.onNodeWithText("尚無騎乘紀錄。").assertDoesNotExist()
        compose.runOnIdle { state.value = TripState(cancelled = true) }
        compose.onNodeWithText("尚無騎乘紀錄。").assertDoesNotExist()
        compose.runOnIdle { state.value = TripState(hasLoaded = true) }
        compose.onNodeWithText("尚無騎乘紀錄。").assertExists()
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithText("更新").performClick()
        compose.runOnIdle { assertEquals(1, reads) }
    }
    @Test fun detailBackRestoresHistoryScrollWithoutRefreshCallback() {
        val days = (0..25).map(::day)
        val parent = TripState(days = days, hasLoaded = true)
        val state = mutableStateOf(parent)
        var reads = 0
        compose.setContent { MaterialTheme {
            TripHistoryContent(state.value, true, { state.value = restoreTripHistory(parent) },
                { reads++ }, { reads++ }, { reads++ }, { state.value = parent.copy(selectedDay = it) }, {})
        } }
        val target = "trip_day_${days[18].tripDate}"
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag(target))
        compose.onNodeWithTag(target).performClick()
        compose.onNodeWithText("當日騎乘").assertExists()
        compose.onNodeWithTag("trips_back").performClick()
        compose.onNodeWithTag(target).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reads) }
    }
    @Test fun failedTerminalPageRetryRemainsVisibleAfterDetail() {
        val parent = TripState(days = listOf(day(0)), hasLoaded = true, cached = true, hasMore = false, errorCode = "UNAVAILABLE")
        val state = mutableStateOf(parent); var retry = 0
        compose.setContent { MaterialTheme {
            TripHistoryContent(state.value, true, { state.value = restoreTripHistory(parent) }, {}, { retry++ }, {},
                { state.value = parent.copy(selectedDay = it, errorCode = null) }, {})
        } }
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag("trip_day_${day(0).tripDate}"))
        compose.onNodeWithTag("trip_day_${day(0).tripDate}").performClick()
        compose.onNodeWithTag("trips_back").performClick()
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag("trips_retry"))
        compose.onNodeWithTag("trips_retry").performClick()
        compose.runOnIdle { assertEquals(1, retry) }
    }
    @Test fun visibleDateUsesStartTimestampAcrossTimezoneBoundary() {
        val start = Instant.parse("2026-01-31T18:00:00Z").epochSecond
        val key = Instant.parse("2026-01-31T00:00:00Z").epochSecond
        val zone = ZoneId.of("Asia/Taipei")
        assertNotEquals(TripFormat.date(key, zone, Locale.US), TripFormat.date(start, zone, Locale.US))
        val value = DailyTrips.newBuilder().setTripDate(key).setStartTime(start).addTripUuids("synthetic-date").build()
        val originalZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Taipei"))
            compose.setContent { MaterialTheme {
                TripHistoryContent(TripState(selectedDay = value), true, {}, {}, {}, {}, {}, {})
            } }
            compose.onNodeWithText(TripFormat.date(start, includeYear = true)).assertExists()
            compose.onNodeWithText(TripFormat.date(key, includeYear = true)).assertDoesNotExist()
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }
    @Test fun tripAuthRecoveryReachesExplicitLoginEvenWhenAccountLooksHealthy() {
        val accountPage = mutableStateOf(false); var login = 0; var read = 0
        compose.setContent { MaterialTheme {
            if (!accountPage.value) TripHistoryContent(TripState(errorCode = "UNAUTHENTICATED"), true,
                {}, {}, { read++ }, {}, {}, {}, { accountPage.value = true })
            else org.openridesync.app.ui.AccountScreen(
                org.openridesync.app.auth.AuthState(org.openridesync.app.auth.AuthStatus.SIGNED_IN),
                org.openridesync.app.AccountState(), null, "synthetic-id", { login++ }, { read++ },
                {}, {}, {}, {}, {}, {}, showFeatureLinks = false)
        } }
        compose.onNodeWithTag("trips_account").performClick()
        compose.runOnIdle { assertEquals(0, login); assertEquals(0, read) }
        compose.onNodeWithTag("account_login").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, login); assertEquals(0, read) }
    }
}
