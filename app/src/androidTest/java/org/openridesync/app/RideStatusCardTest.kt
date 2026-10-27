package org.openridesync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.runtime.*
import org.openridesync.app.ble.RuntimePhase
import androidx.compose.runtime.mutableStateOf

/** Synthetic UI only: no service, permissions, account or Bluetooth is accessed. */
class RideStatusCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initializedConnectionDoesNotClaimVehicleReadinessAndCanStop() {
        var stops = 0
        compose.setContent { MaterialTheme {
            RideStatusCard(RideState(stage = RideStage.CONNECTED, name = "Synthetic vehicle"), false, { stops++ }, {})
        } }
        compose.onNodeWithText("車輛已連線").assertExists()
        compose.onNodeWithText("尚未收到車輛模式").assertExists()
        compose.onNodeWithText("車輛模式：RoadSync").assertDoesNotExist()
        compose.onNodeWithTag("ride_disconnect").performClick()
        assertEquals(1, stops)
    }

    @Test fun permissionRequestCanBeCancelledBeforeStartingConnection() {
        var cancels = 0
        compose.setContent { MaterialTheme {
            RideStatusCard(RideState(), true, {}, { cancels++ })
        } }
        compose.onNodeWithTag("ride_disconnect").assertDoesNotExist()
        compose.onNodeWithTag("ride_permission_cancel").performClick()
        assertEquals(1, cancels)
    }

    @Test fun failureKeepsObservedStepWithoutShowingProgressOrConnectedControls() {
        val state = mutableStateOf(RideState(stage = RideStage.CONNECTING, phase = RuntimePhase.AUTHENTICATING))
        compose.setContent { MaterialTheme { RideStatusCard(state.value, false, {}, {}) } }
        compose.onNodeWithText("正在驗證車輛連線").assertExists()
        compose.onNodeWithTag("ride_connecting_progress").assertExists()
        compose.runOnIdle { state.value = state.value.copy(stage = RideStage.FAILED, errorCode = "TIMEOUT") }
        compose.onNodeWithText("中止於：驗證車輛連線").assertExists()
        compose.onNodeWithTag("ride_connecting_progress").assertDoesNotExist()
        compose.onNodeWithTag("ride_disconnect").assertDoesNotExist()
        compose.runOnIdle { state.value = RideState(RideStage.CONNECTED) }
        compose.onNodeWithTag("ride_connection_phase").assertDoesNotExist()
        compose.runOnIdle { state.value = RideState() }
        compose.onNodeWithTag("ride_status_card").assertDoesNotExist()
    }
}
