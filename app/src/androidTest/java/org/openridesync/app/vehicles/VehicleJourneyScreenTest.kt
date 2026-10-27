package org.openridesync.app.vehicles

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openridesync.honda.wire.HondaWire.*

/** Synthetic content actions; no login, file read, catalog RPC, image download or BLE. */
class VehicleJourneyScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("vehicle_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    @Test fun emptyGarageHasExplicitAddAndNoCatalogReadOnComposition() {
        var add = 0
        var refresh = 0
        compose.setContent { MaterialTheme {
            VehicleCatalogContent(VehicleState(garageLoaded = true), true, actions = VehicleActions(onAdd = { add++ }, onRefresh = { refresh++ }))
        } }
        compose.runOnIdle { assertEquals(0, add); assertEquals(0, refresh) }
        scrollTo("garage_empty").assertExists()
        compose.onNodeWithTag("vehicles_own_country").assertDoesNotExist()
        scrollTo("garage_add").performClick()
        compose.runOnIdle { assertEquals(1, add); assertEquals(0, refresh) }
    }
    @Test fun unreadGarageDoesNotClaimNoSavedVehicles() {
        compose.setContent { MaterialTheme { VehicleCatalogContent(VehicleState(), true) } }
        scrollTo("garage_unread").assertExists()
        compose.onNodeWithTag("garage_empty").assertDoesNotExist()
    }
    @Test fun cancelledOrFailedCatalogDoesNotClaimEmptyResult() {
        val state = mutableStateOf(VehicleState(browsingCatalog = true, cancelled = true))
        compose.setContent { MaterialTheme { VehicleCatalogContent(state.value, true) } }
        scrollTo("vehicles_cancelled").assertExists()
        compose.onNodeWithTag("vehicles_empty").assertDoesNotExist()
        compose.runOnIdle { state.value = VehicleState(browsingCatalog = true, errorCode = "NETWORK") }
        scrollTo("vehicles_error").assertExists()
        compose.onNodeWithTag("vehicles_empty").assertDoesNotExist()
        compose.runOnIdle { state.value = VehicleState(browsingCatalog = true, catalogLoaded = true) }
        scrollTo("vehicles_empty").assertExists()
    }
    @Test fun accountRecoveryAndBackAreExplicit() {
        var accounts = 0
        var backs = 0
        compose.setContent { MaterialTheme {
            VehicleCatalogContent(VehicleState(), false, actions = VehicleActions(onAccount = { accounts++ }, onBack = { backs++ }))
        } }
        scrollTo("vehicles_account").performClick()
        compose.onNodeWithTag("vehicles_back").performClick()
        compose.runOnIdle { assertEquals(1, accounts); assertEquals(1, backs) }
    }
    @Test fun detailYearAndPreferenceAreSeparateFromPairing() {
        val model = Model.newBuilder().setModelLabel("synthetic-model").addModelYears(2026).addModelYears(2025).build()
        var saves = 0
        var pairs = 0
        compose.setContent { MaterialTheme {
            VehicleCatalogContent(VehicleState(browsingCatalog = true, detail = model, detailYear = 2025), true,
                actions = VehicleActions(onSave = { saves++ }, onPair = { pairs++ }))
        } }
        scrollTo("vehicle_save").performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(0, pairs) }
        compose.onNodeWithTag("garage_vehicle").assertDoesNotExist()
    }
    @Test fun savedUnknownYearRequiresChoiceBeforePreferenceSave() {
        val model = Model.newBuilder().setModelLabel("synthetic").addModelYears(2025).build()
        compose.setContent { MaterialTheme {
            VehicleCatalogContent(VehicleState(detail = model, detailYear = 0), true)
        } }
        scrollTo("vehicle_save").assertIsNotEnabled()
    }

    @Test fun listScrollSurvivesDetailAndBackWithoutRefreshCallback() {
        val models = (0..20).map { Model.newBuilder().setModelLabel("model-$it").build() }
        val parent = VehicleState(browsingCatalog = true, catalogLoaded = true,
            catalogs = models.map { ModelCatalog.newBuilder().setModel(it).setModelCount(1).build() })
        val state = mutableStateOf(parent)
        var refreshes = 0
        compose.setContent { MaterialTheme {
            VehicleCatalogContent(state.value, true, actions = VehicleActions(onRefresh = { refreshes++ }))
        } }
        compose.onNodeWithTag("vehicle_list").performScrollToNode(hasText("model-15"))
        compose.onNodeWithText("model-15").assertIsDisplayed()
        compose.runOnIdle { state.value = parent.copy(detail = models[15]) }
        scrollTo("vehicle_save").assertExists()
        compose.runOnIdle { state.value = vehicleBackPage(state.value, null, parent)!! }
        compose.onNodeWithText("model-15").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

}
