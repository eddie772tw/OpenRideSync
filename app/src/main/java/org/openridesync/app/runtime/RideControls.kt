package org.openridesync.app.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.ble.RuntimePhase
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

@Composable
internal fun RidePermissionLauncher(model: RideViewModel, request: RideViewModel.PermissionRequest?) {
    if (request == null) return
    PermissionDialog(listOf(AppAccess.CONNECTION, AppAccess.NOTIFICATIONS, AppAccess.LOCATION),
        required = setOf(AppAccess.CONNECTION), confirmLabel = "繼續連線",
        onConfirm = { model.permissionsResult(request.id) }, onDismiss = model::cancelPermissionRequest)
}

@Composable
internal fun RideStatusCard(state: RideState, permissionPending: Boolean, onDisconnect: () -> Unit, onCancelPermission: () -> Unit,
    compact: Boolean = false) {
    if (state.stage == RideStage.IDLE && !permissionPending) return
    if (compact && state.stage == RideStage.CONNECTED && !permissionPending) {
        Row(Modifier.fillMaxWidth().testTag("ride_status_card"),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(state.name?.takeIf(String::isNotBlank) ?: "車輛已連線", style = MaterialTheme.typography.titleLarge)
                Text(when (state.mode) {
                    Gen1Input.Function.MODE_SAB -> "車輛模式：RoadSync"
                    Gen1Input.Function.MODE_HU -> "車輛模式：儀表"
                    else -> "尚未收到車輛模式"
                }, Modifier.testTag("ride_status"), style = MaterialTheme.typography.titleMedium)
                DrivingErrorNotice(state)
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.testTag("ride_disconnect")) { Text("中斷連線") }
        }
        return
    }
    Card(Modifier.fillMaxWidth().testTag("ride_status_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.name ?: "車輛連線", style = MaterialTheme.typography.titleMedium)
            Text(if (permissionPending) "請先閱讀權限說明，自行選擇是否提出請求。顯示通知並非連線必要權限。" else when (state.stage) {
                RideStage.IDLE -> "尚未連線"
                RideStage.CONNECTING -> "正在連接車輛…"
                RideStage.RECONNECTING -> "正在重新連接車輛…"
                RideStage.CONNECTED -> "車輛已連線"
                RideStage.STOPPING -> "正在中斷連線…"
                RideStage.FAILED -> rideFailureMessage(state.errorCode)
            }, Modifier.testTag("ride_status").semantics { liveRegion = LiveRegionMode.Polite })
            if (!permissionPending && state.stage in setOf(RideStage.CONNECTING, RideStage.RECONNECTING)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ride_connecting_progress"))
            }
            if (!permissionPending && state.stage in setOf(RideStage.CONNECTING, RideStage.RECONNECTING, RideStage.FAILED)) state.phase?.let {
                Text((if (state.stage == RideStage.FAILED) "中止於：" else "正在") + runtimePhaseLabel(it),
                    Modifier.testTag("ride_connection_phase").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (state.stage == RideStage.CONNECTED) {
                Text(when (state.mode) {
                    Gen1Input.Function.MODE_SAB -> "車輛模式：RoadSync"
                    Gen1Input.Function.MODE_HU -> "車輛模式：儀表"
                    else -> "尚未收到車輛模式"
                })
                state.rssi?.let { Text("藍牙訊號：$it dBm", style = MaterialTheme.typography.bodySmall) }
                DrivingErrorNotice(state)
            }
            if (permissionPending) OutlinedButton(onClick = onCancelPermission, modifier = Modifier.testTag("ride_permission_cancel")) { Text("取消連線") }
            if (state.stage in setOf(RideStage.CONNECTING, RideStage.RECONNECTING, RideStage.CONNECTED, RideStage.STOPPING)) {
                OutlinedButton(onClick = onDisconnect, enabled = state.stage != RideStage.STOPPING,
                    modifier = Modifier.testTag("ride_disconnect")) {
                    Text(if (state.stage in setOf(RideStage.CONNECTING, RideStage.RECONNECTING)) "取消連線" else "中斷連線")
                }
            }
        }
    }
}

@Composable
private fun DrivingErrorNotice(state: RideState) {
    if (state.drivingError == true) Text("車輛資料回報異常，請查看儀表狀態。",
        Modifier.testTag("ride_driving_error").semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

internal fun runtimePhaseLabel(phase: RuntimePhase): String = when (phase) {
    RuntimePhase.PREPARING -> "準備車輛連線"
    RuntimePhase.CONNECTING -> "建立藍牙連線"
    RuntimePhase.DISCOVERING -> "探索車輛服務"
    RuntimePhase.READING_INFORMATION -> "讀取車輛資訊"
    RuntimePhase.SUBSCRIBING -> "啟用車輛通知"
    RuntimePhase.REGISTERING -> "設定儀表控制"
    RuntimePhase.AUTHENTICATING -> "驗證車輛連線"
}

internal fun rideFailureMessage(code: String?): String = when (code) {
    "RECONNECT_LOCATION_REQUIRED" -> "自動重連需要位置授權與手機定位。可到「權限與功能」允許概略位置，或保持目前設定，從車庫手動連線。"
    "RECONNECT_STOPPED" -> "自動重連已停止，可從車庫手動連線。"
    "RECONNECT_WINDOW_EXPIRED" -> "自動重連等待時間已結束，請確認車輛在附近，再從車庫手動連線。"
    "RECONNECT_LIMIT" -> "已達自動重連次數上限，請確認手機與車輛狀態，再從車庫手動連線。"
    "AUTH_REQUIRED", "UNAUTHORIZED" -> "請重新登入後再連線。"
    "OPERATION_BUSY", "ALREADY_EXISTS" -> "另一個車輛操作尚未結束，請先結束該操作後再連線。"
    "PERMISSION_REQUIRED", "PERMISSION_DENIED" -> "需要藍牙權限才能連線；請在 App 的系統設定中確認權限。"
    "BLUETOOTH_DISABLED" -> "請開啟手機藍牙，再重新連線。"
    "BOND_REQUIRED" -> "手機尚未與此車輛完成藍牙配對，請依車型說明重新配對。"
    "VEHICLE_NOT_FOUND" -> "已保存的車輛資料已變更，請回到車庫重新選擇車輛。"
    "INVALID_STATE" -> "目前狀態無法繼續連線，請回到車庫確認車輛狀態後重試。"
    "STORAGE_FAILED" -> "無法存取本機連線資料，請前往診斷與匯出查看狀態後再試。"
    "DIAGNOSTICS_UNAVAILABLE" -> "診斷記錄目前無法使用，請先前往診斷與匯出查看狀態，再重新連線。"
    "SERVICE_START_FAILED" -> "系統未能啟動車輛連線服務，請回到 App 前景後重新連線。"
    "UNSUPPORTED" -> "目前尚未支援此車輛的連線方式。"
    "FAILED_PRECONDITION" -> "無法完成車輛驗證，請確認配對狀態。"
    "TIMEOUT" -> "連線逾時，請確認車輛已開啟且在附近後重試。"
    else -> "車輛連線未完成或已中斷，請確認手機與車輛狀態後重試。"
}
