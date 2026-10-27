package org.openridesync.app.runtime

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ble.Gen1InputMonitor
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Production route lifetime plus real lock/recorder, with synthetic observations, no vehicle. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class HandlebarTestLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun huHidesTheToolButKeepsTheStartedRoundUntilBackgroundOrRealDeparture() {
        val owner = Owner()
        val route = mutableStateOf(true)
        val state = mutableStateOf(RideState(RideStage.CONNECTED, mode = Function.MODE_SAB))
        val recorder = HandlebarTest()
        val operation = UUID.randomUUID().toString()
        var sequence = 0L
        var stops = 0
        var backs = 0
        lateinit var back: OnBackPressedDispatcher
        fun start() {
            recorder.create(operation)
            assertTrue(recorder.start(HandlebarGesture.LEFT_LONG, sequence,
                HandlebarContext(state.value.mode, 1, RidePageStage.MENU)))
        }
        fun observe(function: Function) {
            sequence++
            recorder.accept(recorder.state.value.id!!, operation, Gen1InputMonitor.Observation(
                Gen1InputMonitor.Input(function, sequence, 1), Gen1InputMonitor.Mode(state.value.mode, 1),
                System.nanoTime()), RidePageStage.MENU)
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED; start() }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            HandlebarTestLifetime(route.value) { stops++; recorder.close(HandlebarEnd.SCREEN_LEFT, sequence) }
            OpenRideSyncTheme {
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                BackHandler { backs++ }
                RidePhoneContent(state.value) {
                    if (route.value) Text("Observation tool", Modifier.testTag("observation_tool"))
                    else Text("Other route")
                }
            }
        } }
        compose.onNodeWithTag("observation_tool").assertExists()
        compose.runOnIdle { state.value = state.value.copy(mode = Function.MODE_HU); observe(Function.MODE_HU) }
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("observation_tool").assertDoesNotExist()
        compose.runOnIdle {
            back.onBackPressed()
            assertEquals(0, backs)
            assertEquals(0, stops)
            assertNotNull(recorder.state.value.activeRound)
            HandlebarTest.FUNCTIONS.forEach(::observe)
            state.value = state.value.copy(mode = Function.MODE_SAB)
            observe(Function.MODE_SAB)
        }
        compose.onNodeWithTag("observation_tool").assertExists()
        compose.runOnIdle {
            assertEquals(0, stops)
            assertEquals(1L, recorder.state.value.count(Function.MODE_HU))
            assertEquals(1L, recorder.state.value.count(Function.MODE_SAB))
            assertTrue(HandlebarTest.FUNCTIONS.all { recorder.state.value.count(it) == 1L })
            assertEquals(0L, recorder.state.value.rounds.single().lostEvents)
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(HandlebarEnd.SCREEN_LEFT, recorder.state.value.closed)
            val events = recorder.state.value.rounds.single().events
            observe(Function.NEXT)
            assertEquals(events, recorder.state.value.rounds.single().events)
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertNull(recorder.state.value.activeRound) // Foreground never restarts observation.
            start()
            route.value = false
        }
        compose.onNodeWithText("Other route").assertExists()
        compose.runOnIdle {
            assertEquals(2, stops)
            assertEquals(HandlebarEnd.SCREEN_LEFT, recorder.state.value.closed)
            assertNull(recorder.state.value.activeRound)
        }
    }

    @Test fun recompositionUsesTheLatestStopCallbackAndDisposalRemovesTheOldObserver() {
        val owner = Owner()
        val route = mutableStateOf(true)
        val version = mutableStateOf(0)
        val calls = mutableListOf<Int>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            val current = version.value
            HandlebarTestLifetime(route.value) { calls += current }
        } }
        compose.runOnIdle { version.value = 1 }
        compose.runOnIdle {
            assertTrue(calls.isEmpty())
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(listOf(1), calls)
            owner.registry.currentState = Lifecycle.State.RESUMED
            route.value = false
        }
        compose.runOnIdle {
            assertEquals(listOf(1, 1), calls)
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(listOf(1, 1), calls)
        }
    }
}
