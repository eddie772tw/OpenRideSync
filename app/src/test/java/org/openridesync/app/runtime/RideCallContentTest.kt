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
import org.openridesync.app.calls.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideCallContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val incoming = CallItem("one", "合成來源", "synthetic.invalid", "合成來電者", CallKind.INCOMING,
        setOf(CallAction.ANSWER, CallAction.DECLINE), false)

    @Test fun uniqueReadySabCallerOnlyAndPhoneEntryRequiresClick() {
        val ride = mutableStateOf(RideState())
        val inbox = mutableStateOf(CallInboxState(CallAccess.READY, listOf(incoming)))
        var opens = 0
        compose.setContent { MaterialTheme {
            RideContent(ride.value, false, null, RidePageState(RidePageStage.CALL), {}, {}, {}, {}, {}, {},
                callInbox = inbox.value, onCalls = { opens++ })
        } }
        compose.onNodeWithText("合成來電者").assertDoesNotExist()
        compose.runOnIdle { ride.value = RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_HU) }
        compose.onNodeWithTag("ride_call_caller").performScrollTo().assertTextEquals("合成來電者")
        compose.runOnIdle { ride.value = ride.value.copy(mode = Gen1Input.Function.MODE_SAB) }
        compose.onNodeWithTag("ride_call_caller").performScrollTo().assertTextEquals("合成來電者")
        compose.onNodeWithText("實體確認鍵：請求來源 App 接聽。").assertExists()
        compose.runOnIdle { assertEquals(0, opens); inbox.value = inbox.value.copy(calls = listOf(incoming, incoming.copy(id = "two", caller = "另一合成對象"))) }
        compose.onNodeWithTag("ride_calls_multiple").performScrollTo().assertExists()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithText("另一合成對象").assertDoesNotExist()
        compose.runOnIdle { inbox.value = CallInboxState(CallAccess.ACCESS_REQUIRED, listOf(incoming)) }
        compose.onNodeWithText("合成來電者").assertDoesNotExist()
        compose.onNodeWithTag("ride_calls").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opens); ride.value = RideState(RideStage.STOPPING) }
        compose.onNodeWithTag("ride_calls_state").assertDoesNotExist()
    }

    @Test fun huOnlyShowsUniqueIncomingAndNeverReusesSabDispatchResult() {
        val ride = mutableStateOf(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_HU))
        val inbox = mutableStateOf(CallInboxState(CallAccess.READY, listOf(incoming)))
        compose.setContent { MaterialTheme {
            RideContent(ride.value, false, null, RidePageState(RidePageStage.CALL,
                callDispatchResult = CallDispatchResult.DISPATCHED), {}, {}, {}, {}, {}, {}, callInbox = inbox.value)
        } }
        compose.onNodeWithTag("ride_hu_call_hint").performScrollTo().assertTextEquals("HU 來電提示")
        compose.onNodeWithTag("ride_call_caller").assertTextEquals("合成來電者")
        compose.onNodeWithTag("ride_call_result").assertDoesNotExist()
        compose.runOnIdle { inbox.value = inbox.value.copy(calls = listOf(incoming.copy(kind = CallKind.ONGOING,
            actions = setOf(CallAction.HANG_UP)))) }
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithText("HU 僅提供唯一有效來電的提示，不提供進行中通話的掛斷或通話頁；沒有提示不代表通話已結束。").assertExists()
        compose.runOnIdle { inbox.value = CallInboxState(CallAccess.READY, listOf(incoming, incoming.copy(id = "other"))) }
        compose.onNodeWithTag("ride_calls_multiple").assertExists()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.runOnIdle { inbox.value = CallInboxState(CallAccess.READY, listOf(incoming)); ride.value = ride.value.copy(mode = null) }
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.runOnIdle { ride.value = RideState(RideStage.STOPPING) }
        compose.onNodeWithTag("ride_calls_state").assertDoesNotExist()
    }

    @Test fun huPreviewIsGraySyntheticAndUnknownOrStopRemovesCaller() {
        var external = 0
        compose.setContent { MaterialTheme { RidePreviewScreen({ external++ }, { external++ }, { external++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_hu_call").performScrollTo().performClick()
        compose.onNodeWithTag("preview_hu_call_popup").performScrollTo()
            .assert(hasAnyDescendant(hasText("灰色色彩編號 16；沒有讀取通知、模式要求或車端傳送。不是實際儀表像素驗證。")))
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithTag("ride_call_result").assertDoesNotExist()
        compose.onNodeWithTag("preview_mode_未知").performScrollTo().performClick()
        compose.onNodeWithTag("preview_hu_call_popup").assertDoesNotExist()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_CALL").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_caller").performScrollTo().assertExists()
        compose.onNodeWithTag("preview_hu_call_popup").assertDoesNotExist()
        compose.onNodeWithTag("preview_hu_call").performScrollTo().performClick()
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("ride_disconnect").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithTag("preview_hu_call_popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, external) }
    }

    @Test fun ongoingConfirmationAndDispatchedRemainRequestsNotObservedCompletion() {
        val page = mutableStateOf(RidePageState(RidePageStage.CALL_CANCEL))
        val inbox = mutableStateOf(CallInboxState(CallAccess.READY,
            listOf(incoming.copy(kind = CallKind.ONGOING, actions = setOf(CallAction.HANG_UP)))))
        compose.setContent { MaterialTheme {
            RideContent(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB), false, null,
                page.value, {}, {}, {}, {}, {}, {}, callInbox = inbox.value)
        } }
        compose.onNodeWithText("實體確認鍵：請求結束通話；實體返回鍵：取消這次確認。").performScrollTo().assertExists()
        compose.runOnIdle { page.value = page.value.copy(callDispatchResult = CallDispatchResult.DISPATCHED) }
        compose.onNodeWithTag("ride_call_result").performScrollTo().assertTextEquals(callResultText(CallDispatchResult.DISPATCHED))
        compose.runOnIdle { inbox.value = CallInboxState(CallAccess.READY) }
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithText("目前未觀察到支援的通話通知；這不代表通話已結束。").assertExists()
    }

    @Test fun previewCallControlsAreSyntheticAndModeExitAndStopClearCaller() {
        var external = 0
        compose.setContent { MaterialTheme { RidePreviewScreen({ external++ }, { external++ }, { external++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_page_CALL").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_caller").performScrollTo().assertTextEquals("合成通話對象（非真實來電）")
        compose.onNodeWithTag("preview_page_CALL_CANCEL").performScrollTo().performClick()
        compose.onNodeWithText("來源 App 回報通話進行中").performScrollTo().assertExists()
        compose.onNodeWithTag("preview_call_DISPATCHED").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_result").performScrollTo().assertTextEquals(callResultText(CallDispatchResult.DISPATCHED))
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.onNodeWithTag("ride_call_result").assertDoesNotExist()
        compose.onNodeWithTag("preview_page_CALL").assertIsNotEnabled()
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_CALL").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("ride_disconnect").performScrollTo().performClick()
        compose.onNodeWithTag("ride_call_caller").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, external) }
    }
}
