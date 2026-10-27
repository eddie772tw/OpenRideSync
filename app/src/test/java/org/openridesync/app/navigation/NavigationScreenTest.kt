package org.openridesync.app.navigation

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
class NavigationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun ready() = NavigationPageState(signedIn = true, authRevision = 1,
        observation = NavigationObservation(NavigationStatus.OBSERVED,
            MapsInstruction("合成右轉指示", "200 m", null, null), 58, "one", true))

    @Test fun destinationRequiresConfirmationAndDismissNeverLaunches() {
        val launches = mutableListOf<String>()
        compose.setContent { MaterialTheme { NavigationContent(ready(), {}, {}, {}, {}, launches::add, {}, {}, {}) } }
        compose.onNodeWithTag("navigation_destination").performTextInput("合成目的地")
        compose.onNodeWithTag("navigation_launch").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(launches.isEmpty()) }
        compose.onNodeWithTag("navigation_dismiss").performClick()
        compose.onNodeWithTag("navigation_launch").performScrollTo().performClick()
        compose.onNodeWithTag("navigation_launch_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("合成目的地"), launches) }
    }

    @Test fun changedNotificationAndAccessLossInvalidateCancellation() {
        val state = mutableStateOf(ready())
        val canceled = mutableListOf<String>()
        compose.setContent { MaterialTheme { NavigationContent(state.value, {}, {}, {}, {}, {}, {}, canceled::add, {}) } }
        compose.onNodeWithTag("navigation_cancel").performScrollTo().performClick()
        compose.runOnIdle { state.value = ready().copy(observation = ready().observation.copy(sourceId = "two")) }
        compose.onNodeWithTag("navigation_cancel_confirm").assertDoesNotExist()
        compose.onNodeWithTag("navigation_cancel").performScrollTo().performClick()
        compose.runOnIdle { state.value = ready().copy(observation = NavigationObservation(NavigationStatus.ACCESS_REQUIRED)) }
        compose.onNodeWithTag("navigation_cancel_confirm").assertDoesNotExist()
        compose.onNodeWithTag("navigation_instruction").assertDoesNotExist()
        compose.onNodeWithTag("navigation_cancel").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(canceled.isEmpty()) }
    }

    @Test fun backgroundAndAccountRevisionClearDestinationAndConfirmation() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val state = mutableStateOf(ready())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            NavigationContent(state.value, {}, {}, {}, {}, { fail("No launch") }, {}, {}, {})
        } } }
        compose.onNodeWithTag("navigation_destination").performTextInput("私有目的地")
        compose.onNodeWithTag("navigation_launch").performScrollTo().performClick()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithTag("navigation_launch_confirm").assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("navigation_launch").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("navigation_destination").performScrollTo().performTextInput("另一個目的地")
        compose.onNodeWithTag("navigation_launch").performScrollTo().performClick()
        compose.runOnIdle { state.value = ready().copy(authRevision = 2) }
        compose.onNodeWithTag("navigation_launch_confirm").assertDoesNotExist()
        compose.onNodeWithTag("navigation_launch").performScrollTo().assertIsNotEnabled()
    }

    @Test fun dispatchResultDoesNotClaimRouteStartedOrEnded() {
        val value = ready().copy(observation = NavigationObservation(NavigationStatus.WAITING),
            launch = NavigationLaunchResult.DISPATCHED, cancel = NavigationCancelResult.DISPATCHED)
        compose.setContent { MaterialTheme { NavigationContent(value, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("已交給 Maps 處理；尚未確認路線是否開始。").performScrollTo().assertExists()
        compose.onNodeWithText("目前未觀察到 Maps 持續導航通知；這不代表路線已結束。").performScrollTo().assertExists()
        compose.onNodeWithTag("navigation_repeat").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("已送出結束要求，請確認 Maps 的實際狀態。").performScrollTo().assertExists()
    }
}
