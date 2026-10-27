package org.openridesync.app.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.runtime.RidePageStage
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

@Composable
internal fun MediaScreen(onBack: () -> Unit, model: MediaViewModel = viewModel(), onFavorite: () -> Unit = {}, onPermissions: () -> Unit = {}, onAccount: () -> Unit = {}) {
    val state by model.state.collectAsState()
    val listener by MediaAccessService.callSummary.collectAsState()
    val context = LocalContext.current
    var permission by rememberSaveable { mutableStateOf<AppAccess?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.onStart()
                Lifecycle.Event.ON_STOP -> model.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onStart()
        onDispose { owner.lifecycle.removeObserver(observer); model.onStop() }
    }
    BackHandler { model.onStop(); onBack() }
    permission?.let { access ->
        val close = { permission = null; model.refresh() }
        PermissionDialog(listOf(access), onConfirm = close, onDismiss = close)
    }
    MediaContent(state, { model.onStop(); onBack() }, model::refresh, { permission = AppAccess.MEDIA },
        model::select, model::play, model::pause, model::previous, model::next, model::openRideMusic,
        { permission = AppAccess.PHONE }, model::setAutoReturn, onFavorite, onPermissions, onAccount,
        listenerDisconnected = AppAccess.MEDIA.granted(context) && !listener.listenerConnected)
}

