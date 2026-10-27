package org.openridesync.app.runtime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.openridesync.app.BuildConfig
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.navigation.NavigationPreview
import org.openridesync.app.navigation.NavigationObservation
import org.openridesync.app.navigation.NavigationStatus
import org.openridesync.app.navigation.NavigationCancelResult
import org.openridesync.app.navigation.MapsInstruction
import org.openridesync.app.navigation.NavigationProjection
import org.openridesync.app.calls.*
import org.openridesync.app.media.*
import org.openridesync.app.messages.MessageReplyResult
import org.openridesync.app.weather.HomeWeatherState
import org.openridesync.app.weather.CurrentWeather
import org.openridesync.app.weather.WeatherReading
import org.openridesync.app.weather.RideWeatherState
import org.openridesync.app.weather.RideWeatherStage
import org.openridesync.honda.wire.WeatherWire

/** Local render fixtures only. No ViewModel, connection/service, transport, journal or real receipt.
 * Real media/weather test actions remain explicit host callbacks, separate from these fixtures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RidePreviewScreen(onBack: () -> Unit, onStartMediaTest: () -> Unit, onStartWeatherTest: () -> Unit = {}) {
    BackHandler(onBack = onBack)
    if (!BuildConfig.DEBUG) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Text("開發預覽僅限 debug 版本")
            TextButton(onClick = onBack) { Text("返回") }
        }
        return
    }
    var stage by remember { mutableStateOf(RideStage.CONNECTED) }
    var mode by remember { mutableStateOf<Gen1Input.Function?>(Gen1Input.Function.MODE_SAB) }
    var page by remember { mutableStateOf(RidePageStage.MENU) }
    var menuSelected by remember { mutableStateOf(MainMenuSelection.Item.NAVIGATION) }
    var messageReadStatus by remember { mutableStateOf(MessageReadStatus.NONE) }
    var replyText by remember { mutableStateOf<String?>(null) }
    var replyChoice by remember { mutableIntStateOf(0) }
    var replyResult by remember { mutableStateOf<MessageReplyResult?>(null) }
    val replyStages = remember { setOf(RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_LISTENING,
        RidePageStage.MESSAGE_CONFIRMATION, RidePageStage.MESSAGE_TRY_AGAIN, RidePageStage.MESSAGE_SENDING,
        RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT) }
    fun clearReply() { replyText = null; replyChoice = 0; replyResult = null }
    val quickReplies = remember { (1..5).map { "合成快速回覆 $it（不會送出）" } }
    var navigationStatus by remember { mutableStateOf(NavigationStatus.OBSERVED) }
    var navigationCancelResult by remember { mutableStateOf<NavigationCancelResult?>(null) }
    var callKind by remember { mutableStateOf(CallKind.INCOMING) }
    var callResult by remember { mutableStateOf<CallDispatchResult?>(null) }
    var huCallPreview by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var navigationPreview by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var modeRequest by remember { mutableStateOf(ModeRequestStage.IDLE) }
    var previewStatus by remember { mutableStateOf("READY") }
    var previewPlaying by remember { mutableStateOf(true) }
    var previewTrack by remember { mutableIntStateOf(1) }
    var previewVolume by remember { mutableIntStateOf(60) }
    var statusChoice by remember { mutableIntStateOf(0) }
    var statusReport by remember { mutableStateOf<RideStatusReportState?>(null) }
    LaunchedEffect(page, stage, mode) {
        if (page != RidePageStage.STATUS_REPORT || stage != RideStage.CONNECTED || mode != Gen1Input.Function.MODE_SAB)
            statusReport = null
        if (stage != RideStage.CONNECTED || mode != Gen1Input.Function.MODE_SAB) statusChoice = 0
    }
    val previewArtwork = remember { syntheticRideArtwork() }
    DisposableEffect(previewArtwork) { onDispose { previewArtwork.recycle() } }
    val phoneStatus = when (previewStatus) {
        "LOW" -> RideDeviceStatus(12, false, RideSignal(SignalAvailability.NO_SIM), RideSignal(SignalAvailability.OFF), false)
        "UNKNOWN" -> RideDeviceStatus()
        else -> RideDeviceStatus(76, false, RideSignal(SignalAvailability.READY, 3, "合成電信"),
            RideSignal(SignalAvailability.READY, 4, "OpenRideSync-Lab"), true)
    }
    val previewWeather = remember { HomeWeatherState(signedIn = true, locationGranted = true,
        current = CurrentWeather(WeatherWire.Weather.getDefaultInstance(), WeatherReading(28f, 803,
            "合成地點", "多雲（合成）", "Clouds", true)), fetchedAtMillis = System.currentTimeMillis()) }
    val mock = RideState(stage = stage, name = "CBR500R（模擬）", mode = mode,
        errorCode = if (stage == RideStage.FAILED) "TIMEOUT" else null, modeRequest = modeRequest)
    // Message and weather pages require SAB; all values here are local render fixtures.
    val mockPage = RidePageState(page, receiptStatus = null, messageReadStatus = messageReadStatus,
        navigationCancelResult = navigationCancelResult, callDispatchResult = callResult,
        messageLabel = "合成訊息標籤（非真實通知）".takeIf { page in replyStages || page in setOf(RidePageStage.MESSAGE, RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ) },
        messageCanRead = page == RidePageStage.MESSAGE_PROMPT,
        messageCanReply = stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB &&
            (page == RidePageStage.MESSAGE && messageReadStatus == MessageReadStatus.COMPLETE ||
                page == RidePageStage.MESSAGE_READ && messageReadStatus in setOf(MessageReadStatus.READING, MessageReadStatus.COMPLETE)),
        replyChoices = when (page) {
            RidePageStage.MESSAGE_CATEGORY -> RideMessageReply.categoryChoices
            RidePageStage.MESSAGE_QUICK_LIST -> quickReplies
            else -> emptyList()
        }, replyChoice = replyChoice, replyText = replyText, replyResult = replyResult,
        menuItems = MainMenuSelection.Item.entries,
        menuSelected = menuSelected,
        volumePercentage = previewVolume,
        weather = RideWeatherState(if (page == RidePageStage.WEATHER) RideWeatherStage.SPEAKING else RideWeatherStage.COMPLETE,
            current = previewWeather.current), statusChoice = statusChoice, statusReport = statusReport,
        statusCanReport = stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB)
    val previewMedia = MediaState(MediaAccess.GRANTED,
        sessions = listOf(MediaSessionChoice("preview-only", "合成播放器", "synthetic.invalid")),
        selectedId = "preview-only", title = "午後的旅程 $previewTrack（合成）", artist = "合成演出者",
        album = "沿途風景（合成）", playback = if (previewPlaying) MediaPlayback.PLAYING else MediaPlayback.PAUSED,
        actions = android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
            android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT, artwork = previewArtwork)
    val callInbox = if (stage == RideStage.CONNECTED &&
        ((mode == Gen1Input.Function.MODE_SAB && page in setOf(RidePageStage.CALL, RidePageStage.CALL_CANCEL)) ||
            (mode == Gen1Input.Function.MODE_HU && huCallPreview))) {
        CallInboxState(CallAccess.READY, listOf(CallItem("preview-call", "合成通話 App", "synthetic.invalid",
            "合成通話對象（非真實來電）", callKind,
            if (callResult != null) emptySet() else if (callKind == CallKind.INCOMING)
                setOf(CallAction.ANSWER, CallAction.DECLINE) else setOf(CallAction.HANG_UP), false)))
    } else CallInboxState()
    val navigation = if (stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB &&
        page in setOf(RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL)) {
        NavigationObservation(navigationStatus,
            if (navigationStatus == NavigationStatus.OBSERVED)
                MapsInstruction("合成導航指示：前方右轉", "200 m（合成）", "合成目的地，非真實路線", NavigationProjection.Maneuver.RIGHT)
            else null, sourceId = "preview-only", canCancel = true)
    } else NavigationObservation(NavigationStatus.WAITING)

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()
        .consumeWindowInsets(WindowInsets.safeDrawing).testTag("preview_frame")) {
        val panelWidth = minOf(380.dp, maxWidth * 0.74f)
        val panelHeight = maxHeight * 0.80f
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.weight(1f)) {
                    Text("開發預覽・模擬／非實車", Modifier.padding(8.dp).testTag("ride_preview_banner"),
                        style = MaterialTheme.typography.labelMedium)
                }
                FilledTonalButton(onClick = { controlsVisible = !controlsVisible },
                    modifier = Modifier.testTag("preview_controls_toggle")) { Text("模擬選項") }
            }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Box(Modifier.fillMaxSize().testTag("preview_display")) {
            RideStatusLayout(phoneStatus.takeIf { stage == RideStage.CONNECTED },
                if (previewStatus == "UNKNOWN") HomeWeatherState(signedIn = true, locationGranted = true) else previewWeather,
                onWeather = if (mode == Gen1Input.Function.MODE_SAB && page == RidePageStage.MENU) ({
                    clearReply()
                    menuSelected = MainMenuSelection.Item.WEATHER
                    statusChoice = 0; statusReport = null
                    page = RidePageStage.STATUS_MENU; navigationPreview = false
                    feedback = "合成狀態選單；沒有查詢服務或播放語音。"
                }) else null,
                weatherSelected = stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB &&
                    (page == RidePageStage.WEATHER || page == RidePageStage.MENU && menuSelected == MainMenuSelection.Item.WEATHER),
                statusSelected = statusChoice.takeIf { stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB &&
                    page in setOf(RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT) }) {
            RidePhoneContent(mock, onRequestSab = {
                if (stage == RideStage.CONNECTED && mode != Gen1Input.Function.MODE_SAB) {
                    modeRequest = ModeRequestStage.WAITING_FOR_MODE
                    feedback = "模擬：切換要求已接受，未傳送車輛命令。請用側邊選項模擬 SAB 回報。"
                }
            }) {
                if (navigationPreview) NavigationPreview { navigationPreview = false }
                else RideContent(mock, false, previewMedia, mockPage, onBack,
                    onGarage = { feedback = "模擬：車庫入口，不開啟配對。" },
                    onMedia = { feedback = "模擬：播放器入口；真正媒體測試請使用側邊模擬選單的明確按鈕。" },
                    onDiagnostics = { feedback = "模擬：不建立或匯出實車診斷。" },
                    onDisconnect = { clearReply(); stage = RideStage.IDLE; page = RidePageStage.MENU; navigationCancelResult = null; callResult = null; huCallPreview = false; modeRequest = ModeRequestStage.IDLE
                        feedback = "已重設模擬連線；真實連線未變更。" },
                    onCancelPermission = { feedback = "模擬：不提出權限請求。" },
                    onPermissions = { feedback = "模擬：請返回帳號頁的「權限與功能」調整真實授權。" },
                    onRetryFavorite = { feedback = "模擬：不啟動或播放音訊；真正媒體測試請使用側邊模擬選單的明確按鈕。" },
                    observation = navigation,
                    onNavigation = { feedback = "模擬：手機導航入口；此預覽不開啟 Maps 或真實導航。" },
                    callInbox = callInbox,
                    onCalls = { feedback = "模擬：手機通話入口；不讀取通知或操作真實通話。" },
                    onFunction = { item ->
                        if (stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB) {
                            clearReply()
                            statusChoice = 0; statusReport = null
                            menuSelected = item
                            page = when (item) {
                                MainMenuSelection.Item.WEATHER -> RidePageStage.STATUS_MENU
                                MainMenuSelection.Item.NAVIGATION -> RidePageStage.NAVIGATION
                                MainMenuSelection.Item.CALL -> RidePageStage.CALL
                                MainMenuSelection.Item.MESSAGE -> RidePageStage.MESSAGE
                                MainMenuSelection.Item.MUSIC -> RidePageStage.MUSIC
                                MainMenuSelection.Item.VOLUME -> RidePageStage.VOLUME
                            }
                            navigationPreview = false
                        }
                    }, onMenu = { clearReply(); page = RidePageStage.MENU; messageReadStatus = MessageReadStatus.NONE;
                        navigationCancelResult = null; callResult = null },
                    onTrips = { feedback = "模擬：騎乘紀錄入口；沒有查詢帳號或建立旅程。" },
                    onAccount = { feedback = "模擬：帳號入口；沒有變更登入狀態。" },
                    onQuickReplies = { feedback = "模擬：快速回覆設定入口；沒有變更本機範本。" },
                    onRecognitionLanguage = { feedback = "模擬：辨識語言入口；沒有查詢辨識服務或啟動錄音。" },
                    onAction = { function ->
                        if (stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB) {
                            feedback = "合成操作：${function.name}；沒有執行媒體、語音、通知或車輛動作。"
                            if (function == Gen1Input.Function.MENU) {
                                clearReply(); page = RidePageStage.MENU; messageReadStatus = MessageReadStatus.NONE
                                navigationCancelResult = null; callResult = null
                            } else when (page) {
                                RidePageStage.STATUS_MENU -> when (function) {
                                    Gen1Input.Function.NEXT -> statusChoice = (statusChoice + 1).coerceAtMost(3)
                                    Gen1Input.Function.PREVIOUS -> statusChoice = (statusChoice - 1).coerceAtLeast(0)
                                    Gen1Input.Function.SELECT -> if (statusChoice == 0) page = RidePageStage.WEATHER else {
                                        val kind = RideStatusKind.entries[statusChoice - 1]
                                        statusReport = RideStatusReportState(kind, RideStatusReportStage.SPEAKING,
                                            "合成資料：" + rideStatusReportText(kind, phoneStatus, true))
                                        page = RidePageStage.STATUS_REPORT
                                    }
                                    Gen1Input.Function.BACK -> page = RidePageStage.MENU
                                    else -> Unit
                                }
                                RidePageStage.STATUS_REPORT, RidePageStage.WEATHER -> when (function) {
                                    Gen1Input.Function.BACK -> { statusReport = null; page = RidePageStage.STATUS_MENU }
                                    Gen1Input.Function.PREVIOUS -> previewVolume = (previewVolume + 5).coerceAtMost(100)
                                    Gen1Input.Function.NEXT -> previewVolume = (previewVolume - 5).coerceAtLeast(0)
                                    Gen1Input.Function.SELECT -> if (statusReport?.stage == RideStatusReportStage.FAILED)
                                        statusReport = statusReport?.kind?.let { kind -> RideStatusReportState(kind,
                                            RideStatusReportStage.SPEAKING, "合成資料：" + rideStatusReportText(kind, phoneStatus, true)) }
                                    else -> Unit
                                }
                                RidePageStage.MUSIC -> when (function) {
                                    Gen1Input.Function.BACK -> previewPlaying = !previewPlaying
                                    Gen1Input.Function.SELECT -> previewTrack++
                                    else -> Unit
                                }
                                RidePageStage.VOLUME -> when (function) {
                                    Gen1Input.Function.PREVIOUS -> previewVolume = (previewVolume + 5).coerceAtMost(100)
                                    Gen1Input.Function.NEXT -> previewVolume = (previewVolume - 5).coerceAtLeast(0)
                                    else -> Unit
                                }
                                RidePageStage.NAVIGATION -> if (function == Gen1Input.Function.BACK) page = RidePageStage.NAVIGATION_CANCEL
                                RidePageStage.NAVIGATION_CANCEL -> {
                                    if (function == Gen1Input.Function.SELECT) navigationCancelResult = NavigationCancelResult.DISPATCHED
                                    page = RidePageStage.NAVIGATION
                                }
                                RidePageStage.CALL -> if (function == Gen1Input.Function.BACK && callKind == CallKind.ONGOING)
                                    page = RidePageStage.CALL_CANCEL else callResult = CallDispatchResult.DISPATCHED
                                RidePageStage.CALL_CANCEL -> {
                                    if (function == Gen1Input.Function.SELECT) callResult = CallDispatchResult.DISPATCHED
                                    page = RidePageStage.CALL
                                }
                                RidePageStage.MESSAGE_PROMPT -> if (function == Gen1Input.Function.SELECT) {
                                    page = RidePageStage.MESSAGE_READ; messageReadStatus = MessageReadStatus.READING
                                } else if (function == Gen1Input.Function.BACK) page = RidePageStage.MENU
                                RidePageStage.MESSAGE_READ -> when (function) {
                                    Gen1Input.Function.SELECT -> { clearReply(); page = RidePageStage.MESSAGE_CATEGORY }
                                    Gen1Input.Function.BACK -> { clearReply(); page = RidePageStage.MENU }
                                    else -> Unit
                                }
                                RidePageStage.MESSAGE -> if (function == Gen1Input.Function.SELECT) {
                                    if (messageReadStatus == MessageReadStatus.COMPLETE) { clearReply(); page = RidePageStage.MESSAGE_CATEGORY }
                                    else messageReadStatus = MessageReadStatus.COMPLETE
                                }
                                RidePageStage.MESSAGE_CATEGORY -> when (function) {
                                    Gen1Input.Function.NEXT -> replyChoice = (replyChoice + 1).coerceAtMost(RideMessageReply.categoryChoices.lastIndex)
                                    Gen1Input.Function.PREVIOUS -> replyChoice = (replyChoice - 1).coerceAtLeast(0)
                                    Gen1Input.Function.SELECT -> if (replyChoice == 0) {
                                        replyText = null; page = RidePageStage.MESSAGE_LISTENING
                                    } else {
                                        replyChoice = 0; page = RidePageStage.MESSAGE_QUICK_LIST
                                    }
                                    Gen1Input.Function.BACK -> { clearReply(); page = RidePageStage.MESSAGE }
                                    else -> Unit
                                }
                                RidePageStage.MESSAGE_QUICK_LIST -> when (function) {
                                    Gen1Input.Function.NEXT -> replyChoice = (replyChoice + 1).coerceAtMost(quickReplies.lastIndex)
                                    Gen1Input.Function.PREVIOUS -> replyChoice = (replyChoice - 1).coerceAtLeast(0)
                                    Gen1Input.Function.SELECT -> { replyText = null; page = RidePageStage.MESSAGE_SENDING }
                                    Gen1Input.Function.BACK -> { replyChoice = 1; page = RidePageStage.MESSAGE_CATEGORY }
                                    else -> Unit
                                }
                                RidePageStage.MESSAGE_CONFIRMATION -> when (function) {
                                    Gen1Input.Function.SELECT -> { replyText = null; page = RidePageStage.MESSAGE_SENDING }
                                    Gen1Input.Function.BACK -> { replyText = null; page = RidePageStage.MESSAGE_LISTENING }
                                    else -> Unit
                                }
                                RidePageStage.MESSAGE_TRY_AGAIN -> when (function) {
                                    Gen1Input.Function.SELECT -> { replyText = null; page = RidePageStage.MESSAGE_LISTENING }
                                    Gen1Input.Function.BACK -> { clearReply(); page = RidePageStage.MESSAGE }
                                    else -> Unit
                                }
                                RidePageStage.MESSAGE_LISTENING, RidePageStage.MESSAGE_SENDING,
                                RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT -> if (function == Gen1Input.Function.BACK) {
                                    clearReply(); page = RidePageStage.MESSAGE
                                }
                                else -> Unit
                            }
                        }
                    })
            }
            }
        }
        if (controlsVisible) {
            Card(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(panelWidth)
                .heightIn(max = panelHeight).testTag("preview_controls_panel"), elevation = CardDefaults.cardElevation(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("模擬選項", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { controlsVisible = false }, modifier = Modifier.testTag("preview_controls_close")) {
                            Text("收合")
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("這些選項只改變模擬畫面，不建立藍牙連線，也不代表車端驗證。", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("READY" to "狀態列正常", "LOW" to "低電量與離線", "UNKNOWN" to "狀態未知").forEach { (value, label) ->
                                FilterChip(selected = previewStatus == value, onClick = { previewStatus = value },
                                    label = { Text(label) }, modifier = Modifier.testTag("preview_status_$value"))
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("SAB" to Gen1Input.Function.MODE_SAB, "HU" to Gen1Input.Function.MODE_HU, "未知" to null).forEach { (label, value) ->
                                FilterChip(selected = mode == value, onClick = {
                                    mode = value
                                    if (value != Gen1Input.Function.MODE_SAB) {
                                        clearReply()
                                        if (page in replyStages) page = RidePageStage.MENU
                                    }
                                    if (value == Gen1Input.Function.MODE_SAB) modeRequest = ModeRequestStage.IDLE
                                    huCallPreview = false
                                    if (page in setOf(RidePageStage.WEATHER, RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT,
                                            RidePageStage.MESSAGE, RidePageStage.MESSAGE_PROMPT,
                                            RidePageStage.MESSAGE_READ, RidePageStage.NAVIGATION,
                                            RidePageStage.NAVIGATION_CANCEL, RidePageStage.CALL, RidePageStage.CALL_CANCEL) && value != Gen1Input.Function.MODE_SAB)
                                        page = RidePageStage.MENU
                                    messageReadStatus = MessageReadStatus.NONE
                                    navigationCancelResult = null
                                    callResult = null
                                }, label = { Text("模式 $label") },
                                    modifier = Modifier.testTag("preview_mode_$label"))
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("連接中" to RideStage.CONNECTING, "重新連線" to RideStage.RECONNECTING,
                                "失敗" to RideStage.FAILED, "已連線" to RideStage.CONNECTED).forEach { (label, value) ->
                                FilterChip(selected = stage == value, onClick = { stage = value
                                    if (value != RideStage.CONNECTED) { clearReply(); if (page in replyStages || page in setOf(
                                            RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT, RidePageStage.WEATHER)) page = RidePageStage.MENU;
                                        huCallPreview = false; callResult = null; modeRequest = ModeRequestStage.IDLE } }, label = { Text(label) },
                                    modifier = Modifier.testTag("preview_stage_${value.name}"))
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("主選單" to RidePageStage.MENU, "音樂" to RidePageStage.MUSIC, "音量" to RidePageStage.VOLUME,
                                "天氣" to RidePageStage.WEATHER, "訊息" to RidePageStage.MESSAGE,
                                "狀態匯報" to RidePageStage.STATUS_MENU,
                                "新訊息提示（合成）" to RidePageStage.MESSAGE_PROMPT,
                                "訊息讀取（合成）" to RidePageStage.MESSAGE_READ,
                                "回覆方式（合成）" to RidePageStage.MESSAGE_CATEGORY,
                                "快速回覆（合成）" to RidePageStage.MESSAGE_QUICK_LIST,
                                "等待辨識（合成）" to RidePageStage.MESSAGE_LISTENING,
                                "回覆確認（合成）" to RidePageStage.MESSAGE_CONFIRMATION,
                                "辨識失敗（合成）" to RidePageStage.MESSAGE_TRY_AGAIN,
                                "等待傳送結果（合成）" to RidePageStage.MESSAGE_SENDING,
                                "傳送成功（合成）" to RidePageStage.MESSAGE_SENT,
                                "傳送失敗（合成）" to RidePageStage.MESSAGE_NOT_SENT,
                                "導航（合成）" to RidePageStage.NAVIGATION,
                                "結束導航確認（合成）" to RidePageStage.NAVIGATION_CANCEL,
                                "通話（合成）" to RidePageStage.CALL,
                                "結束通話確認（合成）" to RidePageStage.CALL_CANCEL).forEach { (label, value) ->
                                FilterChip(selected = page == value, onClick = { clearReply(); page = value;
                                    if (value == RidePageStage.WEATHER) statusChoice = 0
                                    if (value == RidePageStage.MESSAGE_CONFIRMATION) replyText = "合成回覆內容（不會送出）"
                                    if (value == RidePageStage.MESSAGE_SENT) replyResult = MessageReplyResult.DISPATCHED
                                    if (value == RidePageStage.MESSAGE_NOT_SENT) replyResult = MessageReplyResult.UNAVAILABLE
                                    huCallPreview = false; messageReadStatus = MessageReadStatus.NONE
                                    menuSelected = when (value) {
                                        RidePageStage.MUSIC, RidePageStage.NO_SESSION -> MainMenuSelection.Item.MUSIC
                                        RidePageStage.VOLUME -> MainMenuSelection.Item.VOLUME
                                        RidePageStage.WEATHER, RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT -> MainMenuSelection.Item.WEATHER
                                        RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL -> MainMenuSelection.Item.NAVIGATION
                                        RidePageStage.CALL, RidePageStage.CALL_CANCEL -> MainMenuSelection.Item.CALL
                                        RidePageStage.MESSAGE, RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ,
                                        RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_LISTENING, RidePageStage.MESSAGE_CONFIRMATION,
                                        RidePageStage.MESSAGE_TRY_AGAIN, RidePageStage.MESSAGE_SENDING, RidePageStage.MESSAGE_SENT,
                                        RidePageStage.MESSAGE_NOT_SENT -> MainMenuSelection.Item.MESSAGE
                                        else -> menuSelected
                                    }
                                    navigationCancelResult = null; callResult = null
                                    if (value == RidePageStage.CALL_CANCEL) callKind = CallKind.ONGOING },
                                    enabled = (value !in replyStages && value !in setOf(RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT) ||
                                        stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB) &&
                                        (value !in setOf(RidePageStage.WEATHER, RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT, RidePageStage.MESSAGE,
                                        RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ,
                                        RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL,
                                        RidePageStage.CALL, RidePageStage.CALL_CANCEL) || mode == Gen1Input.Function.MODE_SAB),
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("preview_page_${value.name}"))
                            }
                        }
                        if (page in setOf(RidePageStage.WEATHER, RidePageStage.STATUS_REPORT)) {
                            Text("合成狀態播報：不讀取裝置或啟動語音。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { statusReport = null; page = RidePageStage.STATUS_MENU },
                                modifier = Modifier.testTag("preview_status_complete"),
                                enabled = stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB) {
                                Text("完成狀態播報（合成）")
                            }
                            if (page == RidePageStage.STATUS_REPORT) TextButton(onClick = {
                                statusReport = statusReport?.copy(stage = RideStatusReportStage.FAILED, detail = null, error = "UNAVAILABLE")
                            }, modifier = Modifier.testTag("preview_status_failed"),
                                enabled = stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_SAB) {
                                Text("狀態播報失敗（合成）")
                            }
                        }
                        FilterChip(selected = huCallPreview, onClick = {
                            clearReply(); huCallPreview = true; mode = Gen1Input.Function.MODE_HU; stage = RideStage.CONNECTED
                            page = RidePageStage.MENU; callKind = CallKind.INCOMING; callResult = null
                            navigationCancelResult = null
                        }, label = { Text("HU 來電提示（合成）") }, modifier = Modifier.testTag("preview_hu_call"))
                        if (huCallPreview && mode == Gen1Input.Function.MODE_HU && stage == RideStage.CONNECTED) {
                            val popup = callInbox.calls.singleOrNull()?.let { CallProjection.popup(it.copy(actions = emptySet()), "合成來電") }
                            if (popup != null) Card(Modifier.fillMaxWidth().testTag("preview_hu_call_popup"),
                                colors = CardDefaults.cardColors(containerColor = Color(0xff717171), contentColor = Color.White)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("HU 來電資訊・合成渲染示意")
                                    Text(popup.text)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("儀表模式不將手把按鍵交給 App 處理。")
                                    }
                                    Text("灰色色彩編號 ${popup.background}；沒有讀取通知、模式要求或車端傳送。不是實際儀表像素驗證。")
                                }
                            }
                        }
                        if (page in setOf(RidePageStage.CALL, RidePageStage.CALL_CANCEL)) {
                            Text("通話對象與結果全部是合成樣本；不讀取通知、不接聽、拒接、掛斷或撥號。", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CallKind.entries.forEach { kind ->
                                    FilterChip(selected = callKind == kind, onClick = {
                                        callKind = kind; callResult = null; page = RidePageStage.CALL
                                    }, label = { Text(if (kind == CallKind.INCOMING) "合成來電" else "合成進行中") },
                                        modifier = Modifier.testTag("preview_call_${kind.name}"))
                                }
                                FilterChip(selected = callResult == CallDispatchResult.DISPATCHED,
                                    onClick = { callResult = CallDispatchResult.DISPATCHED },
                                    label = { Text("合成：請求已交付") }, modifier = Modifier.testTag("preview_call_DISPATCHED"))
                            }
                        }
                        if (page in setOf(RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL)) {
                            Text("只顯示合成導航通知與結束要求結果，不開啟 Maps、不讀取通知、不播放語音，也不送出結束要求。",
                                style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("合成指示" to NavigationStatus.OBSERVED, "讀取中" to NavigationStatus.LOADING,
                                    "未觀察到通知" to NavigationStatus.WAITING).forEach { (label, value) ->
                                    FilterChip(selected = navigationStatus == value, onClick = { navigationStatus = value },
                                        label = { Text(label) }, modifier = Modifier.testTag("preview_navigation_${value.name}"))
                                }
                            }
                            if (page == RidePageStage.NAVIGATION_CANCEL) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("未送出" to null, "已請求（合成）" to NavigationCancelResult.DISPATCHED,
                                        "無法送出（合成）" to NavigationCancelResult.UNAVAILABLE).forEach { (label, value) ->
                                        FilterChip(selected = navigationCancelResult == value, onClick = { navigationCancelResult = value },
                                            label = { Text(label) }, modifier = Modifier.testTag("preview_navigation_result_${value?.name ?: "NONE"}"))
                                    }
                                }
                            }
                        }
                        if (page in replyStages) {
                            Text("合成回覆預覽：不使用麥克風、TTS、通知或網路，也不會送出任何訊息。", style = MaterialTheme.typography.bodySmall)
                            if (page == RidePageStage.MESSAGE_LISTENING) {
                                OutlinedButton(onClick = {
                                    replyText = "這是合成辨識的回覆內容（不會送出）"; page = RidePageStage.MESSAGE_CONFIRMATION
                                }, modifier = Modifier.testTag("preview_reply_recognize_success")) { Text("合成辨識成功") }
                                OutlinedButton(onClick = { replyText = null; page = RidePageStage.MESSAGE_TRY_AGAIN },
                                    modifier = Modifier.testTag("preview_reply_recognize_failure")) { Text("合成辨識失敗") }
                            }
                            if (page == RidePageStage.MESSAGE_SENDING) {
                                listOf(true, false).forEach { success ->
                                    OutlinedButton(onClick = {
                                        replyText = null
                                        replyResult = if (success) MessageReplyResult.DISPATCHED else MessageReplyResult.UNAVAILABLE
                                        page = if (success) RidePageStage.MESSAGE_SENT else RidePageStage.MESSAGE_NOT_SENT
                                        feedback = "合成傳送結果；沒有實際傳送或操作通知。"
                                    }, modifier = Modifier.testTag("preview_reply_send_${if (success) "success" else "failure"}")) {
                                        Text(if (success) "合成傳送成功" else "合成傳送失敗")
                                    }
                                }
                            }
                        }
                        if (page in setOf(RidePageStage.MESSAGE, RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ)) {
                            Text("以下只模擬訊息播報狀態，不讀取通知或播放語音。", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("尚未播報" to MessageReadStatus.NONE, "播報中" to MessageReadStatus.READING,
                                    "已完成" to MessageReadStatus.COMPLETE, "失敗" to MessageReadStatus.FAILED).forEach { (label, value) ->
                                    FilterChip(selected = messageReadStatus == value, onClick = { messageReadStatus = value },
                                        label = { Text(label) }, modifier = Modifier.testTag("preview_message_${value.name}"))
                                }
                            }
                        }
                        if (mode != Gen1Input.Function.MODE_SAB)
                            Text("天氣、訊息、導航與通話 Live 預覽需要 SAB 模式；HU 來電提示請使用獨立的合成選項。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { navigationPreview = true }, modifier = Modifier.fillMaxWidth().testTag("preview_navigation")) {
                            Text("檢查導航轉向資料（合成）")
                        }
                        OutlinedButton(onClick = onStartMediaTest, modifier = Modifier.fillMaxWidth().testTag("preview_start_media_test")) {
                            Text("啟動播放器並嘗試播放音訊")
                        }
                        Text("此按鈕會執行真正的手機媒體測試，可能發出聲音；其他選項只改模擬畫面。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onStartWeatherTest, modifier = Modifier.fillMaxWidth().testTag("preview_start_weather_test")) {
                            Text("測試真實天氣與語音匯報")
                        }
                        Text("天氣測試會另行確認定位與語音播放；不需要車輛。", style = MaterialTheme.typography.bodySmall)
                        feedback?.let { Text(it, Modifier.testTag("preview_local_feedback"), style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("preview_exit")) {
                            Text("離開模擬模式")
                        }
                    }
                }
            }
        }
        }
        }
    }
    BackHandler(enabled = controlsVisible) { controlsVisible = false }
}

/** A small, locally drawn cover fixture. Never a real player icon, download or media observation. */
internal fun syntheticRideArtwork(): Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { bitmap ->
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(android.graphics.Color.rgb(224, 120, 64))
    paint.color = android.graphics.Color.rgb(255, 218, 129)
    canvas.drawCircle(118f, 42f, 28f, paint)
    paint.color = android.graphics.Color.rgb(32, 109, 113)
    canvas.drawPath(android.graphics.Path().apply {
        moveTo(0f, 125f); lineTo(76f, 48f); lineTo(160f, 140f); lineTo(160f, 160f); lineTo(0f, 160f); close()
    }, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 17f
    canvas.drawText("SYNTHETIC", 12f, 148f, paint)
}
