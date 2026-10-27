package org.openridesync.app.pairing

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.openridesync.app.vehicles.modelName
import org.openridesync.honda.wire.HondaWire.Model
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

@Composable
internal fun PairingLaunchers(model: PairingViewModel, state: PairingState) {
    var chooserAttempt by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionAttempt by rememberSaveable { mutableStateOf<String?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        model.chooserResult(chooserAttempt, it.resultCode, it.data)
        chooserAttempt = null
    }
    LaunchedEffect(state.attempt, state.stage, state.problem, permissionAttempt) {
        if (permissionAttempt != null && permissionAttempt != state.attempt) permissionAttempt = null
        val attempt = state.attempt ?: return@LaunchedEffect
        if (permissionAttempt == null && model.takePermissionRequest(attempt)) {
            permissionAttempt = attempt
        }
    }
    if (permissionAttempt != null && state.stage == PairingStage.PERMISSION_REQUIRED && permissionAttempt == state.attempt) {
        PermissionDialog(listOf(AppAccess.PAIRING), required = setOf(AppAccess.PAIRING), confirmLabel = "開始搜尋",
            onConfirm = { model.permissionsResult(permissionAttempt); permissionAttempt = null },
            onDismiss = { permissionAttempt = null; model.cancel() })
    }
    LaunchedEffect(state.attempt, state.chooserReady) {
        val attempt = state.attempt ?: return@LaunchedEffect
        model.takeChooser(attempt)?.let {
            chooserAttempt = attempt
            try { chooser.launch(IntentSenderRequest.Builder(it).build()) }
            catch (_: RuntimeException) { model.chooserLaunchFailed(attempt) }
        }
    }
}

@Composable
internal fun PairingScreen(model: PairingViewModel, state: PairingState, onPaired: () -> Unit) {
    PairingLifecycle(model::onStart, model::onStop)
    val back = { model.clear(); if (state.stage == PairingStage.PAIRED) onPaired() }
    BackHandler(onBack = back)
    PairingContent(state, back, model::cancel, model::retry, model::chooseDevice,
        model::requestPermissionsAgain, model::selectModel, onDone = back)
}

/** Pairing is available without a downloaded/rendered guide. The user starts scanning explicitly. */
@Composable
internal fun PairingEntry(generation: Int, enabled: Boolean, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("請先停妥車輛。點選「開始配對」後，在搜尋期間重設車端藍牙配對，再選擇你的 HONDA BTU 裝置。")
        Button(onClick = onStart, enabled = enabled && pairingSupported(generation),
            modifier = Modifier.fillMaxWidth().testTag("vehicle_start_pairing")) { Text("開始配對") }
        if (!pairingSupported(generation)) Text("此車型的儀表世代尚未支援配對連線。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PairingLifecycle(onStart: () -> Unit, onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
}

@Composable
internal fun PairingContent(
    state: PairingState, onBack: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit,
    onChoose: () -> Unit, onPermissions: () -> Unit, onModel: (Model) -> Unit,
    onDone: () -> Unit = onBack,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("pairing_back")) { Text(if (state.stage == PairingStage.PAIRED) "返回車庫" else "返回車型資訊") }
            if (state.stage == PairingStage.CHECKING) {
                Text("返回車型資訊會停止本次搜尋。", style = MaterialTheme.typography.bodySmall)
            }
            Text("車輛配對", style = MaterialTheme.typography.headlineMedium)
            Text(when (state.stage) {
                PairingStage.PERMISSION_REQUIRED -> "需要授予藍牙偵測所需的系統權限，才能偵測並連接你選擇的摩托車。"
                PairingStage.CHECKING -> "請在車輛端重設藍牙配對。正在搜尋處於配對模式的車輛…"
                PairingStage.CHECKED -> "已偵測到可配對的 Honda 車輛廣播。請點選按鈕，並選擇你車上以「HONDA BTU」開頭的裝置。"
                PairingStage.CHOOSING -> "請在 Android 裝置選擇畫面選擇你的摩托車。"
                PairingStage.BONDING -> "正在與選定的摩托車建立藍牙配對…"
                PairingStage.READING -> "正在讀取摩托車資訊…"
                PairingStage.RESOLVING -> "正在取得此摩托車的 Honda 車型資料…"
                PairingStage.MODEL_CHOICE -> "Honda 回傳多個可能車型，請選擇你的實際型號。"
                PairingStage.SAVING -> "正在保存已配對車輛…"
                PairingStage.PAIRED -> "已保存配對車輛：${state.vehicleName.orEmpty()}"
                PairingStage.CANCELLED -> "已取消這次配對。"
                PairingStage.FAILED -> "配對未完成，請確認車端配對模式及手機狀態後重試。"
                PairingStage.IDLE -> "尚未開始配對。"
            }, Modifier.testTag("pairing_status"))
            if (state.stage in setOf(PairingStage.CHECKING, PairingStage.CHOOSING, PairingStage.BONDING,
                    PairingStage.READING, PairingStage.RESOLVING, PairingStage.SAVING)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("pairing_cancel")) { Text("取消配對") }
            }
            when (state.stage) {
                PairingStage.CHECKED -> Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().testTag("pairing_choose")) { Text("選擇裝置") }
                PairingStage.PERMISSION_REQUIRED -> OutlinedButton(onClick = onPermissions, modifier = Modifier.fillMaxWidth()) { Text("授予所需權限") }
                PairingStage.MODEL_CHOICE -> state.candidates.forEach { candidate ->
                    OutlinedButton(onClick = { onModel(candidate) }, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(modelName(candidate))
                            Text(candidate.modelYearsList.joinToString("、").ifBlank { "年份未提供" }, style = MaterialTheme.typography.bodySmall)
                            Text("型號：${candidate.modelLabel}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                PairingStage.FAILED, PairingStage.CANCELLED -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("pairing_retry")) { Text("重新開始") }
                PairingStage.PAIRED -> Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("pairing_garage")) { Text("前往車庫連線") }
                else -> Unit
            }
            if (state.problem != null) Text("狀態：${state.problem}", style = MaterialTheme.typography.bodySmall)
            if (state.problem == "BLUETOOTH_DISABLED") Text("請先在手機設定啟用藍牙，再重新開始。")
            if (state.problem == "LOCATION_DISABLED") Text("請先在手機設定開啟定位服務，再重新開始。")
            if (state.problem == "PERMISSION_REQUIRED") Text("若系統不再顯示權限詢問，請在此 App 的系統設定中授予所需權限。")
            if (state.problem == "STORAGE_FAILED") Text("無法保存車輛資料，請確認手機儲存空間後重試。")
            if (state.problem == "OPERATION_BUSY") Text("請先中斷目前的車輛連線，或等前一次操作結束，再重新開始配對。")
            if (state.problem == "UNSUPPORTED") Text("此車型或手機目前不支援這個配對流程。")
            if (state.stage == PairingStage.PAIRED) Text("車輛已配對，可返回車庫手動連線；騎乘功能尚未完整啟用。", Modifier.testTag("pairing_not_ready"))
            Text("請在車輛停止時，依照已閱讀的車型指引操作。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
