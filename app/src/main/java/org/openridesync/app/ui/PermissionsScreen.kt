package org.openridesync.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.openridesync.app.media.MediaAccessService
import org.openridesync.app.pairing.PairingBluetooth
import org.openridesync.app.runtime.RideConnection

internal enum class AppAccess(val title: String, val explanation: String) {
    PAIRING("搜尋與配對車輛", "允許偵測及連接附近的車輛。未授權時無法搜尋或配對；Android 11 使用精確位置權限並需要開啟定位服務，較新版本使用附近裝置權限。"),
    CONNECTION("連接已配對車輛", "允許與車庫內已配對車輛建立藍牙連線。未授權時無法連接儀表，帳號及診斷仍可使用。"),
    NOTIFICATIONS("顯示 App 通知", "顯示背景連線狀態，並提供返回 App 與中斷連線的通知按鈕。未開啟時通知欄可能看不到連線通知，但仍可在 App 內連線及中斷。這不會授予讀取其他 App 通知的能力。"),
    MEDIA("通知存取、媒體、訊息、通話與導航", "允許系統提供其他 App 的通知與媒體工作階段，用於列出播放器、取得曲目、控制播放及判斷通知來電。開啟訊息頁時會讀取訊息內容與可用回覆動作；實際 SAB 連線期間也會維護暫時的通知佇列。新訊息預設先提示 App 與訊息標籤、按確認才讀取；儀表自訂可改為先提示後自動朗讀，或忽略自動提示。手動瀏覽仍可使用，鎖屏僅讀取 Android 提供的內容，不恢復被遮蔽的文字。通話優先並中止語音；失去通知存取、儀表模式或連線時清除該來源內容。待處理訊息只保存帳號隔離的雜湊識別以供恢復，不保存通知正文、寄件者或回覆動作。Android 14 以上可讀取標準 CallStyle 通話通知的對象與來源 App 提供的接聽、拒接或結束動作。手機頁操作須再次確認；車輛連線的實際儀表模式會在記憶體觀察此通知，來電時實體確認鍵請求接聽、返回鍵請求拒接；進行中通話先按返回進入結束確認，再按確認才請求結束。送出請求不代表已接通或結束，也不代表所有電話或通訊 App 支援；不讀聯絡人或通話紀錄。SAB 模式提供通話頁；HU 模式只以共用 Popup 顯示唯一有效的標準來電資訊，不提供接聽、拒接或掛斷按鍵。HU 與未知模式不處理 App 手把功能動作；模式通知與明確的切回 SAB 要求獨立保留，收到真實 SAB 回報後才恢復操作。失去顯示能力、通知存取、可確認的儀表模式或車輛連線時清除該連線的通話對象與動作。導航頁及實際儀表模式會讀取 Google Maps 的導航通知文字與轉向圖示；只有你明確操作才會播報，或確認開啟目的地、送出目前通知的結束要求。手機導航頁離頁或背景時會清除該頁通知與目的地；車輛連線期間的儀表導航可在背景與鎖屏讀取指示，按確認重播，按返回再確認才要求 Maps 結束導航。離開儀表模式、中斷連線或通話介入時清除該連線的指示並停止播報。未授權仍能開啟 Maps，但不能在此讀取或操作導航通知。訊息回覆也須確認；內容、草稿、通話對象與導航地址不寫入診斷。未授權時這些功能無法使用；這與顯示 App 通知是不同授權。"),
    PHONE("電話狀態", "辨識一般電話通話，讓音樂控制及音量控制能配合通話狀態。未授權時無法可靠判斷一般來電與通話，通話期間的音樂抑制及通話音量選擇可能不正確。天氣與手機狀態匯報需要確認電話閒置；未授權或觀察中斷時仍可查看選單，但不能開始播報。"),
    VOICE("語音回覆與麥克風", "在 RoadSync 模式中，明確選擇語音回覆後才使用麥克風辨識內容；辨識後會讀回草稿，仍須再次確認才送出。未授權時不能語音輸入，朗讀與文字範本回覆仍可使用。若要在背景或鎖屏時語音輸入，請先授權，再從 App 前景手動連接車輛；連線後才授權時，需要中斷再連線。辨識服務依手機設定，可能連網處理聲音，並不保證離線可用。本 App 不保存音訊，也不把訊息與草稿寫入診斷；離開、通話介入、來源改變或離開 RoadSync 模式會取消語音並清除草稿。"),
    LOCATION("所在地天氣與自動重連", "使用概略位置向 Honda 查詢所在地天氣，並在車輛斷線後的 60 秒恢復窗內進行有界移動取樣，以判斷是否繼續自動重連。這些功能需要位置授權、開啟手機定位，並在 App 前景建立含位置類型的連線前景服務，才能在鎖屏後繼續使用；連線後才更改設定時，請手動中斷再連線。未授權、定位關閉或移動資料不足時，自動重連可能停止，仍可從車庫手動連線。此項只請求概略位置，不要求精確位置或背景位置權限；恢復窗不保存座標。"),
    TRIP_RECORDING("旅程記錄與同步", "在設定的「旅程記錄與同步」另行開啟後，實際驗證成功的車輛連線會記錄手機位置並透過 Honda 同步。概略位置也可使用，但路線精度較低；未授權或定位關閉時不能記錄。請先授權，再從 App 前景手動連接車輛，才能在背景或鎖屏時繼續記錄；連線後才更改權限時需中斷再連線。這裡不要求精確位置或背景位置權限，授權本身也不會打開記錄開關。記錄存於本機私有帳號佇列；停止記錄不會刪除待傳資料。位置、車輛名稱及旅程識別會傳給 Honda，不寫入一般診斷。"),
    WIFI_NAME("Wi‑Fi 名稱（SSID）", "Android 將目前連線的 Wi‑Fi 名稱視為位置資訊；顯示 SSID 需要精確位置授權，並開啟手機定位。只允許概略位置時，仍可能無法顯示名稱。拒絕後仍可查看 Wi‑Fi 強度及使用不依賴此授權的功能。這項功能只讀目前連線，不掃描附近網路、不取得 BSSID，也不把名稱寫入日誌或診斷；不要求背景定位。"),
    DO_NOT_DISTURB("勿擾模式控制", "允許 RoadSync（SAB）模式的儀表音量頁操作手機靜音及解除靜音。HU 與未知模式不處理手把功能按鍵。未授權時涉及勿擾模式的操作無法完成；一般音樂播放不需要此授權。"),
    ;

