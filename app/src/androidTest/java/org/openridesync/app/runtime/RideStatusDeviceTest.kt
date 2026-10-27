package org.openridesync.app.runtime

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ui.theme.OpenRideSyncTheme

@RunWith(AndroidJUnit4::class)
class RideStatusDeviceTest {
    @get:Rule val compose = createComposeRule()

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

    /** Real Android sample and native TTS callback; no BLE, microphone, provider message or vehicle mode. */
    @Test fun realBatteryObservationCompletesNativeSpeech() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owned = java.util.concurrent.atomic.AtomicBoolean(true)
        val report = RideStatusReport(context, null, "status-device-test", { owned.get() })
        try {
            withTimeout(130_000) { report.report(RideStatusKind.BATTERY) }
            val observed = report.state.value
            File(context.filesDir, "status-report-device-result.json").writeText(JSONObject()
                .put("nativeSpeechStage", observed.stage.name).put("nativeSpeechError", observed.error ?: "NONE")
                .put("realAndroidSample", true).put("ttsCompletionIsAudibility", false)
                .put("realVehicle", false).put("microphoneUsed", false).toString())
            assertEquals("Native speech returned ${observed.error}", RideStatusReportStage.COMPLETE, observed.stage)
        } finally {
            owned.set(false)
            withContext(Dispatchers.Main.immediate) { report.close() }
        }
    }

}
