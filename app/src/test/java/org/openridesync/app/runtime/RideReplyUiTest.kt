package org.openridesync.app.runtime

import android.Manifest
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Shared production presentation with synthetic state/counters only. No send, microphone or BLE. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
class RideReplyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewShowsTheExactDraftAndOnlyAnExplicitActionRequestsSend() {
        val actions = mutableListOf<Gen1Input.Function>()
        val draft = "Synthetic reply, never delivered"
        compose.setContent { OpenRideSyncTheme {
            RideContent(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB), false, null,
                RidePageState(RidePageStage.MESSAGE_CONFIRMATION, replyText = draft),
                {}, {}, {}, {}, {}, {}, onAction = { actions += it })
        } }
        compose.onNodeWithTag("ride_reply_transcript").assertTextEquals(draft)
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("ride_action_SELECT").performScrollTo().performClick()
        assertEquals(listOf(Gen1Input.Function.SELECT), actions)
        compose.onNodeWithTag("ride_action_BACK").performScrollTo().assertTextContains("重新錄音")
    }

    @Test fun missingMicrophoneOffersRationaleWithoutRequestingOrGrantingAndModeLossDismissesIt() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        val state = mutableStateOf(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB))
        compose.setContent { OpenRideSyncTheme {
            RideContent(state.value, false, null,
                RidePageState(RidePageStage.MESSAGE_TRY_AGAIN, replyError = RideReplyAvailability.MICROPHONE_REQUIRED),
                {}, {}, {}, {}, {}, {})
        } }
        assertFalse(AppAccess.VOICE.granted(application))
        assertNull(shadowOf(application).nextStartedActivity)
        compose.onNodeWithTag("ride_reply_microphone_permission").performScrollTo().performClick()
        compose.onNodeWithText(AppAccess.VOICE.title).assertExists()
        assertNull(shadowOf(application).nextStartedActivity)
        assertFalse(AppAccess.VOICE.granted(application))
        assertFalse(AppAccess.WIFI_NAME.granted(application))
        compose.runOnIdle { state.value = state.value.copy(mode = Gen1Input.Function.MODE_HU) }
        compose.onNodeWithText(AppAccess.VOICE.title).assertDoesNotExist()
    }
}
