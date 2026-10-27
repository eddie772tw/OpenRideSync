package org.openridesync.app.vehicles

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.VehicleStore
import org.openridesync.honda.wire.HondaWire.*

class GarageLayoutTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun model(generation: Int = 1) = Model.newBuilder()
        .setModelLabel("test-bike").setModelSeriesLabel("test-series")
        .addModelYears(2026).setMeter(Meter.newBuilder().setGeneration(generation)).build()

    private fun vehicles(generation: Int = 1, state: PairedVehicleStore.State = PairedVehicleStore.State.PAIRED): List<PairedVehicleStore.Vehicle> {
        val store = PairedVehicleStore(temporary.newFolder())
        store.upsert("00:11:22:33:44:55", "First meter", "series", "1", "First bike", model(generation), 2026, state)
        store.upsert("00:11:22:33:44:66", "Second meter", "series", "1", "Second bike", model(generation), 2026, state)
        return store.read()
    }

    @Test fun phoneUsesTwoColumnsAndWideWindowExpands() {
        assertEquals(2, vehicleGridColumns(390f, 1f))
        assertEquals(2, vehicleGridColumns(390f, 1.05f))
        assertTrue(vehicleGridColumns(840f, 1f) > 2)
        assertEquals(1, vehicleGridColumns(390f, 2f))
        assertEquals(1, vehicleGridColumns(280f, 1f))
    }

    @Test fun heroUsesPersistedFocusNotListPosition() {
        val saved = vehicles()
        val state = VehicleState(pairedVehicles = saved)
        assertEquals(saved[1].mac, primaryGarageVehicle(state)?.mac)
        assertEquals(saved[1].mac, primaryGarageVehicle(state.copy(pairedVehicles = saved.reversed()))?.mac)
        assertNull(primaryGarageVehicle(state.copy(pairedVehicles = listOf(saved[0]))))
        assertNull(primaryGarageVehicle(state.copy(pairedVehicles = emptyList())))
    }

    @Test fun catalogPreferenceCannotCreateAPairedHero() {
        val store = VehicleStore(temporary.newFolder())
        store.writeSelection(model(), 2026)
        val state = VehicleState(selection = store.readSelection(), garageLoaded = true)
        assertNotNull(state.selection)
        assertNull(primaryGarageVehicle(state))
    }

    @Test fun savedDetailBackClearsDeviceIdentityWithoutCatalogRead() {
        val saved = vehicles()
        val garage = VehicleState(pairedVehicles = saved, garageLoaded = true)
        val detail = garage.copy(detail = saved[0].model, detailVehicleMac = saved[0].mac)
        val returned = vehicleBackPage(detail, null, garage)!!
        assertNull(returned.detailVehicleMac)
        assertEquals(VehiclePage.GARAGE, vehiclePage(returned))
        assertFalse(vehicleCatalogNeedsLoad(returned))
    }

    @Test fun savedDetailRetainsPhysicalIdentityForIdenticalModels() {
        val saved = vehicles()
        val state = VehicleState(detail = saved[0].model, detailVehicleMac = saved[0].mac, pairedVehicles = saved)
        assertEquals("First meter", savedDetailVehicle(state)?.btuName)
        assertNull(savedDetailVehicle(state.copy(detailVehicleMac = null)))
        assertNull(savedDetailVehicle(state.copy(detailVehicleMac = "unknown")))
    }

    @Test fun onlySupportedGenerationAndReadyOrPairedEnableConnect() {
        for (generation in 1..3) {
            for (state in PairedVehicleStore.State.values()) {
                val vehicle = vehicles(generation, state).first()
                assertEquals(generation in 1..2 && state in setOf(PairedVehicleStore.State.PAIRED, PairedVehicleStore.State.READY),
                    canConnectSavedVehicle(vehicle))
            }
        }
    }
}
