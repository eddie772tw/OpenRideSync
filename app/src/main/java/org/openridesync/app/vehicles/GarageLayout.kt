package org.openridesync.app.vehicles

import org.openridesync.honda.PairedVehicleStore

/** 390dp phones get two columns; large text/narrow windows retain readable touch targets. */
internal fun vehicleGridColumns(widthDp: Float, fontScale: Float): Int {
    val available = (widthDp - 40f).coerceAtLeast(0f)
    val minimum = 156f * fontScale.coerceAtLeast(1f)
    return ((available + 12f) / (minimum + 12f)).toInt().coerceIn(1, 6)
}

/** Neither list order nor a saved catalog preference may stand in for persisted focus. */
internal fun primaryGarageVehicle(state: VehicleState): PairedVehicleStore.Vehicle? =
    state.pairedVehicles.singleOrNull { it.inFocus }

internal fun savedDetailVehicle(state: VehicleState): PairedVehicleStore.Vehicle? =
    state.detailVehicleMac?.let { mac -> state.pairedVehicles.singleOrNull { it.mac == mac } }

internal fun canConnectSavedVehicle(vehicle: PairedVehicleStore.Vehicle): Boolean =
    vehicle.model.meter.generation in 1..2 &&
        vehicle.state in setOf(PairedVehicleStore.State.PAIRED, PairedVehicleStore.State.READY)

internal fun garageStateLabel(state: PairedVehicleStore.State): String = when (state) {
    PairedVehicleStore.State.REPAIR_NEEDED -> "需要重新配對"
    PairedVehicleStore.State.PAIRED -> "已配對"
    PairedVehicleStore.State.VEHICLE_SELECTION -> "需要確認車型"
    PairedVehicleStore.State.TUTORIAL_PENDING -> "尚未完成使用教學"
    PairedVehicleStore.State.DFU_CHECK_PENDING -> "尚未檢查韌體更新"
    PairedVehicleStore.State.READY -> "已保存狀態：就緒"
    PairedVehicleStore.State.REGULAR_UPDATE_NEEDED -> "有可用韌體更新"
    PairedVehicleStore.State.CRITICAL_UPDATE_NEEDED -> "需要重要韌體更新"
}
