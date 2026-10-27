package org.openridesync.app.vehicles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.openridesync.app.runtime.RideStage
import org.openridesync.honda.PairedVehicleStore

/** Full-span, borderless hero. An unpaired account deliberately has no substitute image. */
@Composable
internal fun GaragePrimaryHero(vehicle: PairedVehicleStore.Vehicle?, imageRequests: VehicleImageRequests,
    onDetails: (PairedVehicleStore.Vehicle) -> Unit) {
    if (vehicle == null) {
        Spacer(Modifier.fillMaxWidth().height(220.dp).testTag("garage_primary_empty"))
        return
    }
    Column(Modifier.fillMaxWidth().testTag("garage_primary")
        .clickable(role = Role.Button, onClickLabel = "查看主要車輛配對資訊") { onDetails(vehicle) },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VehicleImage(imageId(vehicle.model), Modifier.fillMaxWidth().height(220.dp), imageRequests)
        Text("主要車輛", style = MaterialTheme.typography.labelLarge)
        Text(vehicle.vehicleName.ifBlank { modelName(vehicle.model) }, style = MaterialTheme.typography.headlineSmall)
        Text(garageStateLabel(vehicle.state), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun GarageVehicleTile(vehicle: PairedVehicleStore.Vehicle, imageRequests: VehicleImageRequests,
    onDetails: () -> Unit) {
    Card(onClick = onDetails, modifier = Modifier.fillMaxWidth().testTag("garage_vehicle_tile")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleImage(imageId(vehicle.model), Modifier.fillMaxWidth().height(120.dp), imageRequests)
            Text(vehicle.vehicleName.ifBlank { modelName(vehicle.model) }, style = MaterialTheme.typography.titleMedium)
            Text(if (vehicle.year == 0) "年式尚未確認" else "${vehicle.year} 年式")
            Text(garageStateLabel(vehicle.state), style = MaterialTheme.typography.bodySmall)
            if (vehicle.inFocus) Text("主要車輛", style = MaterialTheme.typography.labelMedium)
            Text("配對資訊與管理", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Shows the selected persisted device, not an inferred match on the catalog model label. */
@Composable
internal fun GarageSavedDetails(vehicle: PairedVehicleStore.Vehicle, rideStage: RideStage,
    permissionPending: Boolean, actions: VehicleActions,
    mutationBusy: Boolean = false, mutationError: Boolean = false) {
    var confirmation by remember(vehicle.mac) { mutableStateOf<GarageChange?>(null) }
    val canManage = !permissionPending && !mutationBusy && !mutationError && rideStage in setOf(RideStage.IDLE, RideStage.FAILED)
    confirmation?.let { change ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (change == GarageChange.PRIMARY) "設為主要車輛？" else "從本 App 車庫移除？") },
            text = { Text(if (change == GarageChange.PRIMARY)
                "將 ${vehicle.vehicleName.ifBlank { modelName(vehicle.model) }} 設為本帳號的主要車輛。這只變更本機顯示偏好，不會連線或控制車輛。"
                else "只移除此帳號在本 App 的車輛配對紀錄。Android 藍牙配對與遠端資料不會變更；要再次使用需重新完成 App 配對。移除主要車輛後不會自動選取其他車輛。") },
            confirmButton = { TextButton(enabled = canManage, modifier = Modifier.testTag("garage_change_confirm"),
                onClick = { confirmation = null; actions.onGarageChange(change, vehicle) }) { Text("確認") } },
            dismissButton = { TextButton(modifier = Modifier.testTag("garage_change_cancel"),
                onClick = { confirmation = null }) { Text("取消") } })
    }
    Column(Modifier.fillMaxWidth().testTag("garage_saved_details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("配對資訊與管理", style = MaterialTheme.typography.titleLarge)
        GarageVehicleCard(vehicle.vehicleName.ifBlank { modelName(vehicle.model) }, vehicle.year,
            vehicle.state, vehicle.inFocus,
            onConnect = if (canConnectSavedVehicle(vehicle)) ({ actions.onConnect(vehicle) }) else null,
            connectEnabled = canManage)
        Text("車型：${modelName(vehicle.model)}")
        Text("裝置：${vehicle.btuName.ifBlank { "未提供名稱" }}")
        Text("藍牙位址：${vehicle.mac}")
        Text("儀表世代：${vehicle.model.meter.generation}")
        Text("韌體：${vehicle.firmwareVersion.ifBlank { "未提供" }}")
        if (vehicle.model.meter.generation !in 1..2)
            Text("此儀表世代的連線方式尚未支援。", style = MaterialTheme.typography.bodySmall)
        if (!vehicle.inFocus) OutlinedButton(enabled = canManage,
            onClick = { confirmation = GarageChange.PRIMARY }, modifier = Modifier.testTag("garage_set_primary")) {
            Text("設為主要車輛")
        }
        OutlinedButton(enabled = canManage, onClick = { confirmation = GarageChange.FORGET },
            modifier = Modifier.testTag("garage_forget")) { Text("從本 App 車庫移除") }
        if (mutationBusy) Text("正在保存本機車庫…", Modifier.testTag("garage_change_busy"))
        if (mutationError) {
            Text("無法確認車庫更新結果。請重新讀取本機車庫後再試。", Modifier.testTag("garage_change_error"))
            TextButton(onClick = actions.onReloadGarage, enabled = !mutationBusy) { Text("重新讀取車庫") }
        }
        if (!canManage && !mutationBusy && !mutationError) Text("請先結束連線或權限請求，再管理本機車庫。")
        Text("下方年式選擇與儲存只影響車型偏好，不會變更此配對紀錄。", style = MaterialTheme.typography.bodySmall)
    }
}
