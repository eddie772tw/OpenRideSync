package org.openridesync.app.runtime

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ui.MainTabShell
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class RidePhoneContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun observedHuReplacesAllRoutesAndBackButSabRestoresTheSavedDestination() {
        val state = mutableStateOf(RideState(RideStage.CONNECTED, mode = Function.MODE_SAB))
        var routes by mutableStateOf(listOf("home", "settings"))
        var backs = 0
        var opens = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent { OpenRideSyncTheme {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { backs++ }
            RidePhoneContent(state.value, { opens++ }) {
                MainTabShell(routes, { routes = listOf(it) }) { Text("Saved settings", Modifier.testTag("saved_destination")) }
            }
        } }
        compose.onNodeWithTag("ride_phone_status").assertExists()
        compose.onNodeWithTag("saved_destination").assertExists()
        compose.runOnIdle { state.value = state.value.copy(mode = Function.MODE_HU) }
        compose.onNodeWithTag("ride_phone_locked").assertIsDisplayed()
        compose.onNodeWithTag("ride_phone_switch_hint").assertIsDisplayed()
        compose.onNodeWithTag("main_tab_shell").assertDoesNotExist()
        compose.onNodeWithTag("saved_destination").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_open").assertDoesNotExist()
        compose.runOnIdle { dispatcher.onBackPressed(); assertEquals(0, backs); assertEquals(0, opens)
            assertEquals(listOf("home", "settings"), routes); state.value = state.value.copy(mode = Function.MODE_SAB) }
        compose.onNodeWithTag("saved_destination").assertExists()
        compose.onNodeWithTag("main_tab_settings").assertIsSelected()
        compose.onNodeWithTag("ride_phone_open").performClick()
        compose.runOnIdle { assertEquals(1, opens); dispatcher.onBackPressed(); assertEquals(1, backs) }
    }

    @Test fun unknownAndNonConnectedStatesNeverInheritTheHuLock() {
        val state = mutableStateOf(RideState(RideStage.CONNECTED))
        compose.setContent { OpenRideSyncTheme { RidePhoneContent(state.value, {}, {}) { Text("Usable phone") } } }
        compose.onNodeWithText("已連線・等待車輛回報模式").assertExists()
        compose.onNodeWithTag("ride_phone_request_sab").assertExists()
        compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
        for (stage in listOf(RideStage.CONNECTING, RideStage.RECONNECTING, RideStage.STOPPING, RideStage.FAILED, RideStage.IDLE)) {
            compose.runOnIdle { state.value = RideState(stage, mode = Function.MODE_HU) }
            compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
            compose.onNodeWithTag("ride_phone_request_sab").assertDoesNotExist()
            compose.onNodeWithText("Usable phone").assertExists()
            if (stage == RideStage.RECONNECTING)
                compose.onNodeWithText("正在重新連接車輛").assertExists()
        }
    }

    @Test fun unknownRequestIsAvailableOnRideAndOtherRoutesWithoutLockingNavigation() {
        val state = mutableStateOf(RideState(RideStage.CONNECTED))
        var otherRoute by mutableStateOf(false)
        var requests = 0
        var opens = 0
        var backs = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent { OpenRideSyncTheme {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { backs++ }
            RidePhoneContent(state.value, onOpenRide = if (otherRoute) ({ opens++ }) else null,
                onRequestSab = {
                    requests++; state.value = state.value.copy(modeRequest = ModeRequestStage.WAITING_FOR_MODE)
                }) { Text("Phone content", Modifier.testTag("unknown_phone_content")) }
        } }
        compose.onNodeWithTag("ride_phone_open").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_request_sab").performScrollTo().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("unknown_phone_content").assertExists()
        compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, requests)
            dispatcher.onBackPressed(); assertEquals(1, backs)
            otherRoute = true
            state.value = state.value.copy(mode = Function.UNKNOWN, modeRequest = ModeRequestStage.TIMED_OUT)
        }
        compose.onNodeWithTag("ride_phone_open").performScrollTo().performClick()
        compose.onNodeWithTag("ride_phone_request_sab").performScrollTo().performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(2, requests); state.value = state.value.copy(mode = Function.MODE_HU) }
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("unknown_phone_content").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_request_sab").assertIsNotEnabled()
        compose.runOnIdle { dispatcher.onBackPressed(); assertEquals(1, backs); state.value = state.value.copy(mode = Function.MODE_SAB) }
        compose.onNodeWithTag("unknown_phone_content").assertExists()
        compose.onNodeWithTag("ride_phone_request_sab").assertDoesNotExist()
    }

    @Config(qualifiers = "w320dp-h320dp-mdpi")
    @Test fun unknownRequestScrollsAtLargeFontWithoutTakingTheWholePhoneBody() {
        var requests = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            OpenRideSyncTheme { Box(Modifier.requiredSize(320.dp, 320.dp)) {
                RidePhoneContent(RideState(RideStage.CONNECTED, name = "Synthetic vehicle"), onRequestSab = { requests++ }) {
                    Box(Modifier.fillMaxSize().testTag("unknown_phone_body")) { Text("Phone content") }
                }
            } }
        } }
        compose.onNodeWithTag("ride_phone_request_sab").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(compose.onNodeWithTag("unknown_phone_body").fetchSemanticsNode().boundsInRoot.height > 0f)
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun explicitModeRequestRemainsLockedThroughAckAndTimeoutUntilObservedSab() {
        val state = mutableStateOf(RideState(RideStage.CONNECTED, mode = Function.MODE_HU))
        var requests = 0
        compose.setContent { OpenRideSyncTheme {
            RidePhoneContent(state.value, onRequestSab = {
                requests++; state.value = state.value.copy(modeRequest = ModeRequestStage.WAITING_FOR_MODE)
            }) { Text("Restored phone") }
        } }
        compose.onNodeWithTag("ride_phone_request_sab").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithText("Restored phone").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests); state.value = state.value.copy(modeRequest = ModeRequestStage.TIMED_OUT) }
        compose.onNodeWithTag("ride_phone_request_sab").assertIsEnabled()
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.runOnIdle { state.value = state.value.copy(mode = Function.MODE_SAB) }
        compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
        compose.onNodeWithText("Restored phone").assertExists()
    }

    @Config(qualifiers = "w640dp-h360dp-mdpi")
    @Test fun lockHintRemainsReachableWithLargeTextAndShortLandscapeHeight() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            OpenRideSyncTheme { Box(Modifier.requiredSize(640.dp, 320.dp)) {
                RidePhoneContent(RideState(RideStage.CONNECTED, name = "Synthetic vehicle", mode = Function.MODE_HU)) {
                    error("Locked content must not be composed")
                }
            } }
        } }
        compose.onNodeWithTag("ride_phone_switch_hint").performScrollTo().assertIsDisplayed()
    }
}
