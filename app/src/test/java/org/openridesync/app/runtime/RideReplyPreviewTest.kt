package org.openridesync.app.runtime

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared production UI, synthetic state only: no recognizer, RemoteInput, TTS or ride owner. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
class RideReplyPreviewTest {
    @get:Rule val compose = createComposeRule()
    private var external = 0
    private fun render() {
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({ external++ }, { external++ }, { external++ }) } }
        compose.runOnIdle { assertEquals(0, external) }
    }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag == "preview_controls_toggle" || tag == "preview_controls_close") node.performClick()
        else node.performScrollTo().performClick()
    }
    private fun action(function: String) = click("ride_action_$function")
    private fun inject(tag: String) {
        click("preview_controls_toggle")
        click(tag)
        click("preview_controls_close")
    }
    private fun category() {
        click("ride_function_MESSAGE")
        action("SELECT") // Synthetic read completes first; it cannot reply or send on entry.
        action("SELECT")
        compose.onNodeWithTag("ride_current_function").assertTextEquals("選擇回覆方式")
        repeat(2) { compose.onNodeWithTag("ride_reply_choice_$it").assertExists() }
    }

    @Test fun voiceDraftRequiresExplicitSyntheticRecognitionAndSendAndCanBeRerecorded() {
        render(); category()
        action("SELECT")
        compose.onNodeWithTag("ride_reply_transcript").assertDoesNotExist()
        compose.onNodeWithTag("ride_action_SELECT").assertDoesNotExist()
        inject("preview_reply_recognize_success")
        compose.onNodeWithTag("ride_reply_transcript").assertTextEquals("這是合成辨識的回覆內容（不會送出）")
        action("BACK")
        compose.onNodeWithTag("ride_reply_transcript").assertDoesNotExist()
        inject("preview_reply_recognize_success")
        action("SELECT")
        compose.onNodeWithTag("ride_reply_transcript").assertDoesNotExist()
        compose.onNodeWithTag("ride_action_SELECT").assertDoesNotExist()
        inject("preview_reply_send_success")
        compose.onNodeWithTag("ride_action_SELECT").assertDoesNotExist()
        compose.onNodeWithTag("ride_reply_transcript").assertDoesNotExist()
        action("BACK")
        compose.onNodeWithTag("ride_current_function").assertTextEquals("訊息通知")
        compose.runOnIdle { assertEquals(0, external) }
    }

    @Test
    @Config(qualifiers = "zh-rTW-w430dp-h600dp-mdpi")
    fun quickListOnlySendsOnExplicitSelectionAndSyntheticFailureCannotResend() {
        render(); category()
        action("NEXT"); action("SELECT")
        compose.onNodeWithTag("ride_current_function").assertTextEquals("快速回覆")
        repeat(5) { compose.onNodeWithTag("ride_reply_choice_$it").assertExists() }
        compose.onNodeWithTag("ride_reply_choice_0").assertTextEquals("› 合成快速回覆 1（不會送出）")
        action("BACK")
        compose.onNodeWithTag("ride_reply_choice_1").assertTextEquals("› 快速回覆")
        action("SELECT")
        repeat(4) { action("NEXT") }
        compose.onNodeWithTag("ride_reply_choice_4").assertTextEquals("› 合成快速回覆 5（不會送出）")
            .assertIsSelected().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, external) }
        action("SELECT")
        compose.onNodeWithTag("ride_current_function").assertTextEquals("正在交付回覆")
        inject("preview_reply_send_failure")
        compose.onNodeWithTag("ride_action_SELECT").assertDoesNotExist()
        compose.onNodeWithTag("ride_reply_transcript").assertDoesNotExist()
        action("MENU")
        compose.onNodeWithTag("ride_function_menu").assertExists()
        compose.onNodeWithTag("ride_preview_banner").assertExists()
        compose.runOnIdle { assertEquals(0, external) }
    }

    @Test fun modeAndConnectionLossClearDraftAndKeepReplyFixturesSabOnly() {
        render()
        for (mode in listOf("HU", "未知")) {
            inject("preview_page_MESSAGE_CONFIRMATION")
            compose.onNodeWithTag("ride_reply_transcript").assertExists()
            click("preview_controls_toggle")
            click("preview_mode_$mode")
            compose.onNodeWithTag("preview_page_MESSAGE_CONFIRMATION").assertIsNotEnabled()
            compose.onNodeWithTag("ride_reply_transcript").assertDoesNotExist()
            click("preview_mode_SAB"); click("preview_controls_close")
            compose.onNodeWithTag("ride_function_menu").assertExists()
            compose.onNodeWithTag("ride_reply_transcript").assertDoesNotExist()
        }
        inject("preview_page_MESSAGE_CONFIRMATION")
        click("ride_disconnect")
        compose.onNodeWithTag("ride_reply_transcript").assertDoesNotExist()
        click("preview_controls_toggle")
        compose.onNodeWithTag("preview_page_MESSAGE_LISTENING").assertIsNotEnabled()
        click("preview_stage_CONNECTED"); click("preview_controls_close")
        compose.onNodeWithTag("ride_function_menu").assertExists()
        compose.runOnIdle { assertEquals(0, external) }
    }
}
