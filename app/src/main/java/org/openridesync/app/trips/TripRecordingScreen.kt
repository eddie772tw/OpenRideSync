package org.openridesync.app.trips

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openridesync.app.OpenRideSyncApplication

/** Opening/resuming only refreshes local queue state; collection and upload remain model-owned. */
@Composable
internal fun TripRecordingScreen(onBack: () -> Unit, onPermissions: () -> Unit, onAccount: () -> Unit) {
    val model = (LocalContext.current.applicationContext as OpenRideSyncApplication).tripRecording
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val recoveringEmptyQueue = state.ambiguous && state.pending == 0
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) model.refresh() }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.refresh()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    TripRecordingContent(state, onBack, onPermissions, onAccount, { model.setEnabled(it) },
        { model.upload(emptyOnly = recoveringEmptyQueue) }, model::cancelUpload, model::retryRecording)
}

/** Pure controlled presentation: rendering, recomposition and scrolling dispatch no work. */
@Composable
internal fun TripRecordingContent(
    state: TripRecordingState,
    onBack: () -> Unit,
    onPermissions: () -> Unit,
    onAccount: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onUpload: () -> Unit,
    onCancelUpload: () -> Unit,
    onRetryRecording: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp).testTag("trip_recording_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("trip_recording_back")) { Text("返回") }
            Text("旅程紀錄與上傳", style = MaterialTheme.typography.headlineMedium)
            Text("此帳號的紀錄設定預設關閉。啟用後，只有登入且完成驗證的實際車輛連線期間，才會依定位與前景服務條件收集手機位置、時間與車型資訊，並透過 Honda 服務同步旅程資料。")
            Text("如果定位前景服務已在 App 前景成功啟動，鎖定手機後仍可能繼續紀錄。你可在系統權限中選擇概略或精確位置；概略位置的路線與距離精度可能較低。")
            if (!state.signedIn) {
                Text("請先登入，再選擇是否啟用此帳號的旅程紀錄。", modifier = Modifier.testTag("trip_recording_signed_out"))
                Button(onClick = onAccount, modifier = Modifier.testTag("trip_recording_account")) { Text("前往帳號") }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("同意啟用此帳號的位置紀錄與自動上傳", Modifier.weight(1f))
                    Switch(checked = state.signedIn && state.enabled, enabled = state.signedIn,
                        onCheckedChange = onEnabled, modifier = Modifier.testTag("trip_recording_enabled")
                            .semantics { contentDescription = "同意啟用此帳號的位置紀錄與自動上傳" })
                }
            }
            Text("關閉會停止收集新的位置與自動上傳，已待上傳的資料仍保留，不會因此刪除。你仍可登入同一帳號後明確選擇上傳保留的資料。")
            Text(when {
                !state.signedIn -> "尚未登入，未收集旅程位置。"
                !state.enabled && state.recording -> "已停用，正在停止定位紀錄。"
                state.recording -> "正在紀錄實際車輛連線的旅程位置。"
                state.enabled -> "已啟用，尚未開始紀錄；請確認車輛連線與定位條件。"
                else -> "紀錄已停用，未收集新的旅程位置。"
            }, modifier = Modifier.testTag("trip_recording_status"), style = MaterialTheme.typography.titleMedium)
            Text("紀錄需要定位授權、已開啟的系統定位，以及可用的位置前景服務。開啟此頁不會要求權限、建立車輛連線或開始定位。")
            OutlinedButton(onClick = onPermissions, modifier = Modifier.testTag("trip_recording_permissions")) { Text("查看定位與前景服務權限") }
            OutlinedButton(onClick = onRetryRecording, enabled = state.signedIn && state.enabled && !state.recording,
                modifier = Modifier.testTag("trip_recording_retry")) { Text("重新檢查紀錄條件") }
            if (state.backgroundScheduleFailed) {
                Text("系統目前無法排定背景上傳。已保存的資料仍保留，你可稍後重新開啟此頁檢查，或手動上傳待處理資料。",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("trip_recording_schedule_failed"))
            }
            if (state.errorCode != null) {
                Text(when (state.errorCode) {
                    "LOCATION_PERMISSION" -> "尚未授權定位，無法收集旅程位置。請在權限頁選擇概略或精確位置。"
                    "LOCATION_DISABLED" -> "系統定位服務已關閉，開啟後可重新檢查紀錄條件。"
                    "FOREGROUND_LOCATION_REQUIRED" -> "位置前景服務尚未就緒，請在 App 前景確認定位授權與服務條件。"
                    "LOCATION_UNAVAILABLE" -> "目前無法取得位置，請檢查系統定位後重試。"
                    "STORAGE_FAILED" -> "無法讀寫本機旅程資料，操作尚未完成。"
                    "INVALID_LOCATION" -> "此次位置資料無法使用，尚未加入旅程紀錄。"
                    "AUTH_REQUIRED", "UNAUTHENTICATED", "UNAUTHORIZED" -> "帳號授權需要更新，請前往帳號頁確認。"
                    "UPLOAD_UNCONFIRMED" -> "上次上傳的結果仍待確認，請查看下方說明。"
                    "QUEUE_CHANGED" -> "佇列已有待上傳資料，請確認筆數後再明確選擇是否上傳。"
                    "CANCELLED" -> "已取消此次操作，待上傳資料仍保留。"
                    else -> "暫時無法完成操作，請確認連線或定位條件後明確重試。"
                }, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("trip_recording_error"))
                if (state.signedIn && state.errorCode in setOf("AUTH_REQUIRED", "UNAUTHENTICATED", "UNAUTHORIZED")) {
                    TextButton(onClick = onAccount, modifier = Modifier.testTag("trip_recording_account")) { Text("前往帳號確認授權") }
                }
            }
            HorizontalDivider()
            Text(if (state.signedIn) "待上傳定位紀錄：${state.pending.coerceAtLeast(0)} 筆" else "登入後可查看此帳號的待上傳資料。",
                modifier = Modifier.testTag("trip_recording_pending"), style = MaterialTheme.typography.titleMedium)
            Text("本機待傳資料與 Honda 騎乘紀錄分開顯示。完成上傳後，可回到騎乘紀錄查看 Honda 提供的結果。")
            if (state.signedIn && state.ambiguous && !state.uploading) {
                Card(Modifier.fillMaxWidth().testTag("trip_recording_ambiguous")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("上次上傳結果未確認", style = MaterialTheme.typography.titleMedium)
                        Text(if (state.pending == 0) "本機目前沒有待上傳資料，但上次操作的標記尚未清除。可明確重新檢查本機佇列並清除標記；不會產生新的定位或上傳空資料。"
                            else "資料可能已送出，但尚未確認服務是否接受。本機保留原資料與相同的紀錄識別，只有你明確重試才會再次送出。相同識別不保證服務端會去除重複資料，重試可能再次傳送同一批紀錄。")
                    }
                }
            }
            if (state.uploading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在上傳此帳號的待處理資料。取消可能發生在資料送出之後，未確認的結果仍需另行處理。")
                OutlinedButton(onClick = onCancelUpload, enabled = state.signedIn,
                    modifier = Modifier.testTag("trip_recording_cancel_upload")) { Text("取消此次上傳") }
            }
            Button(onClick = onUpload, enabled = state.signedIn && (state.pending > 0 || state.ambiguous) && !state.uploading,
                modifier = Modifier.fillMaxWidth().testTag("trip_recording_upload")) {
                Text(if (state.ambiguous && state.pending == 0) "檢查佇列並清除未確認標記"
                    else if (state.ambiguous) "明確重試上傳待確認資料" else "上傳此帳號的待處理資料")
            }
        }
    }
}
