package org.openridesync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.vehicles.GarageVehicleCard
import org.openridesync.honda.PairedVehicleStore

/** Synthetic rendering only; does not access real garage data or Bluetooth. */
class GarageVehicleCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownYearAndIncompleteStateAreNotReportedAsPaired() {
        compose.setContent { MaterialTheme {
            GarageVehicleCard("Synthetic vehicle", 0, PairedVehicleStore.State.VEHICLE_SELECTION, false)
        } }
        compose.onNodeWithText("年式尚未確認").assertExists()
        compose.onNodeWithText("需要確認車型").assertExists()
        compose.onNodeWithText("已配對").assertDoesNotExist()
        compose.onNodeWithText("目前選定車輛").assertDoesNotExist()
        compose.onNodeWithTag("garage_vehicle").assertHasNoClickAction()
    }
}
