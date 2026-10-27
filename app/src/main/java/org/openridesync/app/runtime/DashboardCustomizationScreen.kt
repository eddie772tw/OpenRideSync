package org.openridesync.app.runtime

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openridesync.app.OpenRideSyncApplication

internal data class DashboardCustomizationState(
    val order: List<MainMenuSelection.Item> = MainMenuSelection.Item.entries,
    val speechMode: WeatherSpeechMode = WeatherSpeechMode.FULL,
    val autoReturn: Boolean = false,
    val musicPackage: String? = null,
    val incomingMessages: IncomingMessageMode = IncomingMessageMode.ASK_TO_LISTEN,
    val screenAwake: ScreenAwakeMode = ScreenAwakeMode.OFF,
)

/** Preferences only: opening this screen does not start a vehicle connection or media session. */
@Composable
internal fun DashboardCustomizationScreen(onMusicApps: () -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as OpenRideSyncApplication
    val preferences = app.ridePreferences
    val order by preferences.functionOrder.collectAsState()
    val speech by preferences.weatherSpeech.collectAsState()
    val autoReturn by preferences.autoReturn.collectAsState()
    val incomingMessages by preferences.incomingMessages.collectAsState()
    val screenAwake by preferences.screenAwake.collectAsState()
    val music = remember(app) { app.getSharedPreferences("music", Context.MODE_PRIVATE) }
    var musicPackage by remember(music) { mutableStateOf(music.getString("favorite", null)) }
    DisposableEffect(music) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            musicPackage = music.getString("favorite", null)
        }
        music.registerOnSharedPreferenceChangeListener(listener)
        musicPackage = music.getString("favorite", null)
        onDispose { music.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    BackHandler(onBack = onBack)
    DashboardCustomizationContent(DashboardCustomizationState(order, speech, autoReturn, musicPackage, incomingMessages, screenAwake),
        onMove = { item, delta -> preferences.setFunctionOrder(moveFunction(preferences.functionOrder.value, item, delta)) },
        onSpeechMode = preferences::setWeatherSpeech, onAutoReturn = preferences::setAutoReturn,
        onMusicApps = onMusicApps, onBack = onBack, onIncomingMessages = preferences::setIncomingMessages,
        onScreenAwake = preferences::setScreenAwake)
}

