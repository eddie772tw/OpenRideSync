package org.openridesync.app.runtime

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openridesync.app.BuildConfig
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.diagnostics.DiagnosticJournal.*

/** The saved route owns observation, outside the content replaced by the global HU lock. */
@Composable
internal fun HandlebarTestLifetime(onTestRoute: Boolean, onStop: () -> Unit) {
    if (!onTestRoute) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
}

@Composable
internal fun HandlebarTestScreen(onBack: () -> Unit) {
    if (!BuildConfig.DEBUG) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Text("此測試僅供開發版本使用。")
            TextButton(onClick = onBack) { Text("返回") }
        }
        return
    }
    val app = LocalContext.current.applicationContext as OpenRideSyncApplication
    val connection = app.ride
    val ride by connection.state.collectAsStateWithLifecycle()
    val page by connection.musicPage.collectAsStateWithLifecycle()
    val result by connection.handlebarTest.collectAsStateWithLifecycle()
    fun back() { connection.stopHandlebarTest(); onBack() }
    BackHandler { back() }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) message = "已取消匯出，結果仍保留在本頁。"
        else {
            val snapshot = connection.handlebarTest.value
            exporting = true
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        app.contentResolver.openOutputStream(uri, "wt").use { output ->
                            checkNotNull(output).write(snapshot.toJson(BuildConfig.SOURCE_COMMIT,
                                BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME).toByteArray(Charsets.UTF_8))
                        }
                    }.isSuccess
                }
                app.journal?.record(Component.UI, snapshot.operation, Event.EXPORT,
                    if (success) Outcome.SUCCEEDED else Outcome.FAILED, if (success) ErrorCode.NONE else ErrorCode.IO)
                message = if (success) "逐步觀察 JSON 已匯出；僅證明收到功能事件，不代表車端功能或媒體動作成功。"
                    else "無法寫入選定位置；結果仍保留，請另選位置重試。"
                exporting = false
            }
        }
    }
    val connected = ride.stage == RideStage.CONNECTED
    val canObserve = connected && result.id != null && result.closed == null && result.activeRound == null
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("實車按鍵引導測試", style = MaterialTheme.typography.headlineMedium)
            Text("請停妥車輛，再以車把實體按鍵操作。選擇一個步驟並開始觀察，完成該次短按／長按後結束；可跳過或重試。每個步驟可保留 4 輪。")
            Text("本頁只旁聽目前連線的真實 Gen1 Function notification，不會送出按鍵或建立另一條連線。原有頁面、音樂與音量功能仍照常處理，可能切頁、播放或改變音量。")
            Text("已開始的觀察會在儀表模式鎖定期間繼續記錄，切回 RoadSync 後可查看；離開此頁、App 進入背景或連線結束時會停止。")
            Text("不預設手勢與功能一對一對應；同一按法可能依模式與頁面改變。VOLUME_UP 0x05／VOLUME_DOWN 0x06 也可能由獨立 HID 路徑處理，本頁無法觀察 HID。沒有事件只表示未觀察到，不能判定不存在、不支援或通過。")
            Text("連線：${ride.stage.name}｜模式：${ride.mode?.name ?: "未知"}｜App 頁面：${page.stage.name}",
                Modifier.testTag("handlebar_connection"))
            if (!connected) Text("請先從車庫建立真實儀表連線；CONNECTED 且帳號與連線仍有效時才能建立測試。")
            Text("模式記錄於 monitor 接受事件時；頁面是 observer 收到時的 App 狀態，可能已由原功能切換。恢復流程可能吞掉一次 MODE_HU，未知功能也不會由 monitor 發出。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                message = if (connection.createHandlebarTest()) "已建立測試。請選擇第一個實體操作。" else "連線或帳號已變更，無法開始。"
            }, enabled = connected && result.activeRound == null && !exporting,
                modifier = Modifier.testTag("handlebar_create")) {
                Text(if (result.id == null) "建立實車測試" else "清除本頁結果並建立新測試")
            }
            result.id?.let { Text("測試：$it\n狀態：${result.closed?.let(::endLabel) ?: "進行中"}", style = MaterialTheme.typography.bodySmall) }
            HandlebarGesture.entries.forEach { gesture ->
                val rounds = result.rounds.filter { it.gesture == gesture }
                val active = result.activeRound?.let { result.rounds[it].gesture == gesture } == true
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(gesture.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (active) "正在觀察：請操作車把上的${gesture.label}，然後結束。" else
                            "已記錄 ${rounds.size} 輪；沒有預設對應功能。")
                        if (active) {
                            Button(onClick = { connection.endHandlebarStep() }, modifier = Modifier.testTag("handlebar_end")) { Text("結束本輪觀察") }
                            TextButton(onClick = { connection.endHandlebarStep(HandlebarEnd.SKIPPED) }) { Text("停止並標記跳過") }
                        } else {
                            OutlinedButton(onClick = {
                                if (!connection.startHandlebarStep(gesture)) message = "無法開始：連線／帳號已變更，或本手勢已達 4 輪上限。"
                            }, enabled = canObserve && rounds.size < HandlebarTest.MAX_ROUNDS_PER_GESTURE && !exporting,
                                modifier = Modifier.testTag("handlebar_start_${gesture.name}")) { Text(if (rounds.isEmpty()) "開始觀察" else "重試・開始新一輪") }
                            TextButton(onClick = {
                                if (connection.startHandlebarStep(gesture)) connection.endHandlebarStep(HandlebarEnd.SKIPPED)
                            }, enabled = canObserve && rounds.size < HandlebarTest.MAX_ROUNDS_PER_GESTURE && !exporting) { Text("跳過此步驟") }
                        }
                        rounds.forEachIndexed { index, round ->
                            Text("第 ${index + 1} 輪｜${round.end?.let(::endLabel) ?: "觀察中"}｜+${round.startedMs} ms\n" +
                                "開始模式 ${round.startContext.mode?.name ?: "未知"}／頁面 ${round.startContext.page.name}", style = MaterialTheme.typography.bodySmall)
                            Text(if (round.counts.isEmpty()) "未觀察到功能事件" else round.counts.entries.joinToString("\n") {
                                "${HandlebarTest.label(it.key)}：${it.value} 次"
                            }, Modifier.testTag("handlebar_counts_${gesture.name}_$index"))
                            if (round.lostEvents > 0 || round.omittedRows > 0) Text(
                                "資料已截斷：${round.lostEvents} 筆未捕捉；${round.omittedRows} 筆明細因容量省略（已收到的次數仍累計）。不能據此判定完整涵蓋。")
                            var expanded by remember(result.id, gesture, index) { mutableStateOf(false) }
                            if (round.events.isNotEmpty()) {
                                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收合事件明細" else "顯示事件明細（功能／數值／時間／模式／頁面）") }
                                if (expanded) Text(round.events.joinToString("\n") { event ->
                                    "${HandlebarTest.label(event.function)}｜+${event.elapsedMs} ms｜#${event.sequence}\n" +
                                        "模式 ${event.mode?.name ?: "未知"}（rev ${event.modeRevision}）／${event.page.name}"
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("8 個功能事件覆蓋（只計已開始觀察的步驟）", style = MaterialTheme.typography.titleMedium)
            HandlebarTest.FUNCTIONS.forEach { function ->
                val count = result.count(function)
                Text("${HandlebarTest.label(function)}：${if (count == 0L) "未觀察" else "已觀察 $count 次"}",
                    Modifier.testTag("handlebar_coverage_${function.name}"))
            }
            Text("模式事件（不計入 8 個功能）", style = MaterialTheme.typography.titleMedium)
            HandlebarTest.MODES.forEach { Text("${HandlebarTest.label(it)}：${result.count(it)} 次") }
            Text("結果只保留於本次 App 執行期間，返回後可再看；清除或關閉 App 前請先匯出。匯出含逐步明細、缺口與截斷標記，不含 MAC、帳號、權杖、曲名或通知正文。")
            OutlinedButton(onClick = { connection.stopHandlebarTest(HandlebarEnd.FINISHED) },
                enabled = result.id != null && result.closed == null && !exporting) { Text("結束整個測試並保留結果") }
            OutlinedButton(onClick = { connection.stopHandlebarTest(HandlebarEnd.CANCELLED) },
                enabled = result.id != null && result.closed == null && !exporting) { Text("取消觀察並保留結果") }
            Button(onClick = {
                connection.stopHandlebarTest(HandlebarEnd.FINISHED)
                export.launch("openridesync-handlebar-test.json")
            }, enabled = result.id != null && !exporting, modifier = Modifier.testTag("handlebar_export")) { Text("結束觀察並匯出逐步 JSON") }
            message?.let { Text(it, Modifier.testTag("handlebar_message")) }
            TextButton(onClick = { back() }) { Text("返回帳號頁") }
        }
    }
}

private fun endLabel(reason: HandlebarEnd) = when (reason) {
    HandlebarEnd.ENDED -> "已結束觀察"
    HandlebarEnd.SKIPPED -> "已跳過"
    HandlebarEnd.CANCELLED -> "已取消"
    HandlebarEnd.SCREEN_LEFT -> "離頁已停止"
    HandlebarEnd.CONNECTION_ENDED -> "連線或帳號變更已停止"
    HandlebarEnd.FINISHED -> "測試已結束"
}
