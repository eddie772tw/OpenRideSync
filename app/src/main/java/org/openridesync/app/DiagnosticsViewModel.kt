package org.openridesync.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openridesync.diagnostics.DiagnosticExport
import org.openridesync.diagnostics.DiagnosticReader
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.io.File

data class DiagnosticArchiveSummary(val id: String, val bytes: Long, val createdAtMillis: Long,
    val rows: Long, val sessions: Long, val complete: Boolean)
data class DiagnosticsState(val busy: Boolean = false, val message: String = "尚未讀取診斷狀態",
    val sizeLimited: Boolean = false, val archives: List<DiagnosticArchiveSummary> = emptyList(),
    val archiveListError: Boolean = false, val exportPending: Boolean = false)

class DiagnosticsViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as OpenRideSyncApplication
    private val mutableState = MutableStateFlow(DiagnosticsState(exportPending = savedState.get<String>(EXPORT_TARGET) != null))
    val state = mutableState.asStateFlow()

    fun refresh() = runOperation(Event.REQUEST) { _ ->
        val journal = checkNotNull(app.journal)
        val snapshot = File.createTempFile("diagnostics-read-", ".tsv", app.cacheDir)
        val read = try {
            journal.snapshot(snapshot, 5000)
            DiagnosticReader.read(snapshot)
        } finally { snapshot.delete() }
        val status = journal.status()
        "原生日誌狀態\n已驗證快照 ${read.rows} 筆／${read.sessions} 個 session；完整：${read.complete}\n已接受 ${status.accepted} 筆；已寫入 ${status.written} 筆\n丟棄 ${status.dropped} 筆；寫入失敗 ${status.writeFailures} 次\n容量限制：${status.sizeLimited}；已關閉：${status.closed}\n此紀錄包含 App 操作，不能作為實車功能成功證據。"
    }

    fun archiveAndResume() = runOperation(Event.ARCHIVE) { _ ->
        val archive = checkNotNull(app.journal).archiveAndResume()
        "已保存封存 ${archive.id.take(8)}，並開始新的診斷工作階段。舊紀錄的缺漏仍保留；可從下方分別匯出封存與目前日誌。"
    }

    /** Bind the source before opening SAF; rotation/process restoration cannot select a different archive. */
    fun prepareExport(archiveId: String? = null): Boolean {
        if (state.value.busy || state.value.exportPending ||
            (archiveId != null && state.value.archives.none { it.id == archiveId })) return false
        savedState[EXPORT_TARGET] = archiveId ?: ACTIVE
        mutableState.value = state.value.copy(exportPending = true)
        return true
    }

    fun exportLaunchFailed() {
        savedState.remove<String>(EXPORT_TARGET)
        app.journal?.record(Component.UI, null, Event.EXPORT, Outcome.FAILED, ErrorCode.UNAVAILABLE)
        mutableState.value = state.value.copy(exportPending = false, message = "無法開啟檔案選擇器，請稍後重試。日誌仍保留在 App 內。")
    }

    fun export(uri: Uri?) {
        val target = savedState.remove<String>(EXPORT_TARGET) ?: return
        mutableState.value = state.value.copy(exportPending = false)
        if (uri == null) {
            app.journal?.record(Component.UI, null, Event.EXPORT, Outcome.CANCELLED, ErrorCode.CANCELLED)
            mutableState.value = mutableState.value.copy(message = "已取消匯出")
            return
        }
        runOperation(Event.EXPORT) { _ ->
            val zip = File.createTempFile("diagnostics-", ".zip", app.cacheDir)
            try {
                val journal = checkNotNull(app.journal)
                if (target == ACTIVE) DiagnosticExport.export(journal, zip, app.packageName,
                    BuildConfig.VERSION_CODE.toLong(), BuildConfig.SOURCE_COMMIT, android.os.Build.VERSION.SDK_INT)
                else DiagnosticExport.exportArchive(journal, target, zip, app.packageName,
                    BuildConfig.VERSION_CODE.toLong(), BuildConfig.SOURCE_COMMIT, android.os.Build.VERSION.SDK_INT)
                app.contentResolver.openOutputStream(uri, "wt").use { output ->
                    checkNotNull(output) { "destination unavailable" }
                    zip.inputStream().use { it.copyTo(output) }
                }
                "${if (target == ACTIVE) "目前日誌" else "封存 ${target.take(8)}"} ZIP 已寫入選定位置；內含驗證後的日誌與 SHA-256 清單。"
            } finally { zip.delete() }
        }
    }

    private fun runOperation(event: Event, action: (String?) -> String) {
        if (mutableState.value.busy || mutableState.value.exportPending) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val journal = app.journal
                val operation = journal?.newOperation()
                journal?.record(Component.UI, operation, event, Outcome.STARTED, ErrorCode.NONE)
                val result = try { Result.success(action(operation)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { Result.failure(failure) }
                result.also {
                    journal?.record(Component.UI, operation, event,
                        if (it.isSuccess) Outcome.SUCCEEDED else Outcome.FAILED,
                        if (it.isSuccess) ErrorCode.NONE else ErrorCode.IO)
                }
                // Capacity remains actionable even when a full checkpoint reserve prevents snapshot/export.
                val archives = runCatching { checkNotNull(journal).archives().map {
                    DiagnosticArchiveSummary(it.id, it.bytes, it.createdAtMillis, it.rows, it.sessions, it.complete)
                } }
                DiagnosticsState(message = result.getOrElse {
                    "診斷操作未完成。請重新讀取狀態或另選匯出位置；若已達容量上限，可使用「封存並繼續記錄」。既有檔案不會自動刪除。"
                }, sizeLimited = journal?.status()?.sizeLimited == true,
                    archives = archives.getOrDefault(emptyList()), archiveListError = archives.isFailure)
            }
            // Do not show exception messages: SDK/provider errors can contain private data.
            mutableState.value = result
        }
    }

    private companion object {
        const val EXPORT_TARGET = "diagnostics_export_target"
        const val ACTIVE = "active"
    }
}
