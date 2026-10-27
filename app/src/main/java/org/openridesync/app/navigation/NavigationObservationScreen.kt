package org.openridesync.app.navigation

import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

/** Read-only developer entry. No saved route, destination, map launch, speech or vehicle transport. */
@Composable
internal fun NavigationObservationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenRideSyncApplication
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val auth by AuthSession.get().state.collectAsState()
    var observation by remember { mutableStateOf(NavigationObservation()) }
    var backend by remember { mutableStateOf<NativeNavigation?>(null) }
    var access by remember { mutableStateOf(false) }
    // Account changes invalidate even a read-only page's in-memory route snapshot.
    DisposableEffect(owner, auth.revision) {
        var collecting: Job? = null
        fun stop() {
            collecting?.cancel(); collecting = null
            backend?.close(); backend = null
            observation = NavigationObservation()
        }
        fun start() {
            if (backend != null) return
            val source = NativeNavigation(app, app.journal)
            backend = source
            collecting = scope.launch { source.state.collect { observation = it } }
            source.start()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start()
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    BackHandler(onBack = onBack)
    if (access) PermissionDialog(listOf(AppAccess.MEDIA),
        onConfirm = { access = false; backend?.refresh() }, onDismiss = { access = false; backend?.refresh() })
    NavigationObservationContent(observation, onBack, { backend?.refresh() }, { access = true })
}

@Composable
internal fun NavigationObservationContent(state: NavigationObservation, onBack: () -> Unit,
    onRefresh: () -> Unit, onAccess: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("navigation_observation"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("Google Maps 導航資料觀察", style = MaterialTheme.typography.titleLarge)
            Text("此開發頁只讀取 Google Maps 正在顯示的導航通知。請先在 Maps 開始你選擇的路線，再返回查看；本頁不啟動或停止路線、不播報，也不送往車輛。")
            Text(when (state.status) {
                NavigationStatus.LOADING -> "正在讀取通知與圖示範本…"
                NavigationStatus.ACCESS_REQUIRED -> "需要通知存取權才能讀取 Maps 導航資料。拒絕不影響帳號與診斷。"
                NavigationStatus.LISTENER_UNAVAILABLE -> "通知監聽尚未連線；若已授權，可重新檢查或查看系統背景執行設定。"
                NavigationStatus.MAPS_UNAVAILABLE -> "找不到已安裝的 Google Maps。"
                NavigationStatus.WAITING -> "尚未觀察到 Google Maps 的持續導航通知。"
                NavigationStatus.OBSERVED -> "已讀到 Google Maps 導航通知；不代表 App 已建立路線或車端已顯示。"
                NavigationStatus.AMBIGUOUS -> "同時出現多筆導航通知，暫不選定其中一筆。"
                NavigationStatus.UNSUPPORTED -> "目前通知格式或圖示資源無法解析。"
            }, Modifier.testTag("navigation_observation_status"))
            Text("可用圖示範本：${state.templates}/${GoogleMapsManeuvers.expectedTemplateCount}")
            if (state.templates in 1 until GoogleMapsManeuvers.expectedTemplateCount) {
                Text("Maps 圖示資源不完整，僅辨識唯一且完全相符的已知圖示；其他轉向維持未知。")
            }
            state.instruction?.let { value ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(value.instruction, Modifier.testTag("navigation_observation_instruction"))
                        value.nextStep?.let { Text("下一轉彎距離：$it") }
                        value.remaining?.let { Text("Maps 行程摘要：$it") }
                        Text(value.maneuver?.let { "辨識轉向：${it.name}" } ?: "轉向圖示未知；不猜測方向。")
                    }
                }
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("navigation_observation_refresh")) { Text("重新檢查") }
            TextButton(onClick = onAccess) { Text("通知存取與功能說明") }
            Text("通知文字只在本頁使用，離頁或進入背景就清除，不保存地址或路線至診斷。正式儀表導航仍待整合與驗證。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
