package org.openridesync.app.vehicles

internal enum class VehiclePage { GARAGE, CATALOG, SERIES, DETAIL, GUIDE }

internal fun vehiclePage(state: VehicleState): VehiclePage = when {
    state.guide != null -> VehiclePage.GUIDE
    state.detail != null -> VehiclePage.DETAIL
    state.activeSeries != null -> VehiclePage.SERIES
    state.browsingCatalog -> VehiclePage.CATALOG
    else -> VehiclePage.GARAGE
}

/** Parent browsing outcome is restored, while independently updated local garage/selection survive. */
internal fun restoreVehiclePage(parent: VehicleState, current: VehicleState): VehicleState = parent.copy(
    busy = false, selection = current.selection, pairedVehicles = current.pairedVehicles,
    garageMutationBusy = current.garageMutationBusy, garageMutationError = current.garageMutationError,
    garageLoading = current.garageLoading, garageLoaded = current.garageLoaded, garageError = current.garageError,
)

internal fun vehicleListIsEmpty(state: VehicleState): Boolean = !state.busy && !state.cancelled && state.errorCode == null && when (vehiclePage(state)) {
    VehiclePage.CATALOG -> state.catalogLoaded && state.catalogs.isEmpty()
    VehiclePage.SERIES -> state.seriesLoaded && state.models.isEmpty()
    else -> false
}

/** Pure Back decision: no reload action exists in this contract. */
internal fun vehicleBackPage(current: VehicleState, catalogParent: VehicleState?, detailParent: VehicleState?): VehicleState? = when {
    current.guide != null -> current.copy(guide = null, busy = false, errorCode = null, cancelled = false)
    current.detail != null -> restoreVehiclePage(detailParent ?: current.copy(detail = null, detailVehicleMac = null, detailYear = null), current)
    current.activeSeries != null -> restoreVehiclePage(catalogParent ?: current.copy(activeSeries = null, models = emptyList()), current)
    current.browsingCatalog -> current.copy(browsingCatalog = false, busy = false, cancelled = current.cancelled || current.busy)
    else -> null
}

internal fun vehicleCatalogNeedsLoad(state: VehicleState): Boolean = vehiclePage(state) == VehiclePage.CATALOG &&
    !state.busy && !state.catalogLoaded && !state.cancelled && state.errorCode == null

internal fun canOpenVehicleDetail(state: VehicleState): Boolean = !state.busy && state.detail == null && state.guide == null

internal fun canSaveVehiclePreference(model: org.openridesync.honda.wire.HondaWire.Model, year: Int): Boolean =
    if (year == 0) model.modelYearsCount == 0 else year in model.modelYearsList
