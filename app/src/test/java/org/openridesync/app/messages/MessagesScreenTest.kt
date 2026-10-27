package org.openridesync.app.messages

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessagesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val message = MessageItem("one", "合成訊息 App", "synthetic.invalid", "測試對話", "合成內容", 1, true)
    private fun ready(item: MessageItem = message) = MessagesPageState(signedIn = true, observing = true,
        inbox = MessageInboxState(MessageAccess.READY, listOf(item)))

    @Test fun approvedButDisconnectedListenerOffersRecoveryWithoutShowingMessages() {
        var permissions = 0
        compose.setContent { MaterialTheme {
            MessagesContent(ready(), {}, {}, {},
                { permissions++ }, { _, _ -> }, listenerDisconnected = true)
        } }
        compose.onNodeWithText("通知存取已授權，但通知服務尚未連線。訊息功能暫時不可用；請查看通知存取說明與連線恢復方式。")
            .assertExists()
        compose.onNodeWithTag("message_reply_one").assertDoesNotExist()
        compose.onNodeWithTag("messages_access").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, permissions) }
    }

    @Test fun accessExplanationRequiresExplicitActionAndSignedOutContentIsHidden() {
        val state = mutableStateOf(ready())
        var permissions = 0
        var account = 0
        var sends = 0
        compose.setContent { MaterialTheme {
            MessagesContent(state.value, {}, { account++ }, {}, { permissions++ }, { _, _ -> sends++ })
        } }
        compose.runOnIdle { state.value = ready().copy(inbox = MessageInboxState(MessageAccess.ACCESS_REQUIRED)) }
        compose.onNodeWithTag("message_reply_one").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, permissions); assertEquals(0, sends) }
        compose.onNodeWithTag("messages_access").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, permissions); state.value = ready().copy(signedIn = false) }
        compose.onNodeWithText("合成內容").assertDoesNotExist()
        compose.onNodeWithTag("messages_account").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, account); assertEquals(0, sends) }
    }

    @Test fun replyRequiresTextReviewAndExplicitSendAndNeverSendsOnCancel() {
        val sends = mutableListOf<Pair<String, String>>()
        compose.setContent { MaterialTheme { MessagesContent(ready(), {}, {}, {}, {}, { id, text -> sends += id to text }) } }
        compose.onNodeWithTag("message_reply_one").performScrollTo().performClick()
        compose.onNodeWithTag("message_reply_review").assertIsNotEnabled()
        compose.onNodeWithTag("message_reply_text").performTextInput("合成回覆")
        compose.onNodeWithTag("message_reply_review").performClick()
        compose.onNodeWithTag("message_reply_preview").assertTextEquals("合成回覆")
        compose.runOnIdle { assertTrue(sends.isEmpty()) }
        compose.onNodeWithTag("message_reply_cancel").performClick()
        compose.onNodeWithTag("message_reply_one").performScrollTo().performClick()
        compose.onNodeWithTag("message_reply_review").assertIsNotEnabled()
        compose.onNodeWithTag("message_reply_text").performTextInput("確認後傳送")
        compose.onNodeWithTag("message_reply_review").performClick()
        compose.onNodeWithTag("message_reply_send").performClick()
        compose.onNodeWithTag("message_reply_send").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("one" to "確認後傳送"), sends) }
    }

    @Test fun replacementAndStoppedObservationDiscardDraftAndConfirmation() {
        val state = mutableStateOf(ready())
        var sends = 0
        compose.setContent { MaterialTheme { MessagesContent(state.value, {}, {}, {}, {}, { _, _ -> sends++ }) } }
        compose.onNodeWithTag("message_reply_one").performScrollTo().performClick()
        compose.onNodeWithTag("message_reply_text").performTextInput("過期草稿")
        compose.onNodeWithTag("message_reply_review").performClick()
        compose.runOnIdle { state.value = ready(message.copy(id = "two", text = "更新通知")) }
        compose.onNodeWithTag("message_reply_send").assertDoesNotExist()
        compose.onNodeWithTag("message_reply_two").performScrollTo().performClick()
        compose.onNodeWithTag("message_reply_review").assertIsNotEnabled()
        compose.onNodeWithTag("message_reply_text").performTextInput("另一份草稿")
        compose.runOnIdle { state.value = state.value.copy(observing = false) }
        compose.onNodeWithTag("message_reply_text").assertDoesNotExist()
        compose.onNodeWithText("更新通知").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun actualBackAndLifecycleStopClearEditorEvenBeforeInboxObservationUpdates() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var sends = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            MaterialTheme { MessagesContent(ready(), {}, {}, {}, {}, { _, _ -> sends++ }) }
        } }
        compose.onNodeWithTag("message_reply_one").performScrollTo().performClick()
        compose.onNodeWithTag("message_reply_text").performTextInput("不可保留的草稿")
        compose.onNodeWithTag("message_reply_review").performClick()
        compose.onNodeWithTag("message_reply_preview").assertTextEquals("不可保留的草稿")
        compose.runOnIdle {
            assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("message_reply_send").assertDoesNotExist()
        compose.onNodeWithTag("message_reply_text").assertDoesNotExist()
        compose.onNodeWithTag("message_reply_one").performScrollTo().performClick()
        compose.onNodeWithTag("message_reply_review").assertIsNotEnabled()
        compose.onNodeWithTag("message_reply_text").performTextInput("背景前的草稿")
        compose.onNodeWithTag("message_reply_review").performClick()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithTag("message_reply_send").assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("message_reply_one").performScrollTo().performClick()
        compose.onNodeWithTag("message_reply_review").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, sends) }
    }
}