@Composable
internal fun MediaContent(
    state: MediaPageState, onBack: () -> Unit, onRefresh: () -> Unit, onAccess: () -> Unit,
    onSelect: (String) -> Unit, onPlay: () -> Unit, onPause: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit,
    onRideMusic: () -> Unit = {}, onPhonePermission: () -> Unit = {},
    onAutoReturn: (Boolean) -> Unit = {}, onFavorite: () -> Unit = {},
    onPermissions: () -> Unit = {}, onAccount: () -> Unit = {},
    listenerDisconnected: Boolean = false,
) {
    val media = state.media
    val journey = mediaJourney(state)
    val selected = media.sessions.singleOrNull { it.id == media.selectedId }
    var choosePlayer by rememberSaveable(media.selectedId) { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("media-back")) { Text("返回") }
                Text("音樂", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onPermissions) { Text("權限") }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("media-content"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (journey == MediaJourney.ACCOUNT) item {
                    Text("帳號狀態已變更，媒體觀察與控制已停止。請先完成登入。")
                    Button(onClick = onAccount, modifier = Modifier.testTag("media-account")) { Text("前往帳號") }
                } else {
                    if (state.observing && listenerDisconnected) item {
                        Text("通知存取已授權，但通知服務尚未連線。通知訊息與通知來電暫時無法可靠讀取；已可使用的播放器仍可操作。")
                        OutlinedButton(onClick = onAccess, modifier = Modifier.testTag("media-listener-access")) {
                            Text("查看連線恢復說明")
                        }
                    }
                    when (journey) {
                        MediaJourney.PAUSED -> item { Text("媒體觀察已暫停。返回前景後會重新檢查播放器，不會自動播放。") }
                        MediaJourney.ACCESS_REQUIRED -> if (!listenerDisconnected) item {
                            Text("先允許通知存取", style = MaterialTheme.typography.titleMedium)
                            Text("列出其他 App 的播放器與控制播放需要通知存取。可先查看說明；拒絕後仍能返回其他功能。")
                            Button(onClick = onAccess, modifier = Modifier.testTag("media-access")) { Text("查看通知存取說明") }
                        }
                        MediaJourney.UNAVAILABLE -> item {
                            Text(if (listenerDisconnected) "目前無法讀取播放器。通知服務尚未連線；請查看連線恢復說明，或重新整理。"
                                else "目前無法讀取播放器。請重新整理，或到權限頁檢查通知存取。")
                            Button(onClick = onRefresh, modifier = Modifier.testTag("media-retry")) { Text("重新整理") }
                        }
                        MediaJourney.EMPTY -> item {
                            Text("目前沒有可用的媒體工作階段。請先開啟音樂 App 並播放，再回來重新整理。")
                            Button(onClick = onRefresh, modifier = Modifier.testTag("media-retry")) { Text("重新整理播放器") }
                        }
                        MediaJourney.CHOOSE -> item {
                            Text("選擇播放器", style = MaterialTheme.typography.titleMedium)
                            Text("請選取一個媒體工作階段。選取不會自動播放。")
                        }
                        MediaJourney.STALE -> item {
                            Text("先前的播放器工作階段已變更，控制已停用。請重新整理並重新選取。")
                            Button(onClick = onRefresh, modifier = Modifier.testTag("media-retry")) { Text("重新整理播放器") }
                        }
                        else -> Unit
                    }
                    if (journey == MediaJourney.READY) {
                        item {
                            Card(Modifier.fillMaxWidth().testTag("media-now-playing")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(selected?.label.orEmpty(), style = MaterialTheme.typography.labelLarge)
                                    Text(media.title?.takeIf { it.isNotBlank() } ?: "未提供曲名", style = MaterialTheme.typography.titleLarge)
                                    Text(listOfNotNull(media.artist?.takeIf { it.isNotBlank() },
                                        media.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
                                        .ifBlank { "未提供演出者與專輯" })
                                    Text("播放狀態：" + when (media.playback) {
                                        MediaPlayback.UNKNOWN -> "尚未回報"
                                        MediaPlayback.NONE -> "尚未播放"
                                        MediaPlayback.STOPPED -> "已停止"
                                        MediaPlayback.PAUSED -> "已暫停"
                                        MediaPlayback.PLAYING -> "播放中"
                                        MediaPlayback.BUFFERING -> "緩衝中"
                                        MediaPlayback.CONNECTING -> "連線中"
                                        MediaPlayback.ERROR -> "媒體 App 回報錯誤"
                                        MediaPlayback.OTHER -> "其他狀態"
                                    })
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(onClick = onPlay, enabled = media.canPlay, modifier = Modifier.testTag("media-play")) { Text("播放") }
                                        Button(onClick = onPause, enabled = media.canPause, modifier = Modifier.testTag("media-pause")) { Text("暫停") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(onClick = onPrevious, enabled = media.canPrevious) { Text("上一首") }
                                        OutlinedButton(onClick = onNext, enabled = media.canNext) { Text("下一首") }
                                    }
                                    Text("控制按鈕依播放器回報的能力啟用；送出請求不代表已播放，請以上方後續狀態為準。", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    state.result?.let { result -> item {
                        Text(when (result) {
                            DispatchResult.DISPATCHED -> "控制請求已送出，等待媒體 App 回報狀態。"
                            DispatchResult.UNSUPPORTED -> "此媒體工作階段目前不支援這項操作。"
                            DispatchResult.STALE -> "媒體工作階段已變更，請重新整理並選取。"
                            DispatchResult.ACCESS_REQUIRED -> "通知存取授權已變更，請檢查系統設定。"
                            DispatchResult.UNAVAILABLE -> "目前無法送出控制請求，請重新整理。"
                        }, Modifier.testTag("media-result"))
                    } }
                    if (journey == MediaJourney.READY) item {
                        OutlinedButton(onClick = { choosePlayer = !choosePlayer }, modifier = Modifier.testTag("media-change-player")) {
                            Text(if (choosePlayer) "收起播放器清單" else "切換播放器")
                        }
                    }
                    if (journey in setOf(MediaJourney.CHOOSE, MediaJourney.STALE) || (journey == MediaJourney.READY && choosePlayer)) {
                        items(media.sessions.distinctBy { it.id }, key = { it.id }) { session ->
                            OutlinedButton(onClick = { onSelect(session.id); choosePlayer = false }, modifier = Modifier.fillMaxWidth().testTag("media-session-${session.id}")) {
                                Column {
                                    Text((if (session.id == media.selectedId) "已選取：" else "選取：") + session.label)
                                    session.title?.takeIf { it.isNotBlank() }?.let { Text(it) }
                                    Text(session.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (state.observing) item {
                        TextButton(onClick = onRefresh, modifier = Modifier.testTag("media-refresh")) { Text("重新檢查播放器") }
                    }
                    item {
                        OutlinedButton(onClick = onFavorite, enabled = state.observing, modifier = Modifier.testTag("media-favorite")) { Text("常用播放器") }
                        Text("常用播放器是下次連線時的啟動偏好；與本頁目前選取的工作階段不同。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!state.phoneStateGranted) item {
                        Text("未取得電話狀態權限：無法可靠辨識一般來電，通話期間的音樂抑制及通話音量選擇可能不正確。")
                        OutlinedButton(onClick = onPhonePermission, enabled = state.observing,
                            modifier = Modifier.testTag("media-phone-permission")) { Text("電話狀態權限說明") }
                    }
                    item(key = "ride-status") { MediaRideSection(state, onRideMusic) }
                    item {
                        OutlinedButton(onClick = { settingsExpanded = !settingsExpanded }, modifier = Modifier.testTag("media-settings")) {
                            Text(if (settingsExpanded) "收起音樂設定" else "音樂設定與權限")
                        }
                    }
                    if (settingsExpanded) item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("車端音樂頁自動返回主選單", Modifier.weight(1f))
                                Switch(checked = state.autoReturn, onCheckedChange = onAutoReturn,
                                    enabled = state.observing, modifier = Modifier.testTag("ride-auto-return"))
                            }
                            Text("開啟後，車端音樂頁閒置 15 秒回到主選單；音量頁固定自動返回。", style = MaterialTheme.typography.bodySmall)
                            if (journey != MediaJourney.ACCESS_REQUIRED) OutlinedButton(onClick = onAccess, enabled = state.observing,
                                modifier = Modifier.testTag("media-access")) { Text("通知存取說明與設定") }
                            TextButton(onClick = onPermissions) { Text("全部權限與功能") }
                        }
                    }
                    if (state.settingsUnavailable) item { Text("無法開啟系統設定，請從手機設定開啟 OpenRideSync 的通知存取。") }
                }
            }
        }
    }
}

@Composable
private fun MediaRideSection(state: MediaPageState, onRideMusic: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("車端音樂", style = MaterialTheme.typography.titleMedium)
            if (!state.rideConnected) Text("目前未連接車輛。手機播放器可獨立操作；車端顯示與把手功能仍待實車驗證。")
            else {
                val retained = state.rideSelection
                Text(if (retained == null) "選取播放器後，可嘗試在車端開啟音樂頁。" else "連線期間保留的播放器：${retained.label}")
                Text("開啟車端音樂頁不會自動播放；畫面指令成功也不代表儀表顯示已驗證。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRideMusic, enabled = state.observing && state.media.access == MediaAccess.GRANTED && retained != null,
                    modifier = Modifier.testTag("ride-music-open")) { Text("在車端開啟音樂") }
                if (state.rideSelectionFailed) Text("此次播放器選擇未能保留至車輛連線，請重新選取。")
                if (state.rideMusicFailed) Text("無法開啟車端音樂；請確認播放器仍可用，且車輛支援畫面顯示。")
                Text(when (state.rideMusic.stage) {
                    RidePageStage.DISABLED -> "選取播放器後可開啟車端音樂頁。"
                    RidePageStage.SPLASH -> "正在完成連線啟動提示。"
                    RidePageStage.STARTUP_FAILED -> "連線啟動提示未完成，請結束連線後再試。"
                    RidePageStage.UNSUPPORTED -> "此車輛尚未提供所需的顯示能力。"
                    RidePageStage.NO_SESSION -> "播放器工作階段已結束，請重新選取。"
                    RidePageStage.MENU -> "目前位於車端主選單。"
                    RidePageStage.MUSIC -> if (state.calls.musicBlocked) "通話期間暫停車端音樂控制。" else "目前位於車端音樂頁。"
                    RidePageStage.VOLUME -> "目前位於車端音量頁。"
                    RidePageStage.WEATHER -> "目前位於車端天氣匯報。"
                    RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT -> "目前位於車端狀態匯報。"
                    RidePageStage.MESSAGE -> "目前位於車端訊息通知頁。"
                    RidePageStage.MESSAGE_PROMPT -> "目前位於車端新訊息提示頁。"
                    RidePageStage.MESSAGE_READ -> "目前位於車端訊息預覽讀取頁。"
                    RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_LISTENING, RidePageStage.MESSAGE_CONFIRMATION,
                    RidePageStage.MESSAGE_TRY_AGAIN, RidePageStage.MESSAGE_SENDING, RidePageStage.MESSAGE_SENT,
                    RidePageStage.MESSAGE_NOT_SENT -> "目前位於車端訊息回覆流程。"
                    RidePageStage.NAVIGATION -> "目前位於車端導航頁。"
                    RidePageStage.NAVIGATION_CANCEL -> "目前位於車端結束導航確認頁。"
                    RidePageStage.CALL -> "目前位於車端通話通知頁；動作只代表已向來源 App 提出要求。"
                    RidePageStage.CALL_CANCEL -> "目前位於車端結束通話確認頁；尚未送出結束要求。"
                })
                if (!state.calls.phoneObserved) Text("未能確認一般電話狀態，通話期間的音樂抑制可能不正確；請檢查電話狀態權限。")
                if (!state.rideMusic.volumeAvailable) Text("目前無法判定音量控制對象或讀取音量，請確認通知存取與電話權限。")
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("media-ride-details")) { Text(if (expanded) "收起連線細節" else "連線細節") }
                if (expanded) {
                    Text("離開音樂頁仍保留選擇；工作階段結束、授權變更或車輛斷線時清除。")
                    if (state.rideMusic.pending) Text("等待車端確認畫面。")
                    else state.rideMusic.receiptStatus?.let { status ->
                        Text(if (status == 1) "車端已接受畫面指令，實際顯示請以儀表為準。" else "車端未接受這次畫面，請重新開啟音樂頁。")
                    }
                    Text("目前車端依模式、權限與來源提供天氣、Maps 通知導航、標準通話通知、訊息預覽、音樂與音量，實際車端行為仍待驗證。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