/** Pure rendering seam for the host gallery; no Android service, permission or protocol operation. */
@Composable
internal fun DashboardCustomizationContent(state: DashboardCustomizationState,
    onMove: (MainMenuSelection.Item, Int) -> Unit,
    onSpeechMode: (WeatherSpeechMode) -> Unit,
    onAutoReturn: (Boolean) -> Unit,
    onMusicApps: () -> Unit,
    onBack: () -> Unit,
    onIncomingMessages: (IncomingMessageMode) -> Unit = {},
    onScreenAwake: (ScreenAwakeMode) -> Unit = {},
) {
    val order = normalizeFunctionOrder(state.order)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp).testTag("dashboard_customization_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("儀表自訂", style = MaterialTheme.typography.headlineMedium)
            Text("設定儲存在這台手機。調整順序會套用到已支援儀表的主選單，並保留目前焦點功能。")
            Text("功能順序", style = MaterialTheme.typography.titleLarge)
            Text("目前可用：天氣、Maps 通知導航、標準通話通知、訊息預覽、音樂、音量。實際顯示仍依連線能力、模式、權限與通話狀態判定；未支援功能會略過。")
            order.forEachIndexed { index, item ->
                val name = functionLabel(item)
                Card(Modifier.fillMaxWidth().testTag("function_${item.name}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. $name", style = MaterialTheme.typography.titleMedium)
                        if (item == MainMenuSelection.Item.CALL)
                            Text("Android 14 以上的標準 CallStyle 通知可在實際儀表模式顯示來電與進行中通話。需通知存取權，不能代表所有電話或通訊 App；車端操作仍待實車驗證。", style = MaterialTheme.typography.bodySmall)
                        if (item == MainMenuSelection.Item.NAVIGATION)
                            Text("已提供 Maps 通知導航，需通知存取權與實際儀表模式；車端顯示與結束操作仍待實車驗證。", style = MaterialTheme.typography.bodySmall)
                        if (item == MainMenuSelection.Item.MESSAGE)
                            Text("在儀表模式瀏覽通知預覽；新訊息依下方處理設定提示或朗讀。把手回覆仍在開發中。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onMove(item, -1) }, enabled = index > 0,
                                modifier = Modifier.testTag("move_${item.name}_up")
                                    .semantics { contentDescription = "將$name 上移" }) { Text("上移") }
                            OutlinedButton(onClick = { onMove(item, 1) }, enabled = index < order.lastIndex,
                                modifier = Modifier.testTag("move_${item.name}_down")
                                    .semantics { contentDescription = "將$name 下移" }) { Text("下移") }
                        }
                    }
                }
            }
            Text("服務提供者", style = MaterialTheme.typography.titleLarge)
            Text("音樂：${state.musicPackage?.takeIf { it.isNotBlank() }?.let { "已儲存 $it" } ?: "尚未選取常用播放器"}")
            Text("進入播放器清單可確認已安裝的應用程式。選取常用播放器後，下次車輛連線成功時會嘗試開啟並繼續播放。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onMusicApps, modifier = Modifier.fillMaxWidth().testTag("customization_music_apps")) {
                Text("選擇已安裝的音樂播放器")
            }
            Text("導航：使用 Google Maps 的導航通知，依通知存取權與實際儀表模式提供指示。可由手機導航頁自行啟動 Maps；不會自動規劃路線或播報導航。")
            Text("通話：使用來源 App 的標準 CallStyle 通知，無需選擇服務。手機操作須再次確認；實際儀表模式可用實體按鍵操作，送出請求不代表已接通或結束。不讀取聯絡人或通話紀錄。")
            Text("訊息：使用已授權的 Android 訊息通知，無需選擇服務。把手回覆與語音輸入仍在開發中。")
            Text("新訊息處理", style = MaterialTheme.typography.titleLarge)
            Text("僅在實際連線且儀表為 SAB 模式、通知存取已授權時處理新訊息。鎖屏時僅使用 Android 提供的通知內容。")
            Column(Modifier.selectableGroup()) {
                IncomingMessageMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.incomingMessages == mode,
                        onClick = { onIncomingMessages(mode) }, role = Role.RadioButton)
                        .testTag("incoming_messages_${mode.name}").padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.incomingMessages == mode, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(when (mode) {
                                IncomingMessageMode.ASK_TO_LISTEN -> "先詢問是否聆聽"
                                IncomingMessageMode.AUTO_READ -> "自動讀取"
                                IncomingMessageMode.IGNORE -> "忽略新訊息提示"
                            })
                            Text(when (mode) {
                                IncomingMessageMode.ASK_TO_LISTEN -> "只提示 App 與訊息標籤，確認後才朗讀正文。"
                                IncomingMessageMode.AUTO_READ -> "收到新訊息後先提示，再朗讀通知預覽。"
                                IncomingMessageMode.IGNORE -> "停止自動提示與朗讀，仍可手動瀏覽訊息。"
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("此設定不會傳送回覆；自動回覆與語音輸入仍在開發中。", style = MaterialTheme.typography.bodySmall)
            Text("天氣語音", style = MaterialTheme.typography.titleLarge)
            Text("僅影響下一次天氣資訊播報。完整會讀出所有預報；摘要只讀地點與第一個預報時段；關閉則不發聲。")
            Text("啟動提醒與安全／就緒檢查不受此設定影響。播放中的語音不會立即中斷。", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.selectableGroup()) {
                WeatherSpeechMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.speechMode == mode,
                        onClick = { onSpeechMode(mode) }, role = Role.RadioButton)
                        .testTag("weather_speech_${mode.name}").padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.speechMode == mode, onClick = null)
                        Text(when (mode) { WeatherSpeechMode.FULL -> "完整播報"; WeatherSpeechMode.SUMMARY -> "摘要播報"; WeatherSpeechMode.OFF -> "關閉天氣語音" },
                            Modifier.padding(start = 12.dp))
                    }
                }
            }
            Text("閒置自動返回", style = MaterialTheme.typography.titleLarge)
            Text("音樂、未選播放器及導航頁閒置 15 秒後返回主選單。新訊息提示與讀取頁會在播報結束或失敗後計時，閒置 15 秒後顯示下一則待處理訊息或返回原頁。音量頁原本就會自動返回；天氣頁不受此開關影響。")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("啟用閒置自動返回", Modifier.weight(1f))
                Switch(checked = state.autoReturn, onCheckedChange = onAutoReturn,
                    modifier = Modifier.testTag("customization_auto_return")
                        .semantics { contentDescription = "音樂頁面自動返回主選單" })
            }
            Text("連線期間手機螢幕", style = MaterialTheme.typography.titleLarge)
            Text("僅在車輛已連線、App 位於前景時生效；RoadSync 與儀表模式皆適用。模擬預覽不會啟用。")
            Text("保持亮起會增加耗電；此設定不會喚醒或解鎖手機。切到背景或中斷連線後，仍依系統設定休眠。", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.selectableGroup()) {
                ScreenAwakeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.screenAwake == mode,
                        onClick = { onScreenAwake(mode) }, role = Role.RadioButton)
                        .testTag("screen_awake_${mode.name}").padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.screenAwake == mode, onClick = null)
                        Text(when (mode) {
                            ScreenAwakeMode.ON -> "保持亮起"
                            ScreenAwakeMode.ONLY_CHARGING -> "僅充電時保持亮起"
                            ScreenAwakeMode.OFF -> "跟隨系統休眠"
                        }, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

private fun functionLabel(item: MainMenuSelection.Item): String = when (item) {
    MainMenuSelection.Item.WEATHER -> "天氣"
    MainMenuSelection.Item.NAVIGATION -> "導航"
    MainMenuSelection.Item.CALL -> "電話"
    MainMenuSelection.Item.MESSAGE -> "訊息"
    MainMenuSelection.Item.MUSIC -> "音樂"
    MainMenuSelection.Item.VOLUME -> "音量"
}
