package org.openridesync.app.weather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import org.openridesync.app.OpenRideSyncApplication
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

@Composable
internal fun WeatherScreen(onBack: () -> Unit, model: WeatherViewModel = viewModel(), onAccount: () -> Unit = {}) {
    val state by model.state.collectAsState()
    val homeWeather = (LocalContext.current.applicationContext as OpenRideSyncApplication).homeWeather
    val background by homeWeather.state.collectAsState()
    var showPermission by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val dispose = bindWeatherLifecycle(owner.lifecycle, model)
        onDispose(dispose)
    }
    BackHandler { model.onStop(); onBack() }
    if (showPermission) {
        val close = { showPermission = false; model.refreshAccess() }
        PermissionDialog(listOf(AppAccess.LOCATION), onConfirm = close, onDismiss = close)
    }
    WeatherContent(state, { model.onStop(); onBack() }, model::refresh, model::cancel,
        { showPermission = true }, model::openLocationSettings, model::setUnit, onAccount, background, homeWeather::setEnabled)
}

@Composable
internal fun WeatherContent(state: WeatherPageState, onBack: () -> Unit, onRefresh: () -> Unit,
    onCancel: () -> Unit, onPermission: () -> Unit, onSettings: () -> Unit, onUnit: (TemperatureUnit) -> Unit,
    onAccount: () -> Unit = {}, background: HomeWeatherState? = null,
    onBackgroundEnabled: (Boolean) -> Unit = {}) {
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("天氣與預報", style = MaterialTheme.typography.headlineMedium) }
            item { OutlinedButton(onClick = onBack) { Text("返回") } }
            background?.let { home -> item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("首頁背景天氣", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (home.enabled) "自動更新已開啟" else "自動更新已關閉")
                            Switch(checked = home.enabled, onCheckedChange = onBackgroundEnabled,
                                modifier = Modifier.testTag("home-weather-enabled"))
                        }
                        Text("首頁會在已登入且已授權位置時自動取得位置與天氣。背景使用最近一次前景取得的位置，不持續定位；停用會清除保存的位置與天氣。")
                        Text("背景更新約每 60 分鐘由 Android 排程，有網路時執行；省電或系統限制可能延後。", style = MaterialTheme.typography.bodySmall)
                        if (!home.signedIn) Text("請先登入以使用首頁天氣。")
                        else if (!home.locationGranted) Text("尚未授權位置，首頁不會自動取得天氣。請使用下方位置授權入口。")
                        if (home.storageError) Text("無法保存或清除天氣資料，請稍後再試。")
                    }
                }
            } }

            if (!state.signedIn) item {
                Text("請先登入，再查詢所在地天氣。下方查詢與預報由你手動啟動。")
                Button(onClick = onAccount, modifier = Modifier.testTag("weather-account")) { Text("前往帳號") }
            }
            else {
                item {
                    Text("下方手動查詢會取得概略位置，並將位置傳送給 Honda RoadSync 天氣服務；與首頁背景天氣分開。")
                    Text("查詢後，持續停留此頁時每 60 分鐘更新。取消、離頁、進入背景或帳號變更會停止更新；返回後請自行再查詢。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TemperatureUnit.entries.forEach { unit ->
                            FilterChip(selected = state.unit == unit, onClick = { onUnit(unit) },
                                label = { Text(if (unit == TemperatureUnit.CELSIUS) "攝氏 °C" else "華氏 °F") },
                                modifier = Modifier.testTag("weather-unit-${unit.name}"))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.locationGranted) Button(onClick = onPermission, enabled = state.observing,
                            modifier = Modifier.testTag("weather-permission")) { Text("允許概略位置") }
                        OutlinedButton(onClick = onSettings, enabled = state.observing) { Text("開啟位置設定") }
                        if (state.loading) OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("weather-cancel")) { Text("取消讀取") }
                        else Button(onClick = onRefresh, enabled = state.observing && state.locationGranted,
                            modifier = Modifier.testTag("weather-refresh")) { Text(if (state.current == null) "查詢所在地天氣" else "更新天氣") }
                    }
                }
                if (!state.loading && state.current == null && state.errorCode == null) item {
                    Text(if (state.locationGranted) "尚未查詢天氣。按下查詢後才會取得位置與天氣。"
                        else "尚未取得位置授權。你可以查看權限說明、稍後再查詢，或返回其他功能。",
                        Modifier.testTag("weather-empty"))
                }
                if (state.settingsUnavailable) item { Text("無法開啟位置設定，請從手機設定檢查位置服務。") }
                if (state.loading) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.current == null) "正在取得位置與天氣…" else "正在讀取預報…")
                }
                state.errorCode?.let { code -> item {
                    Text(weatherErrorMessage(code), Modifier.testTag("weather-error"))
                    if (code in setOf("AUTH_REQUIRED", "UNAUTHENTICATED"))
                        Button(onClick = onAccount, modifier = Modifier.testTag("weather-account")) { Text("前往帳號") }
                } }
                state.current?.let { current ->
                    item {
                        WeatherCard("目前天氣", current.weather, state.unit)
                        state.fetchedAtMillis?.let { Text("取得時間：${DateFormat.getDateTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                        if (state.lastKnownLocation) Text("目前無法取得新的定位，使用手機最後一次記錄的位置。", style = MaterialTheme.typography.bodySmall)
                        state.locationAgeMillis?.let { Text("查詢時的位置紀錄距今 ${it / 60_000} 分鐘。", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                state.forecastErrorCode?.let { code -> item {
                    Text("預報：${weatherErrorMessage(code)}")
                    if (code in setOf("AUTH_REQUIRED", "UNAUTHENTICATED"))
                        Button(onClick = onAccount, modifier = Modifier.testTag("weather-forecast-account")) { Text("前往帳號") }
                } }
                items(state.forecast?.hours.orEmpty()) { hour ->
                    WeatherCard("${hour.hour} 小時後", hour.weather, state.unit)
                }
            }
        }
    }
}

@Composable
private fun WeatherCard(title: String, weather: WeatherReading, unit: TemperatureUnit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (weather.place.isNotBlank()) Text(weather.place)
            Text(weather.temperature(unit), style = MaterialTheme.typography.headlineMedium)
            Text(weather.description.ifBlank { weather.main.ifBlank { "尚未提供天氣描述" } })
        }
    }
}

internal fun weatherErrorMessage(code: String): String = when (code) {
    "LOCATION_PERMISSION" -> "尚未取得位置授權。請允許概略位置；若已拒絕多次，請從手機的應用程式設定開啟權限。"
    "LOCATION_DISABLED" -> "手機的位置服務已關閉，請開啟位置設定後再試。"
    "LOCATION_UNAVAILABLE" -> "目前無法取得位置，請稍後重新整理。"
    "AUTH_REQUIRED", "UNAUTHENTICATED" -> "登入狀態已失效，請返回帳號頁重新登入。"
    "PERMISSION_DENIED" -> "目前帳號無法使用此天氣服務。"
    "DATA_LOSS" -> "天氣服務回傳的資料不完整，請稍後再試。"
    "CANCELLED" -> "讀取已取消。"
    "DEADLINE_EXCEEDED", "TIMEOUT" -> "讀取天氣逾時，請稍後再試。"
    "UNAVAILABLE", "NETWORK" -> "目前無法連上天氣服務，請檢查網路後再試。"
    else -> "天氣讀取失敗（$code），請稍後再試。"
}
