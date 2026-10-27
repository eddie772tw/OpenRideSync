package org.openridesync.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

@Composable
internal fun NavigationScreen(onBack: () -> Unit, onAccount: () -> Unit, model: NavigationViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(false) }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.onStart()
            if (event == Lifecycle.Event.ON_STOP) model.onStop()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onStart()
        onDispose { owner.lifecycle.removeObserver(observer); model.onStop() }
    }
    val back = { model.onStop(); onBack() }
    BackHandler(onBack = back)
    if (access) PermissionDialog(listOf(AppAccess.MEDIA),
        onConfirm = { access = false; model.refresh() }, onDismiss = { access = false; model.refresh() })
    NavigationContent(state, back, onAccount, model::refresh, { access = true },
        model::launch, model::repeat, model::cancel, model::stopSpeech)
}

@Composable
internal fun NavigationContent(state: NavigationPageState, onBack: () -> Unit, onAccount: () -> Unit,
    onRefresh: () -> Unit, onAccess: () -> Unit, onLaunch: (String) -> Unit,
    onRepeat: (String) -> Unit, onCancel: (String) -> Unit, onStopSpeech: () -> Unit) {
    // Deliberately not saveable: destinations and confirmations disappear on background/account loss.
    var query by remember { mutableStateOf("") }
    var launchQuery by remember { mutableStateOf<String?>(null) }
    var cancelId by remember { mutableStateOf<String?>(null) }
    var foreground by remember { mutableStateOf(true) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false; query = ""; launchQuery = null; cancelId = null
            }
            if (event == Lifecycle.Event.ON_START) foreground = true
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.signedIn, state.authRevision) {
        query = ""; launchQuery = null; cancelId = null
    }
    val active = state.signedIn && foreground
    val observed = state.observation.takeIf { active && it.status == NavigationStatus.OBSERVED }
    val sourceId = observed?.sourceId
    val cancellation = cancelId?.takeIf { it == sourceId && observed?.canCancel == true }
    LaunchedEffect(cancellation) { if (cancellation == null) cancelId = null }
    if (launchQuery != null && active) AlertDialog(
        onDismissRequest = { launchQuery = null }, title = { Text("在 Google Maps 開始導航？") },
        text = { Text("目的地：$launchQuery\nMaps 將使用自己的定位與路線設定，可能取代目前路線。送出後請在 Maps 確認；返回本頁才會重新讀取導航通知。") },
        confirmButton = { TextButton(onClick = {
            val value = launchQuery; launchQuery = null; query = ""
            if (value != null) onLaunch(value)
        }, Modifier.testTag("navigation_launch_confirm")) { Text("開啟 Maps") } },
        dismissButton = { TextButton(onClick = { launchQuery = null }, Modifier.testTag("navigation_dismiss")) { Text("取消") } })
    if (cancellation != null) AlertDialog(onDismissRequest = { cancelId = null },
        title = { Text("要求 Maps 結束導航？") },
        text = { Text("目前指示：${observed?.instruction?.instruction}\n這會執行這一筆 Maps 通知提供的結束動作。通知變更後需要重新確認；送出要求不代表 Maps 已結束路線。") },
        confirmButton = { TextButton(onClick = { cancelId = null; onCancel(cancellation) },
            Modifier.testTag("navigation_cancel_confirm")) { Text("送出結束要求") } },
        dismissButton = { TextButton(onClick = { cancelId = null }) { Text("返回") } })
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("navigation_page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("導航與目的地", style = MaterialTheme.typography.headlineSmall)
            Text("由 Google Maps 規劃路線；此頁顯示它的導航通知與提供的操作。實際儀表模式可使用通知指示與把手操作，車端顯示及按鍵仍待實車驗證。")
            if (!state.signedIn) {
                Text("請先完成帳號登入，再使用導航頁。")
                Button(onClick = onAccount) { Text("帳號與登入") }
            } else {
                OutlinedTextField(value = query, onValueChange = { if (it.length <= 512) query = it },
                    label = { Text("地址或地點名稱") }, singleLine = true, enabled = active,
                    modifier = Modifier.fillMaxWidth().testTag("navigation_destination"))
                Button(onClick = { launchQuery = query }, enabled = active && mapsDestinationIntent(query) != null,
                    modifier = Modifier.testTag("navigation_launch")) { Text("以此目的地開啟 Maps") }
                Text("目的地只保留在本頁，開啟 Maps、離頁或背景時清除，不寫入診斷。未授權通知存取仍可開啟 Maps，但無法在此查看、重播或結束導航。")
                state.launch?.let { Text(when (it) {
                    NavigationLaunchResult.DISPATCHED -> "已交給 Maps 處理；尚未確認路線是否開始。"
                    NavigationLaunchResult.INVALID -> "請輸入 512 字以內、沒有控制字元的地址或地點。"
                    NavigationLaunchResult.UNAVAILABLE -> "無法開啟 Google Maps；請確認已安裝且可使用。"
                    NavigationLaunchResult.LOCKED -> "請先解鎖手機，再開啟導航。"
                }) }
                Text(navigationStatusText(state.observation.status), Modifier.testTag("navigation_status"))
                observed?.instruction?.let { value ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(value.instruction, Modifier.testTag("navigation_instruction"))
                        value.nextStep?.let { Text("下一轉彎距離：$it") }
                        value.remaining?.let { Text("Maps 行程摘要：$it") }
                        Text(value.maneuver?.let { "轉向：${it.name}" } ?: "轉向未知；請依 Maps 與道路指示行駛。")
                    } }
                }
                Button(onClick = { sourceId?.let(onRepeat) }, enabled = sourceId != null && observed?.canRepeat == true && !state.speaking,
                    modifier = Modifier.testTag("navigation_repeat")) { Text("播報目前指示") }
                if (state.speaking) OutlinedButton(onClick = onStopSpeech) { Text("停止本次播報") }
                Text("播報可能降低其他音訊音量；通話、勿擾或通知狀態未就緒時會停止。")
                OutlinedButton(onClick = { cancelId = sourceId }, enabled = sourceId != null && observed?.canCancel == true,
                    modifier = Modifier.testTag("navigation_cancel")) { Text("要求 Maps 結束導航") }
                state.speech?.let { Text(it) }
                state.cancel?.let { Text(when (it) {
                    NavigationCancelResult.DISPATCHED -> "已送出結束要求，請確認 Maps 的實際狀態。"
                    NavigationCancelResult.STALE -> "通知已變更或此要求已送出，請重新查看。"
                    NavigationCancelResult.UNSUPPORTED -> "這筆通知沒有可用的結束動作，請在 Maps 操作。"
                    NavigationCancelResult.ACCESS_REQUIRED -> "需要通知存取權，尚未送出要求。"
                    NavigationCancelResult.UNAVAILABLE -> "目前無法送出要求，請在 Maps 確認。"
                    NavigationCancelResult.LOCKED -> "請先解鎖手機，再確認結束導航。"
                }) }
                OutlinedButton(onClick = onRefresh, Modifier.testTag("navigation_refresh")) { Text("重新檢查") }
                TextButton(onClick = onAccess, Modifier.testTag("navigation_access")) { Text("通知存取與功能說明") }
            }
        }
    }
}

internal fun navigationStatusText(status: NavigationStatus) = when (status) {
    NavigationStatus.LOADING -> "正在讀取 Maps 導航通知…"
    NavigationStatus.ACCESS_REQUIRED -> "尚未授權通知存取，無法讀取導航指示或操作通知。"
    NavigationStatus.LISTENER_UNAVAILABLE -> "通知監聽尚未連線，請檢查通知存取與系統背景執行設定。"
    NavigationStatus.MAPS_UNAVAILABLE -> "找不到已安裝的 Google Maps。"
    NavigationStatus.WAITING -> "目前未觀察到 Maps 持續導航通知；這不代表路線已結束。"
    NavigationStatus.OBSERVED -> "已讀到 Maps 導航通知。"
    NavigationStatus.AMBIGUOUS -> "同時出現多筆導航通知，暫不選定或操作其中一筆。"
    NavigationStatus.UNSUPPORTED -> "目前通知格式無法解析，請使用 Google Maps 查看與操作。"
}