    fun permissions(): Array<String> = when (this) {
        PAIRING -> PairingBluetooth.permissions()
        CONNECTION -> RideConnection.permissions()
        NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        PHONE -> arrayOf(Manifest.permission.READ_PHONE_STATE)
        VOICE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        LOCATION -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        TRIP_RECORDING -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        WIFI_NAME -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> emptyArray()
    }

    fun requestPermissions(context: Context): Array<String> = if (this == WIFI_NAME) permissions()
        else permissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun granted(context: Context): Boolean = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        when (this) {
            NOTIFICATIONS -> manager?.areNotificationsEnabled() == true
            MEDIA -> manager?.isNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java)) == true
            DO_NOT_DISTURB -> manager?.isNotificationPolicyAccessGranted == true
            else -> permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }
    } catch (_: RuntimeException) { false }

    fun settings(context: Context): Intent = when (this) {
        MEDIA -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        DO_NOT_DISTURB -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
}

/** Shared UI for explicit requests only. Resuming or recomposing never launches a request. */
@Composable
private fun AccessControls(accesses: List<AppAccess>, content: @Composable (Set<AppAccess>, @Composable () -> Unit) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val listener by MediaAccessService.callSummary.collectAsState()
    fun read() = accesses.filter { it.granted(context) }.toSet()
    var granted by remember(accesses) { mutableStateOf(read()) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = read()
        message = if (it.values.any { value -> !value }) "尚未授權的功能會維持停用。你可以稍後再試；若系統不再詢問，可自行開啟系統設定。" else null
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { granted = read() }
    DisposableEffect(owner, accesses) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = read() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun open(intent: Intent) {
        try { message = null; settings.launch(intent) }
        catch (_: RuntimeException) { message = "無法開啟系統畫面。請至手機設定 → 應用程式 → OpenRideSync，或搜尋通知存取／勿擾模式存取。" }
    }
    content(granted) {
        Text("每項權限可自行決定。按下請求或設定按鈕後，仍由你在系統畫面確認；可以拒絕並繼續使用不依賴該權限的功能。")
        accesses.forEach { access ->
            AccessCard(access, access in granted, onRequest = {
                val missing = access.permissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) open(access.settings(context))
                else try { message = null; request.launch(access.requestPermissions(context)) }
                catch (_: RuntimeException) { message = "無法顯示權限請求，請使用系統設定按鈕，或稍後重試。" }
            }, onSettings = { open(access.settings(context)) },
                listenerConnected = listener.listenerConnected,
                onPhoneSettings = { open(Intent(Settings.ACTION_SETTINGS)) },
                onLocationSettings = { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        }
        message?.let { Text(it, Modifier.testTag("permission_feedback")) }
    }
}

@Composable
internal fun AccessCard(access: AppAccess, granted: Boolean, onRequest: () -> Unit, onSettings: () -> Unit,
    listenerConnected: Boolean = true, onPhoneSettings: () -> Unit = {}, onLocationSettings: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(access.title, style = MaterialTheme.typography.titleMedium)
            Text(access.explanation)
            Text(if (granted) "已開啟" else "尚未開啟", Modifier.testTag("permission_${access.name}_status"))
            if (access == AppAccess.MEDIA && granted)
                Text(if (listenerConnected) "通知服務已連線" else "通知服務尚未連線",
                    Modifier.testTag("permission_MEDIA_connection"))
            if (access == AppAccess.MEDIA && granted && !listenerConnected) {
                Text("通知存取已授權，但通知服務尚未連線。訊息通知與通知來電目前無法可靠讀取；已可使用的媒體播放器仍可操作。")
                Text("在 Xiaomi 手機可到設定 → 應用程式 → 權限 → 背景自啟動，允許 OpenRideSync；路徑依版本可能不同，也可在設定搜尋「背景自啟動」。返回 App 後重新整理；若仍未連線，可自行檢查下方通知存取設定。")
                TextButton(onClick = onPhoneSettings, modifier = Modifier.testTag("permission_MEDIA_phone_settings")) { Text("開啟手機設定") }
            }
            if (access in setOf(AppAccess.WIFI_NAME, AppAccess.LOCATION)) TextButton(onClick = onLocationSettings,
                modifier = Modifier.testTag("permission_${access.name}_location_settings")) { Text("開啟手機定位設定") }
            if (!granted) {
                Button(onClick = onRequest, modifier = Modifier.testTag("permission_${access.name}_request")) {
                    Text(if (access.permissions().isEmpty()) "開啟授權設定" else "提出權限請求")
                }
                Text("若曾拒絕或系統未顯示詢問，可以自行到系統設定調整。", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSettings, modifier = Modifier.testTag("permission_${access.name}_settings")) {
                Text(if (access == AppAccess.MEDIA) "開啟通知存取設定" else "開啟系統設定")
            }
        }
    }
}

@Composable
internal fun PermissionDialog(accesses: List<AppAccess>, required: Set<AppAccess> = emptySet(),
    confirmLabel: String = "完成", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AccessControls(accesses) { granted, cards ->
        AlertDialog(onDismissRequest = onDismiss, title = { Text("權限與功能說明") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { cards() } },
            confirmButton = { TextButton(onClick = onConfirm, enabled = granted.containsAll(required),
                modifier = Modifier.testTag("permission_continue")) { Text(confirmLabel) } },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("permission_later")) { Text("稍後再說") } })
    }
}

@Composable
internal fun PermissionsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    AccessControls(AppAccess.entries) { _, cards ->
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) { Text("返回") }
                Text("權限與功能", style = MaterialTheme.typography.headlineMedium)
                cards()
            }
        }
    }
}
