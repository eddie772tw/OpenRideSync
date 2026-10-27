package org.openridesync.app.calls

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
class CallsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val call = CallItem("one", "合成通話 App", "synthetic.invalid", "測試來電者", CallKind.INCOMING,
        setOf(CallAction.ANSWER, CallAction.DECLINE), isVideo = true)
    private fun ready(item: CallItem = call) = CallsPageState(signedIn = true, observing = true,
        inbox = CallInboxState(CallAccess.READY, listOf(item)))

    @Test fun sourceActionRequiresConfirmationAndCancelNeverDispatches() {
        val sent = mutableListOf<Pair<String, CallAction>>()
        compose.setContent { MaterialTheme { CallsContent(ready(), {}, {}, {}, {}, { id, action -> sent += id to action }) } }
        compose.onNodeWithTag("call_one_ANSWER").performScrollTo().performClick()
        compose.onNodeWithTag("call_confirm").assertExists()
        compose.onNodeWithText("這是來源 App 回報的視訊通話。").assertExists()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("call_cancel").performClick()
        compose.onNodeWithTag("call_one_DECLINE").performScrollTo().performClick()
        compose.onNodeWithTag("call_confirm").performClick()
        compose.onNodeWithTag("call_confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("one" to CallAction.DECLINE), sent) }
    }

    @Test fun notificationReplacementAndLostAccessInvalidateConfirmation() {
        val state = mutableStateOf(ready())
        var sends = 0
        compose.setContent { MaterialTheme { CallsContent(state.value, {}, {}, {}, {}, { _, _ -> sends++ }) } }
        compose.onNodeWithTag("call_one_ANSWER").performScrollTo().performClick()
        compose.runOnIdle { state.value = ready(call.copy(id = "two")) }
        compose.onNodeWithTag("call_confirm").assertDoesNotExist()
        compose.onNodeWithTag("call_two_ANSWER").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(inbox = CallInboxState(CallAccess.ACCESS_REQUIRED)) }
        compose.onNodeWithTag("call_confirm").assertDoesNotExist()
        compose.onNodeWithText("測試來電者").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun disconnectedAndSignedOutStatesHideCallsWithoutRequestingPermissions() {
        val state = mutableStateOf(ready())
        var permission = 0
        var accounts = 0
        compose.setContent { MaterialTheme { CallsContent(state.value, {}, { accounts++ }, {},
            { permission++ }, { _, _ -> fail("Must not dispatch") }, listenerDisconnected = true) } }
        compose.onNodeWithText("測試來電者").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, permission) }
        compose.onNodeWithTag("calls_access").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, permission); state.value = ready().copy(signedIn = false) }
        compose.onNodeWithTag("calls_account").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, accounts) }
    }

    @Test fun lifecycleStopDiscardsConfirmationEvenBeforeObservationClears() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            MaterialTheme { CallsContent(ready(), {}, {}, {}, {}, { _, _ -> fail("Must not dispatch") }) }
        } }
        compose.onNodeWithTag("call_one_ANSWER").performScrollTo().performClick()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithTag("call_confirm").assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("call_confirm").assertDoesNotExist()
    }

    @Test fun unsupportedPlatformDoesNotOfferCallActionsOrImplyIdlePhone() {
        compose.setContent { MaterialTheme { CallsContent(ready(), {}, {}, {}, {},
            { _, _ -> fail("Must not dispatch") }, supported = false) } }
        compose.onNodeWithText("目前通話通知控制需要 Android 14 或更新版本。請使用來源 App 處理通話。").assertExists()
        compose.onNodeWithTag("call_one_ANSWER").assertDoesNotExist()
    }
}
