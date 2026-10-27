package org.openridesync.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.BuildConfig
import org.openridesync.app.DiagnosticsViewModel
import org.openridesync.app.DiagnosticsState
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthState
import java.text.DateFormat
import java.util.Date

/** Native diagnostics remains available without account configuration or network access. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val model: DiagnosticsViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val auth by AuthSession.get().state.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"), model::export
    )
    BackHandler(onBack = onBack)
    LaunchedEffect(model) { model.refresh() }
    fun exportSource(id: String?) {
        if (!model.prepareExport(id)) return
        try { export.launch(if (id == null) "openridesync-diagnostics.zip" else "openridesync-diagnostics-$id.zip") }
        catch (_: RuntimeException) { model.exportLaunchFailed() }
    }
    DiagnosticsContent(state, auth, onBack, model::refresh, onExport = { exportSource(null) },
        onArchive = model::archiveAndResume, onExportArchive = { exportSource(it) })
}

/** Stateless presentation: the screen owner retains lifecycle, journal and document-launch effects. */
@Composable
internal fun DiagnosticsContent(
    state: DiagnosticsState,
    auth: AuthState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onArchive: () -> Unit = {},
    onExportArchive: (String) -> Unit = {},
) {
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val available = !state.busy && !state.exportPending
    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        title = { Text("封存並繼續記錄？") },
        text = { Text("目前日誌會保留在 App 內，核對完成後開始新的工作階段。已遺失的紀錄無法補回；封存會額外占用儲存空間，且不會自動刪除。之後可分別匯出舊封存與目前日誌。") },
        confirmButton = { TextButton(onClick = { confirmArchive = false; onArchive() },
            enabled = available && state.sizeLimited, modifier = Modifier.testTag("diagnostics_archive_confirm")) { Text("封存並續錄") } },
        dismissButton = { TextButton(onClick = { confirmArchive = false },
            modifier = Modifier.testTag("diagnostics_archive_cancel")) { Text("取消") } },
    )
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("diagnostics_back")) { Text("返回") }
                Text("原生診斷", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("diagnostics_scroll"),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("讀取本機日誌，或匯出檔案供問題排查。不需要登入 Honda 帳號。")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("登入診斷", style = MaterialTheme.typography.titleMedium)
                        Text("狀態：${auth.status.name}\n結果：${auth.code}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("診斷摘要", style = MaterialTheme.typography.titleMedium)
                        Text(state.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在處理診斷資料…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Button(
                    onClick = onRefresh, enabled = available,
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics_read")
                ) { Text("讀取診斷狀態") }
                OutlinedButton(
                    onClick = onExport, enabled = available,
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics_export")
                ) { Text("匯出診斷 ZIP") }
                Text("選擇儲存位置後，會寫入驗證後的日誌及 SHA-256 清單。使用 Android 檔案選擇器，不需要儲存空間權限。", style = MaterialTheme.typography.bodyMedium)
                if (state.exportPending) Text("正在選擇匯出位置；請完成或取消檔案選擇器。")
                if (state.sizeLimited) Text("目前日誌已達容量上限，新的操作尚未記錄。封存後可繼續保存後續紀錄。")
                OutlinedButton(onClick = { confirmArchive = true }, enabled = available && state.sizeLimited,
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics_archive")) { Text("封存並繼續記錄") }
                Text("僅在日誌達到容量上限時啟用；不會變更登入、車庫或藍牙配對。", style = MaterialTheme.typography.bodySmall)
                if (state.archiveListError) Text("無法驗證封存清單，既有檔案仍保留；請重新讀取診斷狀態。")
                for (archive in state.archives) Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("封存 ${archive.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(archive.createdAtMillis)))
                        Text("${archive.bytes} bytes／${archive.rows} 筆／${archive.sessions} 個 session\n完整：${archive.complete}")
                        OutlinedButton(onClick = { onExportArchive(archive.id) }, enabled = available,
                            modifier = Modifier.fillMaxWidth().testTag("diagnostics_archive_export_${archive.id}")) { Text("匯出這份封存") }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("建置資訊", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n套件：${BuildConfig.APPLICATION_ID}\n來源 commit：${BuildConfig.SOURCE_COMMIT}\nAndroid API：${Build.VERSION.SDK_INT}\nHERE：未納入此建置", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text("日誌可驗證 App 操作與檔案完整性；不能單憑匯出成功判定 Honda 服務、通知或實車功能已通過。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
