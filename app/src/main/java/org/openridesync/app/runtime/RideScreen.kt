package org.openridesync.app.runtime

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.media.MediaState
import org.openridesync.app.media.MediaPlayback
import org.openridesync.app.media.FavoriteStartStatus
import org.openridesync.app.media.favoriteStartMessage
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.navigation.NavigationObservation
import org.openridesync.app.navigation.NavigationStatus
import org.openridesync.app.navigation.NavigationCancelResult
import org.openridesync.app.navigation.navigationStatusText
import org.openridesync.app.calls.*
import org.openridesync.app.ui.OemUiAsset
import org.openridesync.app.ui.OemUiIcon
import org.openridesync.app.ui.OemRideAsset
import org.openridesync.app.ui.RideIcon
import org.openridesync.app.weather.RideWeatherStage
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

@Composable
internal fun RideScreen(onBack: () -> Unit, onGarage: () -> Unit, onMedia: () -> Unit,
    onDiagnostics: () -> Unit, model: RideViewModel = viewModel(), onPermissions: () -> Unit = {},
    onNavigation: () -> Unit = {}, onCalls: () -> Unit = {}, onTrips: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null, onQuickReplies: (() -> Unit)? = null,
    onRecognitionLanguage: (() -> Unit)? = null) {
    val state by model.connection.state.collectAsStateWithLifecycle()
    val permission by model.permissionRequest.collectAsStateWithLifecycle()
    val media by model.connection.media.collectAsStateWithLifecycle()
    val page by model.connection.musicPage.collectAsStateWithLifecycle()
    val navigation by model.connection.navigation.collectAsStateWithLifecycle()
    val callInbox by model.connection.callInbox.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    RideContent(state, permission != null, media, page, onBack, onGarage, onMedia, onDiagnostics,
        model.connection::disconnect, model::cancelPermissionRequest, onPermissions,
        onRetryFavorite = { model.connection.retryFavoriteMusic() }, observation = navigation, onNavigation = onNavigation,
        callInbox = callInbox, onCalls = onCalls,
        onFunction = { model.connection.openFunction(it) }, onMenu = { model.connection.returnToMenu() },
        onAction = { model.connection.performAction(it) }, onTrips = onTrips, onAccount = onAccount,
        onQuickReplies = onQuickReplies, onRecognitionLanguage = onRecognitionLanguage)
}

