package org.openridesync.app.trips

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
class TripRecordingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renderingRecompositionAndScrollingDoNotOptInRecordOrUpload() {
        val state = mutableStateOf(TripRecordingState(signedIn = true, pending = 3))
        val calls = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            TripRecordingContent(state.value, { calls += "back" }, { calls += "permissions" }, { calls += "account" },
                { calls += "enabled:$it" }, { calls += "upload" }, { calls += "cancel" }, { calls += "retry" })
        } }
        compose.onNodeWithTag("trip_recording_enabled").assertIsOff()
        compose.onNodeWithTag("trip_recording_upload").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(pending = 4) }
        compose.onNodeWithTag("trip_recording_pending").performScrollTo().assertTextEquals("待上傳定位紀錄：4 筆")
        compose.onNodeWithTag("trip_recording_status").performScrollTo().assertTextContains("未收集新的旅程位置", substring = true)
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("trip_recording_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("trip_recording_back").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("permissions", "back"), calls) }
    }

    @Test fun signedOutStateCannotEnableRetryOrUploadAndHidesAccountQueue() {
        val calls = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            TripRecordingContent(TripRecordingState(enabled = true, pending = 99, ambiguous = true),
                {}, { calls += "permissions" }, { calls += "account" }, { calls += "enabled" },
                { calls += "upload" }, { calls += "cancel" }, { calls += "retry" })
        } }
        compose.onNodeWithTag("trip_recording_enabled").performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("trip_recording_retry").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("trip_recording_upload").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("trip_recording_pending").assertTextEquals("登入後可查看此帳號的待上傳資料。")
        compose.onNodeWithTag("trip_recording_ambiguous").assertDoesNotExist()
        compose.onNodeWithTag("trip_recording_account").performScrollTo().performClick()
        compose.onNodeWithTag("trip_recording_permissions").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("account", "permissions"), calls) }
    }

    @Test fun ambiguousUploadRequiresExplicitRetryAndBusyUploadOffersCancellation() {
        val state = mutableStateOf(TripRecordingState(signedIn = true, pending = 2, ambiguous = true))
        var uploads = 0
        var cancelled = 0
        compose.setContent { MaterialTheme {
            TripRecordingContent(state.value, {}, {}, {}, {}, { uploads++ }, { cancelled++ }, {})
        } }
        compose.onNodeWithTag("trip_recording_ambiguous").performScrollTo().assertExists()
        compose.onNodeWithText("上次上傳結果未確認").assertExists()
        compose.runOnIdle { assertEquals(0, uploads); assertEquals(0, cancelled) }
        compose.onNodeWithTag("trip_recording_upload").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, uploads); state.value = state.value.copy(uploading = true) }
        compose.onNodeWithTag("trip_recording_upload").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("trip_recording_cancel_upload").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(1, uploads) }
    }

    @Test fun explicitOptInStillDoesNotClaimRecordingAndOptOutPreservesPendingDisplay() {
        val state = mutableStateOf(TripRecordingState(signedIn = true, pending = 4))
        val enabled = mutableListOf<Boolean>()
        var retries = 0
        var uploads = 0
        compose.setContent { MaterialTheme {
            TripRecordingContent(state.value, {}, {}, {}, {
                enabled += it; state.value = state.value.copy(enabled = it)
            }, { uploads++ }, {}, { retries++ })
        } }
        compose.onNodeWithTag("trip_recording_enabled").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("trip_recording_status").performScrollTo().assertTextContains("尚未開始紀錄", substring = true)
        compose.runOnIdle { assertEquals(listOf(true), enabled); assertEquals(0, retries); assertEquals(0, uploads) }
        compose.onNodeWithTag("trip_recording_retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithTag("trip_recording_enabled").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("trip_recording_pending").performScrollTo().assertTextEquals("待上傳定位紀錄：4 筆")
        compose.runOnIdle { assertEquals(listOf(true, false), enabled); assertEquals(0, uploads) }
    }

    @Test fun unknownErrorsShowOnlyUserFacingExplanation() {
        compose.setContent { MaterialTheme {
            TripRecordingContent(TripRecordingState(signedIn = true, errorCode = "SYNTHETIC_INTERNAL_DETAIL"),
                {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("trip_recording_error").performScrollTo()
            .assertTextEquals("暫時無法完成操作，請確認連線或定位條件後明確重試。")
        compose.onNodeWithText("SYNTHETIC_INTERNAL_DETAIL", substring = true).assertDoesNotExist()
    }

    @Test fun emptyQueueMarkerOffersExplicitLocalRecoveryWithoutClaimingQueuedData() {
        var recovered = 0
        compose.setContent { MaterialTheme {
            TripRecordingContent(TripRecordingState(signedIn = true, pending = 0, ambiguous = true),
                {}, {}, {}, {}, { recovered++ }, {}, {})
        } }
        compose.onNodeWithTag("trip_recording_upload").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("檢查佇列並清除未確認標記").assertExists()
        compose.runOnIdle { assertEquals(0, recovered) }
        compose.onNodeWithTag("trip_recording_upload").performClick()
        compose.runOnIdle { assertEquals(1, recovered) }
    }

    @Test fun unavailableBackgroundScheduleKeepsManualUploadAvailableWithoutDispatchOnRender() {
        var uploads = 0
        compose.setContent { MaterialTheme {
            TripRecordingContent(TripRecordingState(signedIn = true, enabled = true, pending = 3,
                backgroundScheduleFailed = true), {}, {}, {}, {}, { uploads++ }, {}, {})
        } }
        compose.onNodeWithTag("trip_recording_schedule_failed").performScrollTo()
            .assertTextContains("已保存的資料仍保留", substring = true)
        compose.onNodeWithTag("trip_recording_upload").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, uploads) }
    }
}
