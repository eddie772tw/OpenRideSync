package org.openridesync.app.runtime

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.navigation.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rendering only: no notification listener, map launch, speech or vehicle operation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideNavigationContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun observed() = NavigationObservation(NavigationStatus.OBSERVED,
        MapsInstruction("合成右轉地址", "200 m", "合成目的地摘要", NavigationProjection.Maneuver.RIGHT),
        sourceId = "synthetic", canCancel = true)

    @Test fun manualPreviewUsesOnlySyntheticNavigationAndClearsItOnModeExitAndStop() {
        var external = 0
        compose.setContent { MaterialTheme { RidePreviewScreen({ external++ }, { external++ }, { external++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_page_NAVIGATION").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_instruction").performScrollTo()
            .assertTextEquals("合成導航指示：前方右轉")
        compose.onNodeWithTag("preview_navigation_LOADING").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithTag("preview_navigation_OBSERVED").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_NAVIGATION_CANCEL").performScrollTo().performClick()
        compose.onNodeWithTag("preview_navigation_result_DISPATCHED").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_cancel_result").performScrollTo()
            .assertTextEquals("已送出結束要求，請確認 Maps 的實際狀態；尚未確認路線已停止。")
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithTag("ride_navigation_cancel_result").assertDoesNotExist()
        compose.onNodeWithTag("preview_page_NAVIGATION").assertIsNotEnabled()
        compose.onNodeWithTag("preview_mode_未知").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_NAVIGATION").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_navigation").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_local_feedback").performScrollTo()
            .assertTextEquals("模擬：手機導航入口；此預覽不開啟 Maps 或真實導航。")
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("ride_disconnect").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithTag("ride_idle").assertExists()
        compose.runOnIdle { assertEquals(0, external) }
    }

    @Test fun idleAndNonSabNeverExposeRouteAndEntryRequiresExplicitClick() {
        val ride = mutableStateOf(RideState())
        var opened = 0
        var otherActions = 0
        compose.setContent { MaterialTheme {
            RideContent(ride.value, false, null, RidePageState(), { otherActions++ }, { otherActions++ },
                { otherActions++ }, { otherActions++ }, { otherActions++ }, { otherActions++ },
                observation = observed(), onNavigation = { opened++ })
        } }
        compose.onNodeWithTag("ride_navigation_state").assertDoesNotExist()
        compose.onNodeWithText("合成右轉地址").assertDoesNotExist()
        compose.onNodeWithTag("ride_navigation").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, opened); assertEquals(0, otherActions)
            ride.value = RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_HU)
        }
        compose.onNodeWithTag("ride_navigation_state").performScrollTo().assertExists()
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithText("Maps 行程摘要：合成目的地摘要").assertDoesNotExist()
        compose.runOnIdle { ride.value = ride.value.copy(mode = Gen1Input.Function.MODE_SAB) }
        compose.onNodeWithTag("ride_navigation_instruction").performScrollTo().assertTextEquals("合成右轉地址")
        compose.onNodeWithText("來源為手機 Maps 通知，不代表已在車端顯示。").assertExists()
        compose.runOnIdle { ride.value = ride.value.copy(mode = null) }
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithText("Maps 行程摘要：合成目的地摘要").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(0, otherActions) }
    }

    @Test fun loadingWaitingAndDisconnectedDiscardEvenAnOldInstruction() {
        val ride = mutableStateOf(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB))
        val observation = mutableStateOf(observed())
        compose.setContent { MaterialTheme {
            RideContent(ride.value, false, null, RidePageState(), {}, {}, {}, {}, {}, {}, observation = observation.value)
        } }
        compose.onNodeWithTag("ride_navigation_instruction").performScrollTo().assertExists()
        compose.runOnIdle { observation.value = observed().copy(status = NavigationStatus.LOADING) }
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithTag("ride_navigation_status").assertTextEquals("正在讀取 Maps 導航通知…")
        compose.runOnIdle { observation.value = observed().copy(status = NavigationStatus.WAITING) }
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithTag("ride_navigation_status").assertTextEquals("目前未觀察到 Maps 持續導航通知；這不代表路線已結束。")
        compose.runOnIdle { observation.value = observed(); ride.value = RideState(RideStage.STOPPING) }
        compose.onNodeWithTag("ride_navigation_state").assertDoesNotExist()
        compose.onNodeWithText("合成右轉地址").assertDoesNotExist()
    }

    @Test fun cancelDispatchIsOnlyARequestAndLeavingModeHidesItsResult() {
        val ride = mutableStateOf(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB))
        val page = mutableStateOf(RidePageState(RidePageStage.NAVIGATION_CANCEL,
            navigationCancelResult = NavigationCancelResult.DISPATCHED))
        compose.setContent { MaterialTheme {
            RideContent(ride.value, false, null, page.value, {}, {}, {}, {}, {}, {}, observation = observed())
        } }
        compose.onNodeWithTag("ride_navigation_cancel_result").performScrollTo()
            .assertTextEquals("已送出結束要求，請確認 Maps 的實際狀態；尚未確認路線已停止。")
        compose.runOnIdle { page.value = page.value.copy(navigationCancelResult = NavigationCancelResult.ACCESS_REQUIRED) }
        compose.onNodeWithTag("ride_navigation_cancel_result").assertTextEquals("需要通知存取權，尚未送出結束要求。")
        compose.runOnIdle { ride.value = ride.value.copy(mode = Gen1Input.Function.MODE_HU) }
        compose.onNodeWithTag("ride_navigation_cancel_result").assertDoesNotExist()
    }
}
