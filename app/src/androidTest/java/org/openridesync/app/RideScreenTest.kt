package org.openridesync.app

import org.openridesync.app.ui.theme.OpenRideSyncTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.runtime.*

/** Synthetic UI transitions only; never starts a service, radio operation or vehicle write. */
class RideScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun incomingMessageStatesExposeOnlyLabelAndHonestInterruptedOrStorageStatus() {
        val page = mutableStateOf(RidePageState(RidePageStage.MESSAGE_PROMPT,
            messageLabel = "Synthetic label", messageCanRead = true))
        compose.setContent { OpenRideSyncTheme {
            RideContent(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB), false, null,
                page.value, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("ride_message_label").assertTextEquals("Synthetic label")
        compose.onNodeWithText("新訊息提示：確認讀取通知預覽，返回略過，主選單清除待處理佇列。").assertExists()
        compose.runOnIdle { page.value = page.value.copy(stage = RidePageStage.MESSAGE_READ,
            messageReadStatus = MessageReadStatus.FAILED, messageRecoveryAvailable = false) }
        compose.onNodeWithText("訊息朗讀已中止或失敗，不會自行重播正文；可返回後手動瀏覽。").assertExists()
        compose.onNodeWithTag("ride_message_recovery_unavailable").assertExists()
        compose.runOnIdle { page.value = RidePageState(RidePageStage.MENU) }
        compose.onNodeWithTag("ride_message_label").assertDoesNotExist()
        compose.onNodeWithTag("ride_message_recovery_unavailable").assertDoesNotExist()
    }

    @Test fun connectedControlsRequireObservedConnectionAndDisappearAfterDisconnect() {
        val state = mutableStateOf(RideState(RideStage.CONNECTING, name = "Synthetic vehicle"))
        val page = mutableStateOf(RidePageState(RidePageStage.MENU))
        var stops = 0
        var media = 0
        var retries = 0
        compose.setContent { OpenRideSyncTheme {
            RideContent(state.value, false, null, page.value, {}, {}, { media++ }, {}, { stops++ }, {},
                onRetryFavorite = { retries++; state.value = state.value.copy(favoriteStart = org.openridesync.app.media.FavoriteStartStatus.STARTING) })
        } }
        compose.onNodeWithTag("ride_media").assertDoesNotExist()
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_disconnect").performClick()
        compose.runOnIdle { assertEquals(1, stops); state.value = RideState(RideStage.CONNECTED, name = "Synthetic vehicle", mode = Gen1Input.Function.MODE_SAB, rssi = -65) }
        compose.onNodeWithTag("ride_details_toggle").performScrollTo().performClick()
        compose.onNodeWithText("車輛模式：RoadSync").assertExists()
        compose.onNodeWithText("藍牙訊號：-65 dBm").assertExists()
        compose.onNodeWithTag("ride_details_toggle").performClick()
        compose.onNodeWithText("主選單").assertExists()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            instrumentation.targetContext.cacheDir.resolve("test-ride-connected.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
        compose.runOnIdle { assertEquals(0, retries) }
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.runOnIdle { page.value = RidePageState(RidePageStage.MUSIC) }
        compose.onNodeWithTag("ride_retry_favorite").performScrollTo().performClick()
        compose.onNodeWithTag("ride_retry_favorite").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithTag("ride_media").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, media); state.value = RideState(RideStage.FAILED, errorCode = "TIMEOUT") }
        compose.onNodeWithTag("ride_media").assertDoesNotExist()
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_garage").performScrollTo().assertExists()
        compose.runOnIdle { state.value = RideState() }
        compose.onNodeWithTag("ride_idle").performScrollTo().assertExists()
    }

    @Test fun waitingForPermissionOffersCancellationWithoutClaimingConnection() {
        var cancelled = 0
        var diagnostics = 0
        compose.setContent { OpenRideSyncTheme {
            RideContent(RideState(), true, null, RidePageState(), {}, {}, {}, { diagnostics++ }, {}, { cancelled++ })
        } }
        compose.onNodeWithTag("ride_idle").assertDoesNotExist()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_permission_cancel").performClick()
        compose.onNodeWithTag("ride_diagnostics").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(1, diagnostics) }
    }
}
