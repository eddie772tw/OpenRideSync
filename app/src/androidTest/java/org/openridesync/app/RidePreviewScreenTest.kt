package org.openridesync.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.runtime.RidePreviewScreen
import org.openridesync.app.ui.theme.OpenRideSyncTheme

/** Synthetic render/callback checks only. No real media callback, vehicle service or radio action. */
class RidePreviewScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownModeRequestIsSyntheticAndHuStillWaitsForAnExplicitSabFixture() {
        assumeTrue(BuildConfig.DEBUG)
        var external = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { external++ }, { external++ }) } }
        compose.onNodeWithTag("preview_controls_panel").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_request_sab").assertDoesNotExist()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_mode_未知").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_request_sab").performScrollTo().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("ride_display_state").assertExists()
        compose.onNodeWithTag("ride_phone_mode_request_state").performScrollTo()
            .assertTextEquals("車輛已接受要求，等待 RoadSync 模式回報…")
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("ride_phone_request_sab").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_phone_request_sab").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
        compose.onNodeWithTag("ride_display_state").assertExists()
        compose.runOnIdle { assertEquals(0, external) }
    }

    @Test fun incomingMessagePreviewIsSyntheticAndLeavesNoLabelOutsideSab() {
        assumeTrue(BuildConfig.DEBUG)
        var external = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { external++ }, { external++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_page_MESSAGE_PROMPT").performScrollTo().performClick()
        compose.onNodeWithTag("ride_message_label").performScrollTo().assertTextEquals("合成訊息標籤（非真實通知）")
        compose.onNodeWithTag("preview_page_MESSAGE_READ").performScrollTo().performClick()
        compose.onNodeWithTag("preview_message_FAILED").performScrollTo().performClick()
        compose.onNodeWithText("訊息朗讀已中止或失敗，不會自行重播正文；可返回後手動瀏覽。").performScrollTo().assertExists()
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_message_label").assertDoesNotExist()
        compose.onNodeWithTag("preview_page_MESSAGE_PROMPT").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, external) }
    }

    @Test fun huIncomingPopupPreviewIsSyntheticAndClearsOnUnknownMode() {
        assumeTrue(BuildConfig.DEBUG)
        var external = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { external++ }, { external++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_hu_call").performScrollTo().performClick()
        compose.onNodeWithTag("preview_hu_call_popup").performScrollTo().assertExists()
        compose.onNodeWithText("確認：接聽（示意）").assertDoesNotExist()
        compose.onNodeWithText("返回：拒接（示意）").assertDoesNotExist()
        compose.onNodeWithText("儀表模式不將手把按鍵交給 App 處理。").assertExists()
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithTag("preview_mode_未知").performScrollTo().performClick()
        compose.onNodeWithTag("preview_hu_call_popup").assertDoesNotExist()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, external) }
    }

    @Test fun syntheticCallsNeverDispatchOrStartExternalFeatures() {
        assumeTrue(BuildConfig.DEBUG)
        var external = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { external++ }, { external++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_page_CALL").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_caller").performScrollTo().assertTextEquals("合成通話對象（非真實來電）")
        compose.onNodeWithTag("preview_page_CALL_CANCEL").performScrollTo().performClick()
        compose.onNodeWithTag("preview_call_DISPATCHED").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_result").performScrollTo().assertTextContains("請以來源 App 與後續通知為準", substring = true)
        compose.onNodeWithTag("preview_mode_未知").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithTag("ride_call_result").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, external) }
    }

    @Test fun syntheticNavigationConfirmationNeverStartsExternalTest() {
        assumeTrue(BuildConfig.DEBUG)
        var starts = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { starts++ }, { starts++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_page_NAVIGATION").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_instruction").performScrollTo()
            .assertTextEquals("合成導航指示：前方右轉")
        compose.onNodeWithTag("preview_page_NAVIGATION_CANCEL").performScrollTo().performClick()
        compose.onNodeWithTag("preview_navigation_result_DISPATCHED").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_cancel_result").performScrollTo()
            .assertTextContains("尚未確認路線已停止", substring = true)
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_navigation_instruction").assertDoesNotExist()
        compose.onNodeWithTag("preview_page_NAVIGATION").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun localFixturesSwitchWithoutAutomaticallyStartingMedia() {
        assumeTrue(BuildConfig.DEBUG)
        var starts = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { starts++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("ride_preview_banner").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("preview_stage_CONNECTING").performScrollTo().performClick()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("preview_stage_FAILED").performScrollTo().performClick()
        compose.onNodeWithTag("ride_connecting_progress").assertDoesNotExist()
        compose.onNodeWithTag("preview_stage_CONNECTED").performScrollTo().performClick()
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("preview_page_WEATHER").assertIsNotEnabled()
        compose.onNodeWithTag("preview_mode_未知").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_MUSIC").performScrollTo().performClick()
        compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText("音樂")))
        compose.onNodeWithTag("preview_page_VOLUME").performScrollTo().performClick()
        compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText("音量")))
        compose.onNodeWithTag("preview_page_MENU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText("主選單")))
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_WEATHER").performScrollTo().performClick()
        compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText("天氣匯報")))
        compose.onNodeWithTag("preview_page_MESSAGE").performScrollTo().performClick()
        for ((status, label) in listOf("NONE" to "訊息通知：上下選擇，確認播報，返回離開。",
            "READING" to "正在播報選定的通知預覽；返回可停止。", "COMPLETE" to "按確認選擇回覆方式，或上下切換訊息。",
            "FAILED" to "播報未完成，請檢查音訊、勿擾與通知存取，再自行決定是否重試。")) {
            compose.onNodeWithTag("preview_message_$status").performScrollTo().performClick()
            compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText(label)))
            if (status == "COMPLETE") compose.onNodeWithTag("ride_action_SELECT").assertTextContains("回覆…")
        }
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("preview_page_MESSAGE").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_navigation").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("navigation_preview_projection").assertExists()
        compose.onNodeWithTag("navigation_preview_choose").performScrollTo().performClick()
        compose.onNodeWithTag("navigation_preview_LEFT").performScrollTo().performClick()
        compose.onNodeWithTag("navigation_preview_projection").assert(hasAnyDescendant(hasText("圖示編號：53；色彩編號：5／6")))
        compose.onNodeWithTag("navigation_preview_missing").performScrollTo().performClick()
        compose.onNodeWithTag("navigation_preview_projection").assertDoesNotExist()
        compose.onNodeWithTag("navigation_preview_unavailable").assertExists()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("navigation_preview_back").performScrollTo().performClick()
        compose.onNodeWithTag("ride_display_state").assertExists()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_start_media_test").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("ride_disconnect").performScrollTo().performClick()
        compose.onNodeWithTag("ride_idle").assertExists()
        compose.onNodeWithTag("ride_preview_banner").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