/** Render only service observations. No connection, transport or mode request is made on entry. */
@Composable
internal fun RideContent(state: RideState, permissionPending: Boolean, media: MediaState?, page: RidePageState,
    onBack: () -> Unit, onGarage: () -> Unit, onMedia: () -> Unit, onDiagnostics: () -> Unit,
    onDisconnect: () -> Unit, onCancelPermission: () -> Unit, onPermissions: () -> Unit = {},
    onRetryFavorite: (() -> Unit)? = null, observation: NavigationObservation = NavigationObservation(),
    onNavigation: () -> Unit = {}, callInbox: CallInboxState = CallInboxState(), onCalls: () -> Unit = {},
    onFunction: ((MainMenuSelection.Item) -> Unit)? = null, onMenu: (() -> Unit)? = null,
    onAction: ((Gen1Input.Function) -> Unit)? = null, onTrips: (() -> Unit)? = null, onAccount: (() -> Unit)? = null,
    onQuickReplies: (() -> Unit)? = null, onRecognitionLanguage: (() -> Unit)? = null) {
    var detailsExpanded by remember { mutableStateOf(false) }
    var voicePermission by remember { mutableStateOf(false) }
    if (voicePermission && state.mode == Gen1Input.Function.MODE_SAB)
        PermissionDialog(listOf(AppAccess.VOICE), onConfirm = { voicePermission = false },
            onDismiss = { voicePermission = false })
    LaunchedEffect(state.mode) { if (state.mode != Gen1Input.Function.MODE_SAB) voicePermission = false }
    val selected = media?.sessions?.singleOrNull { it.id == media.selectedId }
    val showCalls = page.stage in setOf(RidePageStage.CALL, RidePageStage.CALL_CANCEL) ||
        callInbox.calls.isNotEmpty() || page.callDispatchResult != null
    val showNavigation = page.stage in setOf(RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL) ||
        observation.instruction != null || observation.status in setOf(NavigationStatus.OBSERVED,
            NavigationStatus.LOADING, NavigationStatus.AMBIGUOUS, NavigationStatus.UNSUPPORTED) ||
        page.navigationCancelResult != null
    val showMusic = page.stage in setOf(RidePageStage.MUSIC, RidePageStage.NO_SESSION) ||
        selected != null || state.favoriteStart !in setOf(FavoriteStartStatus.NONE,
            FavoriteStartStatus.NO_SELECTION, FavoriteStartStatus.USER_SELECTED)
    MaterialTheme(colorScheme = if (state.stage == RideStage.CONNECTED) RideColors else MaterialTheme.colorScheme) {
    val readableCardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface)
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.stage == RideStage.CONNECTED) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page.stage in setOf(RidePageStage.MUSIC, RidePageStage.NO_SESSION, RidePageStage.VOLUME,
                            RidePageStage.WEATHER, RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT,
                            RidePageStage.MESSAGE, RidePageStage.NAVIGATION) && onMenu != null)
                        TextButton(onClick = onMenu, modifier = Modifier.testTag("ride_main_menu")) { Text("主選單") }
                    else if (page.stage in setOf(RidePageStage.MENU, RidePageStage.DISABLED,
                            RidePageStage.SPLASH, RidePageStage.STARTUP_FAILED, RidePageStage.UNSUPPORTED))
                        TextButton(onClick = onBack) { Text("返回") }
                    Text(state.name?.takeIf(String::isNotBlank) ?: "RoadSync", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    onTrips?.let { IconButton(onClick = it, modifier = Modifier.testTag("ride_trips")
                        .semantics { contentDescription = "騎乘紀錄" }) { RideIcon(OemRideAsset.TRIPS, size = 28.dp) } }
                    onAccount?.let { IconButton(onClick = it, modifier = Modifier.testTag("ride_account")
                        .semantics { contentDescription = "帳號" }) { RideIcon(OemRideAsset.ACCOUNT, size = 28.dp) } }
                    IconButton(onClick = onDisconnect, modifier = Modifier.testTag("ride_disconnect")
                        .semantics { contentDescription = "中斷連線" }) { RideIcon(OemRideAsset.SHUTDOWN, size = 28.dp) }
                }
            } else TextButton(onClick = onBack) { Text("返回") }
            if (state.stage != RideStage.CONNECTED) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OemUiIcon(OemUiAsset.BLUETOOTH)
                Text("儀表連線", style = MaterialTheme.typography.headlineMedium)
            }
            if (state.stage != RideStage.CONNECTED)
                RideStatusCard(state, permissionPending, onDisconnect, onCancelPermission, compact = true)
            if (state.stage == RideStage.IDLE && !permissionPending) {
                Text("尚未連線", Modifier.testTag("ride_idle"), style = MaterialTheme.typography.titleLarge)
                Text("先在車庫選擇已配對車輛並連線。沒有已保存車輛時，可選擇車型開始配對。")
            }
            if (state.stage == RideStage.CONNECTED) {
                Card(Modifier.fillMaxWidth().testTag("ride_display_state"), colors = readableCardColors) {
                    Column(Modifier.padding(if (page.stage == RidePageStage.MENU) 0.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val title = when (page.stage) {
                            RidePageStage.MENU -> "主選單"
                            RidePageStage.MUSIC, RidePageStage.NO_SESSION -> "音樂"
                            RidePageStage.VOLUME -> "音量"
                            RidePageStage.WEATHER -> "天氣匯報"
                            RidePageStage.STATUS_MENU -> "狀態匯報"
                            RidePageStage.STATUS_REPORT -> rideStatusChoices.getOrNull(page.statusChoice) ?: "狀態匯報"
                            RidePageStage.NAVIGATION -> "導航"
                            RidePageStage.NAVIGATION_CANCEL -> "結束導航確認"
                            RidePageStage.CALL -> "通話通知"
                            RidePageStage.CALL_CANCEL -> "結束通話確認"
                            RidePageStage.MESSAGE_PROMPT -> "新訊息"
                            RidePageStage.MESSAGE_READ -> "訊息朗讀"
                            RidePageStage.MESSAGE -> "訊息通知"
                            RidePageStage.MESSAGE_CATEGORY -> "選擇回覆方式"
                            RidePageStage.MESSAGE_QUICK_LIST -> "快速回覆"
                            RidePageStage.MESSAGE_LISTENING -> "語音回覆"
                            RidePageStage.MESSAGE_CONFIRMATION -> "確認回覆內容"
                            RidePageStage.MESSAGE_TRY_AGAIN -> "語音未完成"
                            RidePageStage.MESSAGE_SENDING -> "正在交付回覆"
                            RidePageStage.MESSAGE_SENT -> "已交付來源 App"
                            RidePageStage.MESSAGE_NOT_SENT -> "回覆未送出"
                            else -> "儀表畫面"
                        }
                        val function = when (page.stage) {
                            RidePageStage.MUSIC, RidePageStage.NO_SESSION -> MainMenuSelection.Item.MUSIC
                            RidePageStage.VOLUME -> MainMenuSelection.Item.VOLUME
                            RidePageStage.WEATHER, RidePageStage.STATUS_MENU -> MainMenuSelection.Item.WEATHER
                            RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL -> MainMenuSelection.Item.NAVIGATION
                            RidePageStage.CALL, RidePageStage.CALL_CANCEL -> MainMenuSelection.Item.CALL
                            RidePageStage.MESSAGE, RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ,
                            RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_LISTENING,
                            RidePageStage.MESSAGE_CONFIRMATION, RidePageStage.MESSAGE_TRY_AGAIN,
                            RidePageStage.MESSAGE_SENDING, RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT -> MainMenuSelection.Item.MESSAGE
                            else -> null
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            function?.let { RideFunctionIcon(it) }
                            Text(title, Modifier.testTag("ride_current_function").semantics { heading() },
                                style = if (page.stage == RidePageStage.MENU) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        val description = when (page.stage) {
                            RidePageStage.DISABLED -> "等待儀表畫面狀態"
                            RidePageStage.SPLASH -> "正在準備啟動畫面…"
                            RidePageStage.STARTUP_FAILED -> "啟動畫面未完成，請中斷後重新連線。"
                            RidePageStage.UNSUPPORTED -> "此車輛未提供所需的畫面顯示能力。"
                            RidePageStage.NO_SESSION -> "播放器已結束，請重新選擇播放器。"
                            RidePageStage.MENU -> "主選單"
                            RidePageStage.MUSIC -> "音樂"
                            RidePageStage.VOLUME -> "音量"
                            RidePageStage.WEATHER -> "天氣匯報"
                            RidePageStage.STATUS_MENU -> "上下選擇項目，按確認才會朗讀；不會自動播報。"
                            RidePageStage.STATUS_REPORT -> when (page.statusReport?.stage) {
                                RideStatusReportStage.LOADING -> "正在讀取選取當時的手機狀態…"
                                RideStatusReportStage.SPEAKING -> "正在播報；上下調整音量，返回取消。"
                                RideStatusReportStage.FAILED -> "播報未完成。請確認音訊與勿擾設定，再自行決定是否重試。"
                                RideStatusReportStage.COMPLETE -> "播報已完成。"
                                else -> "等待狀態資訊。"
                            }
                            RidePageStage.NAVIGATION -> "導航"
                            RidePageStage.NAVIGATION_CANCEL -> "結束導航確認：確認後才會要求 Maps 結束導航。"
                            RidePageStage.CALL -> "通話通知"
                            RidePageStage.CALL_CANCEL -> "結束通話確認：確認後才向來源 App 提出結束要求。"
                            RidePageStage.MESSAGE_PROMPT -> when {
                                page.messageCanRead -> "新訊息提示：確認讀取通知預覽，返回略過，主選單清除待處理佇列。"
                                page.messageReadStatus == MessageReadStatus.FAILED -> "新訊息提示已中止或失敗，沒有自動讀取；可返回後手動瀏覽。"
                                else -> "新訊息提示：完成提示後依自動讀取設定朗讀；返回可略過。"
                            }
                            RidePageStage.MESSAGE_READ -> when (page.messageReadStatus) {
                                MessageReadStatus.READING -> "正在讀取新訊息預覽；上下調整音量，返回停止並處理下一則。"
                                MessageReadStatus.COMPLETE -> if (page.messageCanReply) "按確認選擇回覆方式，或返回處理下一則。"
                                    else "訊息預覽播報已完成；返回處理下一則。"
                                MessageReadStatus.FAILED -> "訊息朗讀已中止或失敗，不會自行重播正文；可返回後手動瀏覽。"
                                MessageReadStatus.NONE -> "準備讀取新訊息預覽。"
                            }
                            RidePageStage.MESSAGE -> when (page.messageReadStatus) {
                                MessageReadStatus.NONE -> "訊息通知：上下選擇，確認播報，返回離開。"
                                MessageReadStatus.READING -> "正在播報選定的通知預覽；返回可停止。"
                                MessageReadStatus.COMPLETE -> if (page.messageCanReply) "按確認選擇回覆方式，或上下切換訊息。"
                                    else "通知預覽播報已完成；此通知沒有可用的回覆動作。"
                                MessageReadStatus.FAILED -> "播報未完成，請檢查音訊、勿擾與通知存取，再自行決定是否重試。"
                            }
                            RidePageStage.MESSAGE_CATEGORY -> "上下選擇回覆方式，按確認繼續。"
                            RidePageStage.MESSAGE_QUICK_LIST -> if (page.replyChoices.isEmpty())
                                "目前沒有可用範本，無法傳送。請到設定檢查快速回覆範本。"
                                else "上下選擇範本；按確認會直接向來源 App 傳送選取回覆，返回可取消。"
                            RidePageStage.MESSAGE_LISTENING -> "提示結束後請說出回覆內容。辨識不會自動送出，返回可取消。"
                            RidePageStage.MESSAGE_CONFIRMATION -> "按確認送出以下內容；按返回重新錄音。"
                            RidePageStage.MESSAGE_TRY_AGAIN -> when (page.replyError) {
                                RideReplyAvailability.MICROPHONE_REQUIRED -> "需要麥克風授權才能語音輸入；朗讀與文字範本不依賴此授權。"
                                RideReplyAvailability.RECONNECT_REQUIRED -> "這次連線未啟用背景語音。請先允許麥克風，再從 App 前景中斷並重新連線。"
                                RideReplyAvailability.NO_NETWORK -> "目前沒有可用網路，請恢復連線後自行重試。"
                                else -> "未取得可用的語音內容。可按確認重試，或返回取消。"
                            }
                            RidePageStage.MESSAGE_SENDING -> "正在向原訊息 App 提出回覆要求…"
                            RidePageStage.MESSAGE_SENT -> "來源 App 的回覆動作已派送；這不代表對方已收到訊息。"
                            RidePageStage.MESSAGE_NOT_SENT -> "回覆要求未完成，沒有自動重送。請返回後確認來源訊息。"
                        }
                        if (page.stage == RidePageStage.MENU) RideRidingMenu(page, media,
                            onFunction.takeIf { state.mode == Gen1Input.Function.MODE_SAB })
                        if (state.mode == Gen1Input.Function.MODE_SAB && page.stage == RidePageStage.STATUS_MENU)
                            rideStatusChoices.forEachIndexed { index, label ->
                                Text(if (page.statusChoice == index) "› $label" else label,
                                    Modifier.testTag("ride_status_choice_$index"),
                                    style = if (page.statusChoice == index) MaterialTheme.typography.headlineMedium
                                        else MaterialTheme.typography.titleLarge)
                            }
                        if (state.mode == Gen1Input.Function.MODE_SAB && !page.statusCanReport &&
                            (page.stage == RidePageStage.STATUS_MENU || page.stage == RidePageStage.STATUS_REPORT &&
                                page.statusReport?.stage == RideStatusReportStage.FAILED)) {
                            Text("目前無法確認可安全播報。請確認電話狀態、通知存取與音訊設定；未授權或觀察中斷時不會開始匯報。",
                                Modifier.testTag("ride_status_unavailable"), style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = onPermissions, modifier = Modifier.testTag("ride_status_permissions")) {
                                Text("權限與功能")
                            }
                        }
                        if (state.mode == Gen1Input.Function.MODE_SAB && page.stage == RidePageStage.STATUS_REPORT)
                            page.statusReport?.detail?.let { Text(it, Modifier.testTag("ride_status_report_detail"),
                                style = MaterialTheme.typography.headlineSmall) }
                        if (state.mode == Gen1Input.Function.MODE_SAB && page.stage == RidePageStage.VOLUME) {
                            Text(page.volumePercentage?.let { "$it%" } ?: "音量未知",
                                Modifier.testTag("ride_volume_value"), fontSize = 64.sp)
                        }
                        if (state.mode == Gen1Input.Function.MODE_SAB && page.stage == RidePageStage.WEATHER) {
                            val weather = page.weather
                            weather?.current?.weather?.let { reading ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    OemRideWeatherIcon(reading.kind)
                                    Text(reading.temperature(weather.unit), fontSize = 64.sp,
                                        modifier = Modifier.testTag("ride_weather_temperature"))
                                }
                                Text(reading.place, style = MaterialTheme.typography.headlineSmall)
                            }
                            Text(when (weather?.stage) {
                                RideWeatherStage.LOADING -> "正在取得天氣…"
                                RideWeatherStage.SPEAKING -> "正在播報天氣"
                                RideWeatherStage.COMPLETE -> "天氣播報已完成"
                                RideWeatherStage.FAILED -> "天氣匯報未完成"
                                else -> "等待天氣資訊"
                            }, style = MaterialTheme.typography.titleLarge)
                        }
                        if (description != title) Text(description, style = MaterialTheme.typography.titleLarge)
                        if (state.mode == Gen1Input.Function.MODE_SAB && page.stage in setOf(RidePageStage.MESSAGE_PROMPT,
                                RidePageStage.MESSAGE_READ, RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_LISTENING,
                                RidePageStage.MESSAGE_CONFIRMATION, RidePageStage.MESSAGE_TRY_AGAIN,
                                RidePageStage.MESSAGE_SENDING, RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT))
                            page.messageLabel?.let { Text(it, Modifier.testTag("ride_message_label"),
                                style = MaterialTheme.typography.headlineSmall) }
                        if (state.mode == Gen1Input.Function.MODE_SAB) {
                            if (page.stage in setOf(RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST)) page.replyChoices.forEachIndexed { index, choice ->
                                val focused = index == page.replyChoice
                                val intoView = remember { BringIntoViewRequester() }
                                LaunchedEffect(focused, choice) { if (focused) intoView.bringIntoView() }
                                Text(if (focused) "› $choice" else choice,
                                    Modifier.bringIntoViewRequester(intoView).testTag("ride_reply_choice_$index")
                                        .semantics { this.selected = focused },
                                    style = if (focused) MaterialTheme.typography.headlineSmall
                                        else MaterialTheme.typography.titleLarge)
                            }
                            if (page.stage == RidePageStage.MESSAGE_QUICK_LIST && page.replyChoices.isEmpty())
                                onQuickReplies?.let { open -> TextButton(onClick = open,
                                    modifier = Modifier.testTag("ride_quick_reply_settings")) { Text("快速回覆設定") } }
                            if (page.stage == RidePageStage.MESSAGE_TRY_AGAIN)
                                onRecognitionLanguage?.let { open -> TextButton(onClick = open,
                                    modifier = Modifier.testTag("ride_recognition_language")) { Text("語音辨識語言") } }
                            if (page.stage == RidePageStage.MESSAGE_CONFIRMATION) page.replyText?.let {
                                Text(it, Modifier.testTag("ride_reply_transcript"), style = MaterialTheme.typography.headlineMedium)
                                if (page.replyError != null) Text("語音讀回未完成，請先確認畫面上的內容再決定是否送出。",
                                    Modifier.testTag("ride_reply_readback_failed"), style = MaterialTheme.typography.titleLarge)
                            }
                            if (page.stage == RidePageStage.MESSAGE_TRY_AGAIN && page.replyError == RideReplyAvailability.MICROPHONE_REQUIRED)
                                Button(onClick = { voicePermission = true }, modifier = Modifier.testTag("ride_reply_microphone_permission")) {
                                    Text("麥克風權限說明")
                                }
                        }
                        if (!page.messageRecoveryAvailable)
                            Text("待處理訊息無法可靠讀取或保存，已停止自動提示；仍可手動瀏覽。請檢查儲存空間並重新連線。",
                                Modifier.testTag("ride_message_recovery_unavailable"), style = MaterialTheme.typography.titleMedium)
                        if (page.pending) Text("等待車端確認畫面。")
                        else if (page.receiptStatus != null && page.receiptStatus != 1)
                            Text("車端未接受這次畫面指令。", style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (showCalls && (detailsExpanded || page.stage in setOf(RidePageStage.CALL,
                        RidePageStage.CALL_CANCEL, RidePageStage.DISABLED))) {
                    Card(Modifier.fillMaxWidth().testTag("ride_calls_state"), colors = readableCardColors) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.mode == Gen1Input.Function.MODE_HU) {
                                val calls = callInbox.calls.takeIf { callInbox.access == CallAccess.READY }.orEmpty()
                                val incoming = calls.singleOrNull()?.takeIf { it.kind == CallKind.INCOMING }
                                Text("HU 來電提示", Modifier.testTag("ride_hu_call_hint"), style = MaterialTheme.typography.titleMedium)
                                when {
                                    callInbox.access == CallAccess.ACCESS_REQUIRED -> Text("需要通知存取權，才能觀察支援的來電。")
                                    callInbox.access != CallAccess.READY -> Text("目前無法讀取支援的來電通知，請查看手機。")
                                    calls.size > 1 -> Text("有多筆通話通知，儀表不會任選對象；請到手機通話頁確認。", Modifier.testTag("ride_calls_multiple"))
                                    incoming == null -> Text("HU 僅提供唯一有效來電的提示，不提供進行中通話的掛斷或通話頁；沒有提示不代表通話已結束。")
                                    else -> {
                                        Text(incoming.appName)
                                        Text(incoming.caller, Modifier.testTag("ride_call_caller"), style = MaterialTheme.typography.headlineMedium)
                                        Text("來源 App 回報來電；這不代表儀表已顯示提示。")
                                        Text("儀表僅顯示被動來電資訊；HU 模式不將手把按鍵交給 App 處理。")
                                    }
                                }
                            } else if (state.mode != Gen1Input.Function.MODE_SAB) {
                                Text("尚未確認儀表模式，目前不顯示通話對象或實體操作提示。")
                            } else {
                                val calls = callInbox.calls.takeIf { callInbox.access == CallAccess.READY }.orEmpty()
                                val call = calls.singleOrNull()
                                when {
                                    callInbox.access == CallAccess.ACCESS_REQUIRED -> Text("需要通知存取權，才能讀取標準通話通知。")
                                    callInbox.access != CallAccess.READY -> Text("目前無法讀取支援的通話通知，請查看手機。")
                                    calls.size > 1 -> Text("有多筆通話通知，儀表不會任選對象；請到手機通話頁確認。", Modifier.testTag("ride_calls_multiple"))
                                    call == null -> Text("目前未觀察到支援的通話通知；這不代表通話已結束。")
                                    else -> {
                                        Text(call.appName)
                                        Text(call.caller, Modifier.testTag("ride_call_caller"), style = MaterialTheme.typography.headlineMedium)
                                        Text(if (call.kind == CallKind.INCOMING) "來源 App 回報來電" else "來源 App 回報通話進行中")
                                        if (call.isVideo) Text("來源 App 回報視訊通話")
                                        if (page.stage == RidePageStage.CALL && call.kind == CallKind.INCOMING) {
                                            if (CallAction.ANSWER in call.actions) Text("實體確認鍵：請求來源 App 接聽。")
                                            if (CallAction.DECLINE in call.actions) Text("實體返回鍵：請求來源 App 拒接。")
                                        } else if (call.kind == CallKind.ONGOING && CallAction.HANG_UP in call.actions &&
                                            page.stage in setOf(RidePageStage.CALL, RidePageStage.CALL_CANCEL)) {
                                            Text(if (page.stage == RidePageStage.CALL_CANCEL)
                                                "實體確認鍵：請求結束通話；實體返回鍵：取消這次確認。"
                                                else "實體返回鍵先開啟結束確認，再按確認才請求結束通話；選單鍵離開頁面，不會結束通話。")
                                        }
                                    }
                                }
                                page.callDispatchResult?.let { Text(callResultText(it), Modifier.testTag("ride_call_result")) }
                            }
                            OutlinedButton(onClick = onCalls, modifier = Modifier.fillMaxWidth().testTag("ride_calls")) {
                                Text("手機通話通知與控制")
                            }
                        }
                    }
                }
                if (showNavigation && (detailsExpanded || page.stage in setOf(RidePageStage.NAVIGATION,
                        RidePageStage.NAVIGATION_CANCEL, RidePageStage.DISABLED))) {
                    Card(Modifier.fillMaxWidth().testTag("ride_navigation_state"), colors = readableCardColors) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.mode != Gen1Input.Function.MODE_SAB) {
                                Text("切換至實際 RoadSync 儀表模式後，才顯示目前的 Maps 導航觀察。")
                            } else {
                                Text(navigationStatusText(observation.status), Modifier.testTag("ride_navigation_status"))
                                if (observation.status == NavigationStatus.OBSERVED) {
                                    Text("來源為手機 Maps 通知，不代表已在車端顯示。", style = MaterialTheme.typography.bodySmall)
                                    observation.instruction?.let { instruction ->
                                        Text(instruction.instruction, Modifier.testTag("ride_navigation_instruction"),
                                            style = MaterialTheme.typography.headlineMedium)
                                        instruction.nextStep?.let { Text("下一轉彎距離：$it", style = MaterialTheme.typography.titleLarge) }
                                        instruction.remaining?.let { Text("Maps 行程摘要：$it", style = MaterialTheme.typography.titleMedium) }
                                    }
                                }
                                page.navigationCancelResult?.let { result ->
                                    Text(when (result) {
                                        NavigationCancelResult.DISPATCHED -> "已送出結束要求，請確認 Maps 的實際狀態；尚未確認路線已停止。"
                                        NavigationCancelResult.STALE -> "通知已變更或此要求已送出，請重新查看導航狀態。"
                                        NavigationCancelResult.UNSUPPORTED -> "這筆通知沒有可用的結束動作，請在 Maps 操作。"
                                        NavigationCancelResult.ACCESS_REQUIRED -> "需要通知存取權，尚未送出結束要求。"
                                        NavigationCancelResult.UNAVAILABLE -> "目前無法送出結束要求，請在 Maps 確認。"
                                        NavigationCancelResult.LOCKED -> "請先解鎖手機，再確認結束導航。"
                                    }, Modifier.testTag("ride_navigation_cancel_result"))
                                }
                            }
                            OutlinedButton(onClick = onNavigation, modifier = Modifier.fillMaxWidth().testTag("ride_navigation")) {
                                Text("手機導航與 Maps 狀態")
                            }
                        }
                    }
                }
                if (showMusic && (detailsExpanded || page.stage in setOf(RidePageStage.MUSIC,
                        RidePageStage.NO_SESSION, RidePageStage.DISABLED))) {
                    Card(Modifier.fillMaxWidth().testTag("ride_music_state"), colors = readableCardColors) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selected?.label ?: "尚未選擇播放器")
                            if (selected != null) Text(when (media?.playback) {
                                MediaPlayback.PLAYING -> "播放中"
                                MediaPlayback.PAUSED -> "已暫停"
                                MediaPlayback.BUFFERING, MediaPlayback.CONNECTING -> "載入中"
                                MediaPlayback.STOPPED, MediaPlayback.NONE -> "已停止"
                                else -> "播放狀態未知"
                            }, Modifier.testTag("ride_music_playback"), style = MaterialTheme.typography.titleLarge)
                            if (state.favoriteStart != FavoriteStartStatus.NONE) Text(favoriteStartMessage(state.favoriteStart))
                            media?.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                            media?.artist?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                            media?.album?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                            if (selected == null && onRetryFavorite != null) {
                                OutlinedButton(onClick = onRetryFavorite,
                                    enabled = state.favoriteStart != FavoriteStartStatus.STARTING,
                                    modifier = Modifier.fillMaxWidth().testTag("ride_retry_favorite")) {
                                    Text("啟動常用播放器並繼續播放")
                                }
                                Text("嘗試開啟已設定的常用播放器並繼續播放音訊，不需中斷車輛連線。若需要授權，可到下方「權限與功能」設定。",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = onMedia, modifier = Modifier.fillMaxWidth().testTag("ride_media")) { Text("播放器與儀表操作") }
                        }
                    }
                }
                RidePageActions(page, media, observation, callInbox,
                    onAction.takeIf { state.mode == Gen1Input.Function.MODE_SAB })
                if (detailsExpanded && !state.backgroundWeather) Text("這次連線未啟用背景天氣定位。請先在「權限與功能」允許位置、開啟手機定位，再從 App 前景手動重新連線。",
                    style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.testTag("ride_details_toggle")) {
                    Text(if (detailsExpanded) "收合連線與功能說明" else "連線與功能說明")
                }
                if (detailsExpanded) {
                    Text(when (state.mode) {
                        Gen1Input.Function.MODE_SAB -> "車輛模式：RoadSync"
                        Gen1Input.Function.MODE_HU -> "車輛模式：儀表"
                        else -> "尚未收到車輛模式"
                    }, Modifier.testTag("ride_status"))
                    state.rssi?.let { Text("藍牙訊號：$it dBm") }
                    if (!page.pending && page.receiptStatus == 1)
                        Text("車端已接受畫面指令，實際顯示請以儀表為準。")
                    if (!showMusic && state.favoriteStart != FavoriteStartStatus.NONE)
                        Text(favoriteStartMessage(state.favoriteStart))
                    if (!showCalls) {
                        if (callInbox.access == CallAccess.ACCESS_REQUIRED)
                            Text("需要通知存取權，才能讀取標準通話通知。")
                        else if (callInbox.access != CallAccess.READY)
                            Text("目前無法讀取支援的通話通知，請查看手機。")
                    }
                    if (!showNavigation && state.mode == Gen1Input.Function.MODE_SAB)
                        Text(navigationStatusText(observation.status))
                    Text("RoadSync 模式可使用其他手機功能，連線由背景服務維持；儀表模式會鎖定 App 畫面，請在車上切回 RoadSync 模式。離開車輛時請使用「中斷連線」。")
                    Text("來源為 Android 14 以上的標準 CallStyle 通知，不代表完整電話、聯絡人或所有通訊 App 支援；車端顯示與實體操作仍待實車驗證。")
                    Text("目前儀表提供天氣、Maps 通知導航、標準通話通知、訊息預覽、音樂與音量，仍依模式、權限及資料來源判定可用性。新訊息依儀表自訂設定先詢問、自動讀取或忽略。導航與通話的車端顯示、實體操作仍待實車驗證；把手回覆與其他騎乘功能仍在開發中。")
                }
            }
            if (state.stage != RideStage.CONNECTED || detailsExpanded) {
            OutlinedButton(onClick = onGarage, modifier = Modifier.fillMaxWidth().testTag("ride_garage")) { Text("車庫與配對") }
            if (state.stage == RideStage.CONNECTED && !showMusic)
                OutlinedButton(onClick = onMedia, modifier = Modifier.fillMaxWidth().testTag("ride_media")) { Text("播放器與儀表操作") }
            if (state.stage != RideStage.CONNECTED || !showNavigation)
                OutlinedButton(onClick = onNavigation, modifier = Modifier.fillMaxWidth().testTag("ride_navigation")) { Text("手機導航與 Maps 狀態") }
            if (state.stage != RideStage.CONNECTED || !showCalls)
                OutlinedButton(onClick = onCalls, modifier = Modifier.fillMaxWidth().testTag("ride_calls")) { Text("手機通話通知與控制") }
            OutlinedButton(onClick = onPermissions, modifier = Modifier.fillMaxWidth().testTag("ride_permissions")) { Text("權限與功能") }
            OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth().testTag("ride_diagnostics")) { Text("診斷與匯出") }
            }
        }
    }
    }
}

internal fun callResultText(result: CallDispatchResult): String = when (result) {
    CallDispatchResult.DISPATCHED -> "請求已交給來源 App；是否接通、拒接或結束，請以來源 App 與後續通知為準。"
    CallDispatchResult.STALE -> "通話通知已變更、移除或已操作，沒有再次送出；請重新確認手機上的通話。"
    CallDispatchResult.UNSUPPORTED -> "目前通知沒有提供這個動作，請到來源 App 處理。"
    CallDispatchResult.ACCESS_REQUIRED -> "通知存取已變更，沒有送出要求。"
    CallDispatchResult.UNAVAILABLE -> "無法確認要求已交付，請先查看來源 App 的實際通話狀態。"
    CallDispatchResult.LOCKED -> "請先解鎖手機，再重新確認通話操作。"
}
