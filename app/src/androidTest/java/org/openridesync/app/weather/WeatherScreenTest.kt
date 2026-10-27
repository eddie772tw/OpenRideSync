package org.openridesync.app.weather

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic content only. Does not exercise Android location, permission dialogs or Honda. */
class WeatherScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun readyEntryWaitsForExactlyOneQueryClick() {
        var queries = 0
        compose.setContent { MaterialTheme {
            WeatherContent(WeatherPageState(signedIn = true, observing = true, locationGranted = true),
                {}, { queries++ }, {}, {}, {}, {})
        } }
        compose.runOnIdle { assertEquals(0, queries) }
        scrollTo("weather-empty").assertExists()
        scrollTo("weather-refresh").performClick()
        compose.runOnIdle { assertEquals(1, queries) }
    }

    @Test fun missingPermissionHasExplanationAndDoesNotQuery() {
        var queries = 0
        var permissions = 0
        compose.setContent { MaterialTheme {
            WeatherContent(WeatherPageState(signedIn = true, observing = true),
                {}, { queries++ }, {}, { permissions++ }, {}, {})
        } }
        scrollTo("weather-refresh").assertIsNotEnabled()
        scrollTo("weather-permission").performClick()
        compose.runOnIdle { assertEquals(1, permissions); assertEquals(0, queries) }
    }

    @Test fun signedOutOffersAccountInsteadOfQuery() {
        var accounts = 0
        compose.setContent { MaterialTheme {
            WeatherContent(WeatherPageState(), {}, {}, {}, {}, {}, {}, { accounts++ })
        } }
        compose.onNodeWithTag("weather-refresh").assertDoesNotExist()
        compose.onNodeWithTag("weather-account").performClick()
        compose.runOnIdle { assertEquals(1, accounts) }
    }

    @Test fun loadingProvidesCancelAndNoRepeatQueryButton() {
        var cancels = 0
        compose.setContent { MaterialTheme {
            WeatherContent(WeatherPageState(signedIn = true, observing = true, locationGranted = true, loading = true),
                {}, {}, { cancels++ }, {}, {}, {})
        } }
        compose.onNodeWithTag("weather-refresh").assertDoesNotExist()
        scrollTo("weather-cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun cancellationRemainsVisibleUntilExplicitRetry() {
        var queries = 0
        compose.setContent { MaterialTheme {
            WeatherContent(WeatherPageState(signedIn = true, observing = true, locationGranted = true,
                errorCode = "CANCELLED"), {}, { queries++ }, {}, {}, {}, {})
        } }
        scrollTo("weather-error").assertTextEquals("讀取已取消。")
        compose.runOnIdle { assertEquals(0, queries) }
        scrollTo("weather-refresh").performClick()
        compose.runOnIdle { assertEquals(1, queries) }
    }

    @Test fun forecastAuthFailureAlsoOffersAccountRecovery() {
        var accounts = 0
        compose.setContent { MaterialTheme {
            WeatherContent(WeatherPageState(signedIn = true, observing = true, locationGranted = true,
                forecastErrorCode = "AUTH_REQUIRED"), {}, {}, {}, {}, {}, {}, { accounts++ })
        } }
        scrollTo("weather-forecast-account").performClick()
        compose.runOnIdle { assertEquals(1, accounts) }
    }

    @Test fun expiredSessionProvidesDirectRecovery() {
        var accounts = 0
        compose.setContent { MaterialTheme {
            WeatherContent(WeatherPageState(signedIn = true, observing = true, locationGranted = true,
                errorCode = "UNAUTHENTICATED"), {}, {}, {}, {}, {}, {}, { accounts++ })
        } }
        scrollTo("weather-account").performClick()
        compose.runOnIdle { assertEquals(1, accounts) }
    }
}
