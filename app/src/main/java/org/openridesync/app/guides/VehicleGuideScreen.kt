package org.openridesync.app.guides

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openridesync.app.vehicles.VehicleState
import org.openridesync.app.vehicles.VehicleViewModel
import org.openridesync.app.vehicles.modelName
import org.openridesync.app.pairing.PairingEntry
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode

@Composable
internal fun VehicleGuideScreen(model: VehicleViewModel, state: VehicleState, onBack: () -> Unit, onStartPairing: (() -> Unit)? = null, onAccount: () -> Unit = {}) {
    val guide = state.guide ?: return
    var rendered by remember(guide.operationId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(guide.operationId) {
        if (guide.document != null) {
            delay(30_000)
            if (rendered == null) {
                rendered = false
                model.guideRendered(guide.operationId, false, ErrorCode.TIMEOUT)
            }
        }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("guide_back")) { Text("返回") }
                Text(if (guide.kind == GuideKind.SETUP) "車輛設定說明" else "使用教學預覽",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = model::refresh, enabled = !state.busy) { Text("重新載入") }
            }
            state.detail?.let { Text(modelName(it), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium) }
            if (guide.kind == GuideKind.TUTORIAL) {
                Text("完成此教學預覽後返回車型資訊。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            when {
                state.errorCode != null || rendered == false -> {
                    Text(if (state.errorCode in setOf("AUTH_REQUIRED", "UNAUTHENTICATED")) "帳號需要重新登入，請返回帳號頁。"
                        else "暫時無法顯示此車型的原廠說明，請重新載入或稍後再試。",
                        Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.errorCode in setOf("AUTH_REQUIRED", "UNAUTHENTICATED"))
                        TextButton(onClick = onAccount, modifier = Modifier.padding(horizontal = 20.dp)) { Text("前往帳號") }
                }
                guide.document != null -> {
                    if (rendered == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    key(guide.operationId) {
                        GuideWebView(guide.document, Modifier.fillMaxWidth().weight(1f).testTag("guide_webview"),
                            onLoaded = {
                                if (rendered == null) { rendered = true; model.guideRendered(guide.operationId, true) }
                            },
                            onError = { rendered = false; model.guideRendered(guide.operationId, false, it.diagnostic) },
                            onFinish = if (guide.kind == GuideKind.TUTORIAL) ({ model.finishGuide(guide.operationId) }) else null)
                    }
                }
                state.busy -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在取得原廠說明…", Modifier.padding(20.dp))
                    TextButton(onClick = model::cancel) { Text("取消讀取") }
                }
                else -> Text("已取消載入，可重新載入說明。", Modifier.padding(20.dp))
            }
            if (guide.kind == GuideKind.SETUP && onStartPairing != null) {
                Column(Modifier.padding(16.dp)) {
                    PairingEntry(state.detail?.meter?.generation ?: 0, !state.garageMutationBusy && !state.garageMutationError && !state.garageLoading, onStartPairing)
                }
            }
        }
    }
}
