package org.openridesync.app.calls

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.media.MediaAccessService
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

@Composable
internal fun CallsScreen(onBack: () -> Unit, onAccount: () -> Unit, model: CallsViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val listener by MediaAccessService.callSummary.collectAsState()
    val context = LocalContext.current
    var permission by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.onStart()
                Lifecycle.Event.ON_STOP -> model.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onStart()
        onDispose { owner.lifecycle.removeObserver(observer); model.onStop() }
    }
    val back = { model.onStop(); onBack() }
    BackHandler(onBack = back)
    if (permission) {
        val close = { permission = false; model.refresh() }
        PermissionDialog(listOf(AppAccess.MEDIA), onConfirm = close, onDismiss = close)
    }
    CallsContent(state, back, onAccount, model::refresh, { permission = true }, model::dispatch,
        supported = Build.VERSION.SDK_INT >= 34,
        listenerDisconnected = AppAccess.MEDIA.granted(context) && !listener.listenerConnected)
}

@Composable
internal fun CallsContent(state: CallsPageState, onBack: () -> Unit, onAccount: () -> Unit,
    onRefresh: () -> Unit, onAccess: () -> Unit, onDispatch: (String, CallAction) -> Unit,
    supported: Boolean = true, listenerDisconnected: Boolean = false) {
    var confirmation by remember { mutableStateOf<Pair<String, CallAction>?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) confirmation = null
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val entries = if (supported && state.signedIn && state.observing && !listenerDisconnected &&
        state.inbox.access == CallAccess.READY) state.inbox.calls else emptyList()
    val request = confirmation
    val selected = entries.singleOrNull { request != null && it.id == request.first && request.second in it.actions }
    LaunchedEffect(selected?.id, selected?.actions) { if (selected == null) confirmation = null }
    if (selected != null && request != null) AlertDialog(
        onDismissRequest = { confirmation = null },
        title = { Text("確認${callActionLabel(request.second)}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(selected.appName)
            Text(selected.caller)
            if (selected.isVideo) Text("這是來源 App 回報的視訊通話。")
            Text("這會執行來源通話 App 提供的${callActionLabel(request.second)}動作。請先確認目前通話對象；通知變更或離開頁面後需重新選擇。")
        } },
        confirmButton = { TextButton(onClick = {
            confirmation = null
            onDispatch(selected.id, request.second)
        }, modifier = Modifier.testTag("call_confirm")) { Text("確認${callActionLabel(request.second)}") } },
        dismissButton = { TextButton(onClick = { confirmation = null }, modifier = Modifier.testTag("call_cancel")) { Text("取消") } },
        modifier = Modifier.testTag("call_confirmation"),
    )
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(20.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("calls_back")) { Text("返回") }
            Text("通話通知", style = MaterialTheme.typography.headlineMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("calls_list")) {
                item { Text("顯示來源 App 提供的標準通話通知。接聽、拒接或結束都需要你確認；本頁不會自動處理來電。") }
                if (!state.signedIn) item {
                    Text("請先登入，再開啟通話通知功能。")
                    Button(onClick = onAccount, modifier = Modifier.testTag("calls_account")) { Text("前往帳號與登入") }
                } else if (!supported) item {
                    Text("目前通話通知控制需要 Android 14 或更新版本。請使用來源 App 處理通話。")
                } else {
                    item {
                        if (state.observing && listenerDisconnected) Text(
                            "通知存取已授權，但通知服務尚未連線。通話通知暫時不可用，請查看恢復說明。")
                        else when (state.inbox.access) {
                            CallAccess.ACCESS_REQUIRED -> Text("尚未授予通知存取，無法讀取通話通知或使用其控制動作。可先查看說明，再自行決定是否授權。")
                            CallAccess.UNAVAILABLE -> Text("目前無法取得通話通知。請確認通知存取及服務連線狀態，再重新整理。")
                            CallAccess.READY -> if (entries.isEmpty()) Text("目前沒有支援的通話通知。這不代表手機沒有通話；未提供標準通知的 App 請直接開啟處理。")
                        }
                        OutlinedButton(onClick = onAccess, modifier = Modifier.testTag("calls_access")) {
                            Text(if (listenerDisconnected) "查看連線恢復說明" else "通知存取說明與設定")
                        }
                        TextButton(onClick = onRefresh, modifier = Modifier.testTag("calls_refresh")) { Text("重新整理") }
                    }
                    state.result?.let { result -> item {
                        Text(when (result) {
                            CallDispatchResult.DISPATCHED -> "請求已交給來源 App；通話是否接通或結束，請以來源 App 與後續通知為準。"
                            CallDispatchResult.STALE -> "原通話通知已變更、移除或已操作，沒有再次送出。請重新確認目前通話。"
                            CallDispatchResult.UNSUPPORTED -> "目前通知未提供這個控制動作，請到來源 App 處理。"
                            CallDispatchResult.ACCESS_REQUIRED -> "通知存取已變更，沒有送出。請檢查系統設定。"
                            CallDispatchResult.UNAVAILABLE -> "無法確認請求已交給來源 App，請先查看實際通話狀態。"
                            CallDispatchResult.LOCKED -> "請先解鎖手機，再重新確認通話操作。"
                        }, Modifier.testTag("calls_result"))
                    } }
                    items(entries, key = { it.id }) { call ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(call.appName, style = MaterialTheme.typography.labelLarge)
                                Text(call.caller, style = MaterialTheme.typography.titleMedium)
                                Text(if (call.kind == CallKind.INCOMING) "來源 App 回報來電" else "來源 App 回報通話進行中")
                                if (call.isVideo) Text("視訊通話")
                                CallAction.entries.filter { it in call.actions }.forEach { action ->
                                    OutlinedButton(onClick = { confirmation = call.id to action },
                                        modifier = Modifier.fillMaxWidth().testTag("call_${call.id}_${action.name}")) {
                                        Text(callActionLabel(action))
                                    }
                                }
                                if (call.actions.isEmpty()) Text("目前沒有可用動作；請查看來源 App 的最新通話狀態。")
                            }
                        }
                    }
                }
                item { Text("手機頁離頁或背景後會清除該頁的通話對象與控制動作；車輛連線期間另由儀表服務在記憶體觀察標準 CallStyle 通知。SAB 提供通話頁；HU 僅以共用 Popup 提示唯一有效來電，不強制切換 SAB，也不提供進行中通話的掛斷頁。失去顯示能力、授權、可確認的儀表模式或連線後清除。Android 14 以上才支援此通知來源，不代表完整電話、聯絡人或所有通訊 App 支援。本頁不讀取聯絡人或通話紀錄；車端顯示與實體操作仍待實車驗證，撥號仍在開發中。", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

internal fun callActionLabel(action: CallAction) = when (action) {
    CallAction.ANSWER -> "接聽"
    CallAction.DECLINE -> "拒接"
    CallAction.HANG_UP -> "結束通話"
}
