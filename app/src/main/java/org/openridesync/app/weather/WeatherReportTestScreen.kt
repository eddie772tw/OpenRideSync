package org.openridesync.app.weather

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
import kotlinx.coroutines.launch
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.BuildConfig
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

/** Explicit real location/Honda request/audio check; contains no synthetic forecast or car success. */
@Composable
internal fun WeatherReportTestScreen(onBack: () -> Unit) {
    if (!BuildConfig.DEBUG) { BackHandler(onBack = onBack); TextButton(onClick = onBack) { Text("返回") }; return }
    val app = LocalContext.current.applicationContext as OpenRideSyncApplication
    val session = AuthSession.get()
    val auth by session.state.collectAsState()
    val signedIn = auth.status == AuthStatus.SIGNED_IN && session.currentUser()?.uid != null
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var showPermission by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val locationGranted = remember(permissionRevision, visible) { weatherLocationGranted(app) }
    val reporter = remember(auth.status, session.revision) { RideWeather(app, app.journal,
        app.journal?.newOperation() ?: java.util.UUID.randomUUID().toString()) { visible } }
    val state by reporter.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, reporter) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { visible = true; permissionRevision++ }
            if (event == Lifecycle.Event.ON_STOP) { visible = false; reporter.cancel() }
        }
        visible = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        owner.lifecycle.addObserver(observer)
        onDispose { visible = false; owner.lifecycle.removeObserver(observer); reporter.close() }
    }
    BackHandler { reporter.cancel(); onBack() }
    if (showPermission) {
        val close: () -> Unit = { showPermission = false; permissionRevision++ }
        PermissionDialog(listOf(AppAccess.LOCATION), onConfirm = close, onDismiss = close)
    }
    Scaffold { padding -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("天氣語音實測", style = MaterialTheme.typography.headlineSmall)
        Text("會讀取真實位置，使用已登入 Honda 帳號查詢天氣，並透過手機目前音訊路徑朗讀；通話或勿擾期間不播放。")
        Text("來源：Honda RoadSync API；溫度使用本 App 選擇，未選擇時繼承 Android 地區偏好。這不是 Google App 資料。")
        if (!signedIn) Text("請先返回帳號頁完成登入；未登入不會定位或發出天氣請求。")
        Text("狀態：${state.stage}", Modifier.testTag("weather-report-status"))
        state.error?.let { Text("未完成：$it") }
        state.current?.weather?.let { Text("${it.description} · ${it.temperature(state.unit)}") }
        val busy = state.stage in setOf(RideWeatherStage.LOADING, RideWeatherStage.SPEAKING)
        if (!locationGranted) OutlinedButton(onClick = { showPermission = true }, enabled = signedIn && !busy,
            modifier = Modifier.testTag("weather-report-permission")) { Text("選擇位置權限") }
        Button(onClick = { scope.launch { reporter.report() } }, enabled = visible && signedIn && locationGranted && !busy,
            modifier = Modifier.testTag("weather-report-start")) { Text("定位並朗讀真實預報") }
        if (busy) OutlinedButton(onClick = reporter::cancel) { Text("取消") }
        Text("語音完成僅表示 TTS 回呼完成，不代表已在耳機或車輛播放。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { reporter.cancel(); onBack() }) { Text("返回") }
    } }
}
