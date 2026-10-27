package org.openridesync.app.vehicles

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.guides.GuideKind
import org.openridesync.app.guides.VehicleGuide
import org.openridesync.honda.wire.HondaWire.*

class VehicleJourneyTest {
    private val model = Model.newBuilder().setModelLabel("synthetic-model").addModelYears(2026).addModelYears(2025).build()
    private val catalog = ModelCatalog.newBuilder().setModel(model).setModelCount(2).build()
    private val parent = VehicleState(browsingCatalog = true, catalogLoaded = true,
        catalogs = listOf(catalog), countryCode = "TW", cached = true)

    @Test fun garageDoesNotAuthorizeCatalogReadUntilExplicitEntry() {
        assertEquals(VehiclePage.GARAGE, vehiclePage(VehicleState()))
        assertFalse(vehicleCatalogNeedsLoad(VehicleState()))
        assertTrue(vehicleCatalogNeedsLoad(VehicleState(browsingCatalog = true)))
        assertFalse(vehicleCatalogNeedsLoad(parent))
    }
    @Test fun cancelledFailedOrBusyEntryDoesNotRestartWithoutRetry() {
        val entered = VehicleState(browsingCatalog = true)
        assertFalse(vehicleCatalogNeedsLoad(entered.copy(cancelled = true)))
        assertFalse(vehicleCatalogNeedsLoad(entered.copy(errorCode = "NETWORK")))
        assertFalse(vehicleCatalogNeedsLoad(entered.copy(busy = true)))
    }
    @Test fun repeatedModelOpenCannotCreateSelfParent() {
        assertTrue(canOpenVehicleDetail(parent))
        assertFalse(canOpenVehicleDetail(parent.copy(detail = model, detailYear = 2025)))
        assertFalse(canOpenVehicleDetail(parent.copy(busy = true)))
        assertFalse(canOpenVehicleDetail(parent.copy(guide = VehicleGuide(GuideKind.SETUP))))
    }
    @Test fun unknownGarageYearCannotBeSavedAsAConfirmedPreference() {
        assertFalse(canSaveVehiclePreference(model, 0))
        assertTrue(canSaveVehiclePreference(model, 2025))
        assertFalse(canSaveVehiclePreference(model, 1999))
        assertTrue(canSaveVehiclePreference(model.toBuilder().clearModelYears().build(), 0))
    }
    @Test fun onlySuccessfulEmptyListsAreEmpty() {
        val entered = VehicleState(browsingCatalog = true)
        assertFalse(vehicleListIsEmpty(entered))
        assertTrue(vehicleListIsEmpty(entered.copy(catalogLoaded = true)))
        for (state in listOf(entered.copy(catalogLoaded = true, cancelled = true),
            entered.copy(catalogLoaded = true, errorCode = "NETWORK"), entered.copy(catalogLoaded = true, busy = true)))
            assertFalse(vehicleListIsEmpty(state))
        assertFalse(vehicleListIsEmpty(parent))
        assertFalse(vehicleListIsEmpty(parent.copy(activeSeries = catalog)))
        assertTrue(vehicleListIsEmpty(parent.copy(activeSeries = catalog, seriesLoaded = true)))
    }
    @Test fun seriesBackRestoresParentOutcomeAndNeverRequiresReload() {
        val failedSeries = parent.copy(activeSeries = catalog, busy = true, cached = false, errorCode = "NETWORK")
        val restored = vehicleBackPage(failedSeries, parent, null)!!
        assertEquals(parent.catalogs, restored.catalogs)
        assertTrue(restored.cached)
        assertNull(restored.errorCode)
        assertFalse(restored.busy)
        assertNull(restored.activeSeries)
        assertFalse(vehicleCatalogNeedsLoad(restored))
    }
    @Test fun detailBackRestoresSeriesAndPreservesLatestGarageResult() {
        val series = parent.copy(activeSeries = catalog, models = listOf(model), seriesLoaded = true, cached = true)
        val detail = series.copy(detail = model, detailYear = 2025, garageError = true)
        val restored = vehicleBackPage(detail, parent, series)!!
        assertEquals(VehiclePage.SERIES, vehiclePage(restored))
        assertEquals(listOf(model), restored.models)
        assertTrue(restored.garageError)
        assertTrue(restored.cached)
    }
    @Test fun guideBackKeepsChosenYearAndNeverMarksPaired() {
        val detail = parent.copy(detail = model, detailYear = 2025)
        val guide = detail.copy(guide = VehicleGuide(GuideKind.SETUP), busy = true)
        val restored = vehicleBackPage(guide, null, parent)!!
        assertEquals(VehiclePage.DETAIL, vehiclePage(restored))
        assertEquals(2025, restored.detailYear)
        assertTrue(restored.pairedVehicles.isEmpty())
        assertFalse(restored.busy)
        assertFalse(restored.cancelled)
    }
    @Test fun cancelledCatalogBackReturnsGarageWithoutFabricatingEmptySuccess() {
        val loading = VehicleState(browsingCatalog = true, busy = true)
        val garage = vehicleBackPage(loading, null, null)!!
        assertEquals(VehiclePage.GARAGE, vehiclePage(garage))
        assertTrue(garage.cancelled)
        assertFalse(garage.catalogLoaded)
        assertFalse(vehicleCatalogNeedsLoad(garage.copy(browsingCatalog = true)))
        assertNull(vehicleBackPage(garage, null, null))
    }
}
