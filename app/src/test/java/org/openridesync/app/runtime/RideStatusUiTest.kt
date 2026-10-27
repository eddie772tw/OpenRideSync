package org.openridesync.app.runtime

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideStatusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun observedDrivingErrorIsVisibleAndUnknownDoesNotInventAnError() {
        val state = mutableStateOf(RideState(RideStage.CONNECTED, mode = Function.MODE_SAB))
        compose.setContent { OpenRideSyncTheme { RideStatusCard(state.value, false, {}, {}, compact = true) } }
        compose.onNodeWithTag("ride_driving_error").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(drivingError = true) }
        compose.onNodeWithTag("ride_driving_error").assertTextContains("車輛資料回報異常", substring = true)
        compose.runOnIdle { state.value = state.value.copy(drivingError = false) }
        compose.onNodeWithTag("ride_driving_error").assertDoesNotExist()
    }

    @Test fun menuControlsDescribeSelectionAndDoNothingUntilClicked() {
        val actions = mutableListOf<Function>()
        compose.setContent { OpenRideSyncTheme {
            RideContent(RideState(RideStage.CONNECTED, mode = Function.MODE_SAB), false, null,
                RidePageState(RidePageStage.STATUS_MENU, statusChoice = 3, statusCanReport = true),
                {}, {}, {}, {}, {}, {}, onAction = { actions += it })
        } }
        compose.onNodeWithTag("ride_status_choice_3").performScrollTo().assertTextEquals("› Wi-Fi")
        compose.onNodeWithTag("ride_action_NEXT").performScrollTo().assertIsNotEnabled()
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("ride_action_SELECT").performScrollTo().performClick()
        assertEquals(listOf(Function.SELECT), actions)
        compose.onNodeWithTag("ride_action_BACK").assertTextContains("返回")
    }

    @Test fun syntheticReportHighlightsMatchingTileAndReturnsWithoutExternalActions() {
        var external = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({ external++ }, { external++ }, { external++ }) } }
        compose.onNodeWithTag("ride_status_weather").performClick()
        compose.onNodeWithTag("ride_status_weather").assertIsSelected()
        compose.onNodeWithTag("ride_status_choice_0").performScrollTo().assertTextEquals("› 天氣預報")
        compose.onNodeWithTag("ride_action_NEXT").performScrollTo().performClick()
        compose.onNodeWithTag("ride_status_battery").assertIsSelected()
        compose.onNodeWithTag("ride_status_weather").assertIsNotSelected()
        compose.onNodeWithTag("ride_action_SELECT").performScrollTo().performClick()
        compose.onNodeWithTag("ride_status_report_detail").performScrollTo().assertTextContains("合成資料：手機電量，百分之 76", substring = true)
        compose.onNodeWithTag("ride_action_SELECT").assertDoesNotExist()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_status_complete").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_status_choice_1").performScrollTo().assertTextEquals("› 手機電量")
        compose.onNodeWithTag("ride_status_report_detail").assertDoesNotExist()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_status_choice_1").assertDoesNotExist()
        assertEquals(0, external)
    }

    @Test fun unavailableReportOffersGuidanceWithoutRequestingOrDispatching() {
        var requests = 0
        val actions = mutableListOf<Function>()
        compose.setContent { OpenRideSyncTheme {
            RideContent(RideState(RideStage.CONNECTED, mode = Function.MODE_SAB), false, null,
                RidePageState(RidePageStage.STATUS_MENU), {}, {}, {}, {}, {}, {},
                onPermissions = { requests++ }, onAction = { actions += it })
        } }
        compose.onNodeWithTag("ride_status_unavailable").performScrollTo().assertTextContains("未授權或觀察中斷", substring = true)
        compose.onNodeWithTag("ride_action_SELECT").performScrollTo().assertIsNotEnabled()
        assertEquals(0, requests)
        compose.onNodeWithTag("ride_status_permissions").performScrollTo().performClick()
        assertEquals(1, requests)
        assertTrue(actions.isEmpty())
    }

    @Test @Config(qualifiers = "zh-rTW-w240dp-h480dp-mdpi")
    fun selectedWrappedStatusTileScrollsIntoTheShortViewport() {
        val selected = mutableStateOf(0)
        compose.setContent { OpenRideSyncTheme {
            RideStatusLayout(RideDeviceStatus(76), statusSelected = selected.value) { }
        } }
        compose.runOnIdle { selected.value = 3 }
        compose.onNodeWithTag("ride_status_wifi").assertIsSelected().assertIsDisplayed()
        val tile = compose.onNodeWithTag("ride_status_wifi").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("ride_status_viewport").getUnclippedBoundsInRoot()
        assertTrue(tile.top >= viewport.top && tile.bottom <= viewport.bottom)
    }
}
