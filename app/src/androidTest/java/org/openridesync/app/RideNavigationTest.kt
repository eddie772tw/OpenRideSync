package org.openridesync.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.runtime.RideStage

/** Actual Activity routing, without a synthetic connection or Bluetooth operation. */
class RideNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun notificationIntentOpensRideAndRecreationPreservesTheChosenScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as OpenRideSyncApplication
        check(app.ride.state.value.stage == RideStage.IDLE)
        val open = Intent(context, MainActivity::class.java).setAction(MainActivity.OPEN_RIDE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // ActivityScenario adds CLEAR_TASK to its launch intent; never reuse that mutated object
        // for subsequent notification taps, which must deliver onNewIntent to this Activity.
        ActivityScenario.launch<MainActivity>(Intent(open)).use { activity ->
            check(open.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK == 0)
            compose.onNodeWithTag("ride_idle").assertExists()
            compose.onNodeWithTag("ride_navigation").performScrollTo().performClick()
            compose.onNodeWithTag("navigation_destination").assertExists()
            compose.onNodeWithTag("navigation_launch").performScrollTo().assertIsNotEnabled()
            check(app.ride.state.value.stage == RideStage.IDLE)
            compose.onNodeWithText("返回").performScrollTo().performClick()
            compose.onNodeWithTag("ride_idle").assertExists()
            compose.onNodeWithTag("ride_calls").performScrollTo().performClick()
            compose.onNodeWithTag("calls_list").assertExists()
            check(app.ride.state.value.stage == RideStage.IDLE)
            compose.onNodeWithTag("calls_back").performClick()
            compose.onNodeWithTag("ride_idle").assertExists()
            compose.onNodeWithTag("ride_diagnostics").performScrollTo().performClick()
            compose.onNodeWithTag("ride_idle").assertDoesNotExist()
            activity.recreate()
            compose.onNodeWithTag("ride_idle").assertDoesNotExist()
            activity.onActivity { it.startActivity(open) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("ride_idle").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ride_idle").assertExists()
            // Repeat the same notification tap after another navigation; not a one-shot route.
            compose.onNodeWithTag("ride_diagnostics").performScrollTo().performClick()
            activity.onActivity { it.startActivity(open) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("ride_idle").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ride_idle").assertExists()
            check(app.ride.state.value.stage == RideStage.IDLE)
        }
    }
}
