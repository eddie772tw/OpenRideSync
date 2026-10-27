package org.openridesync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.pairing.PairingContent
import org.openridesync.app.pairing.PairingStage
import org.openridesync.app.pairing.PairingState
import org.openridesync.app.pairing.PairingLifecycle
import org.openridesync.app.pairing.PairingEntry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Synthetic state rendering only; does not request permissions, scan, bond or save real data. */
class PairingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelEntryStartsExplicitlyWithoutWaitingForAWebGuide() {
        var starts = 0
        val generation = mutableStateOf(1)
        compose.setContent { MaterialTheme { PairingEntry(generation.value, true) { starts++ } } }
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("vehicle_start_pairing").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts); generation.value = 3 }
        compose.onNodeWithTag("vehicle_start_pairing").assertIsNotEnabled()
        compose.onNodeWithText("此車型的儀表世代尚未支援配對連線。").assertExists()
    }

    @Test fun actualLifecycleStopsAndRestartsOnceWithoutRecompositionRestart() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var starts = 0
        var stops = 0
        val replacement = mutableStateOf(false)
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val current = replacement.value
                PairingLifecycle(onStart = { starts += if (current) 10 else 1 }, onStop = { stops++ })
            }
        }
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(1, starts)
            replacement.value = true
        }
        compose.runOnIdle {
            assertEquals(1, starts) // A recomposition must not start a second scan.
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(0, stops) // The permission/picker pause alone is not page stop.
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(1, stops)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertEquals(11, starts) // Exactly one restart, using the latest callback.
        }
    }

    @Test fun advertisementRequiresExplicitDeviceSelection() {
        val state = mutableStateOf(PairingState(PairingStage.CHECKING, "synthetic-attempt"))
        var choices = 0
        compose.setContent { MaterialTheme {
            PairingContent(state.value, {}, {}, {}, { choices++ }, {}, {})
        } }
        compose.onNodeWithTag("pairing_choose").assertDoesNotExist()
        compose.onNodeWithTag("pairing_not_ready").assertDoesNotExist()
        compose.onNodeWithText("請在車輛端重設藍牙配對。正在搜尋處於配對模式的車輛…").assertExists()
        compose.onNodeWithText("返回車型資訊會停止本次搜尋。").assertExists()
        compose.runOnIdle { state.value = state.value.copy(stage = PairingStage.CHECKED) }
        compose.onNodeWithText("返回車型資訊會停止本次搜尋。").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, choices) }
        compose.onNodeWithTag("pairing_choose").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, choices) }
    }

    @Test fun failureOffersRetryWithoutSuccessAndPairedIsNotReady() {
        val state = mutableStateOf(PairingState(PairingStage.FAILED, problem = "BOND_FAILED"))
        var retries = 0
        var garage = 0
        compose.setContent { MaterialTheme {
            PairingContent(state.value, {}, {}, { retries++ }, {}, {}, {}, onDone = { garage++ })
        } }
        compose.onNodeWithTag("pairing_not_ready").assertDoesNotExist()
        compose.onNodeWithText("返回車型資訊會停止本次搜尋。").assertDoesNotExist()
        compose.onNodeWithTag("pairing_garage").assertDoesNotExist()
        compose.onNodeWithTag("pairing_retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = PairingState(PairingStage.PAIRED, vehicleName = "Synthetic model") }
        compose.onNodeWithTag("pairing_not_ready").performScrollTo().assertExists()
        compose.onNodeWithTag("pairing_choose").assertDoesNotExist()
        compose.onNodeWithTag("pairing_garage").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, garage) }
    }
}
