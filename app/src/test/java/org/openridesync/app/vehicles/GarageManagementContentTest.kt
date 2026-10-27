package org.openridesync.app.vehicles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openridesync.app.runtime.RideStage
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.wire.HondaWire.Model
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GarageManagementContentTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private fun vehicle(): PairedVehicleStore.Vehicle {
        val store = PairedVehicleStore(temp.newFolder())
        val model = Model.newBuilder().setModelLabel("same-model").setModelSeriesLabel("series").build()
        store.upsert("02:AA:BB:CC:DD:01", "fixture", "series", "fw", "First", model, 0, PairedVehicleStore.State.PAIRED)
        store.upsert("02:AA:BB:CC:DD:02", "fixture", "series", "fw", "Second", model, 0, PairedVehicleStore.State.PAIRED)
        return store.read().first()
    }
    @Test fun explicitConfirmationAndCancelNeverTriggerImplicitMutation() {
        val v = vehicle(); val changes = mutableListOf<Pair<GarageChange, String>>()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { GarageSavedDetails(v, RideStage.IDLE, false,
            VehicleActions(onGarageChange = { change, target -> changes += change to target.mac })) } } }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        compose.onNodeWithTag("garage_set_primary").performScrollTo().performClick()
        compose.onNodeWithTag("garage_change_cancel").performClick()
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        compose.onNodeWithTag("garage_set_primary").performScrollTo().performClick()
        compose.onNodeWithTag("garage_change_confirm").performClick()
        compose.onNodeWithTag("garage_change_confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(GarageChange.PRIMARY to v.mac), changes) }
        compose.onNodeWithTag("garage_forget").performScrollTo().performClick()
        compose.onNodeWithText("從本 App 車庫移除？").assertExists()
        compose.onNodeWithTag("garage_change_confirm").performClick()
        compose.runOnIdle { assertEquals(GarageChange.FORGET to v.mac, changes.last()); assertEquals(2, changes.size) }
    }
    @Test fun permissionChangeWhileDialogOpenDisablesConfirmation() {
        val v = vehicle(); val pending = mutableStateOf(false); var changes = 0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { GarageSavedDetails(v, RideStage.IDLE, pending.value,
            VehicleActions(onGarageChange = { _, _ -> changes++ })) } } }
        compose.onNodeWithTag("garage_forget").performScrollTo().performClick()
        compose.runOnIdle { pending.value = true }
        compose.onNodeWithTag("garage_change_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("garage_change_cancel").performClick()
        compose.onNodeWithTag("garage_forget").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, changes) }
    }
    @Test fun uncertainCommitBlocksNewActionsButOffersExplicitReload() {
        val v = vehicle(); var reloads = 0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            GarageSavedDetails(v, RideStage.IDLE, false, VehicleActions(onReloadGarage = { reloads++ }), mutationError = true)
        } } }
        compose.onNodeWithTag("garage_set_primary").assertIsNotEnabled()
        compose.onNodeWithTag("garage_forget").assertIsNotEnabled()
        compose.onNodeWithText("重新讀取車庫").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reloads) }
    }

}
