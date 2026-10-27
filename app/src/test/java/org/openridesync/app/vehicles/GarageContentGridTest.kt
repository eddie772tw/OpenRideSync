package org.openridesync.app.vehicles

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.VehicleStore
import org.openridesync.honda.wire.HondaWire.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real production grid with synthetic records and blank images; no auth owner or image RPC. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GarageContentGridTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val requests = VehicleImageRequests()
    private val model = Model.newBuilder().setModelLabel("synthetic-shared-model")
        .setModelSeriesLabel("synthetic-series").addModelYears(2026)
        .setMeter(Meter.newBuilder().setGeneration(1)).build()

    @After fun closeImageOwner() { requests.close() }

    private fun show(state: MutableState<VehicleState>, actions: VehicleActions = VehicleActions(),
        width: Int = 390, fontScale: Float = 1f) {
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
        assertEquals(0, model.imagesCount)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width.dp, 700.dp)) {
                        VehicleCatalogContent(state.value, signedIn = true, actions = actions, imageRequests = requests)
                    }
                }
            }
        }
    }

    private fun saved(longNames: Boolean = false): List<PairedVehicleStore.Vehicle> {
        val store = PairedVehicleStore(temporary.newFolder())
        (1..3).forEach { index ->
            val name = if (longNames) "合成車輛 $index：很長的名稱用來確認放大字體仍然可以閱讀並開啟配對資訊" else "合成車輛 $index"
            store.upsert("00:11:22:33:44:0$index", "Synthetic meter $index", "synthetic-series", "1", name,
                model, 2026, PairedVehicleStore.State.PAIRED)
        }
        return store.read()
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("vehicle_list").performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    @Test fun emptyHeroCannotUseCatalogPreferenceAndAddIsExplicit() {
        val preferences = VehicleStore(temporary.newFolder())
        preferences.writeSelection(model, 2026)
        var adds = 0
        var refreshes = 0
        show(mutableStateOf(VehicleState(garageLoaded = true, selection = preferences.readSelection())),
            VehicleActions(onAdd = { adds++ }, onRefresh = { refreshes++ }))
        compose.onNodeWithTag("garage_primary_empty").assertExists()
        compose.onNodeWithTag("garage_primary").assertDoesNotExist()
        compose.onAllNodesWithTag("garage_vehicle_tile").assertCountEquals(0)
        scrollTo(hasTestTag("garage_empty")).assertExists()
        compose.runOnIdle { assertEquals(0, adds); assertEquals(0, refreshes) }
        scrollTo(hasTestTag("garage_add")).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, adds); assertEquals(0, refreshes) }
    }

    @Test fun primaryIsNotRepeatedAndSameModelTilesPreservePhysicalIdentityInTwoColumns() {
        val vehicles = saved()
        val selected = mutableListOf<String>()
        show(mutableStateOf(VehicleState(garageLoaded = true, pairedVehicles = vehicles)),
            VehicleActions(onSavedInfo = { selected.add(it.mac) }))
        compose.onNodeWithTag("garage_primary").performClick()
        scrollTo(hasTestTag("garage_vehicle_tile") and hasText(vehicles[0].vehicleName)).assertIsDisplayed()
        compose.onAllNodesWithTag("garage_vehicle_tile").assertCountEquals(2)
        val first = compose.onNode(hasTestTag("garage_vehicle_tile") and hasText(vehicles[0].vehicleName))
        val second = compose.onNode(hasTestTag("garage_vehicle_tile") and hasText(vehicles[1].vehicleName))
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val secondBounds = second.fetchSemanticsNode().boundsInRoot
        assertEquals(firstBounds.top, secondBounds.top, 1f)
        assertTrue(firstBounds.left < secondBounds.left)
        compose.onNode(hasTestTag("garage_vehicle_tile") and hasText(vehicles[2].vehicleName)).assertDoesNotExist()
        first.performClick()
        second.performClick()
        compose.runOnIdle { assertEquals(listOf(vehicles[2].mac, vehicles[0].mac, vehicles[1].mac), selected) }
    }

    @Test fun unreadLoadingAndFailureRemainDifferentFromSuccessfulEmptyGarage() {
        val state = mutableStateOf(VehicleState())
        show(state)
        scrollTo(hasTestTag("garage_unread")).assertExists()
        compose.onNodeWithTag("garage_empty").assertDoesNotExist()
        compose.runOnIdle { state.value = VehicleState(garageLoading = true) }
        compose.onNodeWithTag("garage_empty").assertDoesNotExist()
        compose.onNodeWithTag("garage_unread").assertDoesNotExist()
        compose.runOnIdle { state.value = VehicleState(garageLoaded = true, garageError = true) }
        scrollTo(hasTestTag("garage_error")).assertExists()
        compose.onNodeWithTag("garage_empty").assertDoesNotExist()
        compose.runOnIdle { state.value = VehicleState(garageLoaded = true) }
        scrollTo(hasTestTag("garage_empty")).assertExists()
        compose.onNodeWithTag("garage_error").assertDoesNotExist()
    }

    @Test fun narrowDoubleFontKeepsAddAndLongNamedVehicleActionsReachable() {
        val vehicles = saved(longNames = true)
        var adds = 0
        val selected = mutableListOf<String>()
        show(mutableStateOf(VehicleState(garageLoaded = true, pairedVehicles = vehicles)),
            VehicleActions(onAdd = { adds++ }, onSavedInfo = { selected.add(it.mac) }), width = 320, fontScale = 2f)
        scrollTo(hasTestTag("garage_add")).assertIsDisplayed().performClick()
        scrollTo(hasTestTag("garage_vehicle_tile") and hasText(vehicles[0].vehicleName))
            .assertIsDisplayed().performClick()
        scrollTo(hasTestTag("garage_vehicle_tile") and hasText(vehicles[1].vehicleName))
            .assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, adds)
            assertEquals(listOf(vehicles[0].mac, vehicles[1].mac), selected)
        }
    }
}
