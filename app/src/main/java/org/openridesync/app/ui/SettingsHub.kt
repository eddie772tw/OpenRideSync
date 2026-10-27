package org.openridesync.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.openridesync.app.BuildConfig
import org.openridesync.app.ui.theme.AppearanceMode

/** Pure presentation: rendering and document navigation never query a service or start a ride. */
@Composable
internal fun SettingsHubContent(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    appearance: AppearanceMode,
    onAppearanceChange: (AppearanceMode) -> Unit,
    accountMessage: String = "登入與帳號讀取狀態請查看帳號頁。",
    syncReadMessage: String = "帳號、車庫與騎乘紀錄各自顯示讀取結果；此處沒有全域同步開關。",
    developer: Boolean = BuildConfig.DEBUG,
) {
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    val back: () -> Unit = { if (document != null) document = null else onBack() }
    BackHandler(onBack = back)
    key(document) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp)
                .testTag(if (document == null) "settings_screen" else "settings_document"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = back, modifier = Modifier.testTag("shell_back")) { Text("返回") }
                if (document != null) {
                    SettingsDocument(document!!)
                } else {
                    Text("設定與協助", style = MaterialTheme.typography.headlineMedium)
                    SettingsSection("帳號、登入與同步", "settings_section_account") {
                        Text(accountMessage, modifier = Modifier.testTag("settings_account_state"))
                        SettingsAction("帳號與登入", "settings_account") { onOpen("account") }
                        Text(syncReadMessage, modifier = Modifier.testTag("settings_sync_state"))
                        Text("進入設定不會發起帳號或紀錄讀取。請到對應頁面明確操作；讀取不代表背景持續同步，也不代表已上傳騎乘資料。")
                        SettingsAction("車庫與車型資料", "settings_vehicles") { onOpen("vehicles") }
                        SettingsAction("騎乘紀錄讀取", "settings_trips") { onOpen("trips") }
                        SettingsAction("旅程記錄與同步", "settings_trip-recording") { onOpen("trip-recording") }
                    }
                    SettingsSection("日間／夜間外觀", "settings_section_appearance") {
                        Text("只變更手機 App 外觀，會保留你的選擇；不改變車輛儀表。")
                        Column(Modifier.selectableGroup()) {
                            AppearanceMode.entries.forEach { mode ->
                                Row(Modifier.fillMaxWidth().selectable(appearance == mode,
                                    onClick = { onAppearanceChange(mode) }, role = Role.RadioButton)
                                    .testTag("settings_appearance_${mode.name.lowercase()}").padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    RadioButton(selected = appearance == mode, onClick = null)
                                    Text(when (mode) { AppearanceMode.SYSTEM -> "跟隨系統"; AppearanceMode.LIGHT -> "日間"; AppearanceMode.DARK -> "夜間" },
                                        modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                    }
                    SettingsSection("功能設定與協助", "settings_section_help") {
                        SettingsAction("訊息通知與快速回覆", "settings_messages") { onOpen("messages") }
                        SettingsAction("快速回覆範本", "settings_quick-replies") { onOpen("quick-replies") }
                        SettingsAction("語音辨識語言", "settings_recognition-language") { onOpen("recognition-language") }
                        SettingsAction("通話通知與控制", "settings_calls") { onOpen("calls") }
                        SettingsAction("導航與目的地", "settings_navigation") { onOpen("navigation") }
                        SettingsAction("權限與功能", "settings_permissions") { onOpen("permissions") }
                        SettingsAction("重新查看設定導引", "settings_setup") { onOpen("setup") }
                        SettingsAction("診斷與匯出", "settings_diagnostics") { onOpen("diagnostics") }
                        Text("查看診斷不需登入；匯出由你選擇保存位置。")
                    }
                    SettingsSection("資訊、授權與文件", "settings_section_info") {
                        listOf("about" to "關於與建置資訊", "privacy" to "資料處理與限制", "licenses" to "開源元件與授權參考",
                            "terms" to "服務條款狀態", "docs" to "使用說明").forEach { (id, title) ->
                            SettingsAction(title, "settings_$id") { document = id }
                        }
                    }
                    if (BuildConfig.DEBUG && developer) {
                        SettingsSection("開發選項", "settings_section_developer") {
                            Text("僅開發版顯示。這些工具不是一般騎乘入口，也不代表已通過實車驗收。")
                            SettingsAction("儀表模式預覽與獨立測試", "settings_preview") { onOpen("ride-preview") }
                            SettingsAction("Google Maps 導航資料觀察", "settings_navigation_observation") { onOpen("navigation-observation") }
                            SettingsAction("實車按鍵觀察工具", "settings_handlebar") { onOpen("handlebar-test") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SettingsAction(label: String, tag: String, action: () -> Unit) {
    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
}

/** Local readable documents; no remote content, implied acceptance or automatic browser launch. */
@Composable
private fun SettingsDocument(id: String) {
    val (title, paragraphs) = when (id) {
        "about" -> "關於與建置資訊" to listOf(
            "OpenRideSync · 獨立研究重構版。尚非實車測試候選；不是 Honda 官方 App。",
            "版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "套件：${BuildConfig.APPLICATION_ID}", "建置：${BuildConfig.BUILD_TYPE}", "來源版本：${BuildConfig.SOURCE_COMMIT}",
            "既有修改版的實車結果不代表本重構版已通過驗收。")
        "privacy" -> "資料處理與限制" to listOf(
            "這是目前功能的資料處理說明，不是完整法律隱私政策。",
            "正常登入由 Google／Firebase 流程處理；登入後的 Honda 帳號、車庫、騎乘紀錄與天氣讀取由對應功能處理。服務讀取需要網路，失敗以各頁實際狀態為準。",
            "手機外觀、快速回覆範本與語音辨識語言保存在此 App 的本機設定。進入設定不會登入、查詢位置、掃描或連接車輛；可用辨識語言由你明確向系統辨識服務查詢，不會啟動錄音。",
            "旅程記錄與同步預設關閉，另以目前帳號保存你的選擇。啟用後，實際驗證成功的車輛連線可收集手機位置、車輛資料與時間，保存於本機帳號隔離的待傳佇列並透過 Honda 同步。關閉會停止收集與自動上傳，但保留待傳資料；可明確手動上傳。位置、車輛名稱與旅程識別不加入一般診斷匯出。",
            "訊息頁開啟或實際儀表連線期間，依通知權限讀取 Android 可見的訊息與回覆動作。收到的正文與辨識草稿限於記憶體；待處理訊息只保存帳號分隔的雜湊識別。自行編輯並保存的快速回覆範本另存於 App 私人設定。只有明確確認傳送才呼叫原 App 的回覆動作；正文、範本、收件對象與草稿不寫入診斷。",
            "權限頁可查看授權與限制。診斷與匯出頁提供本機紀錄與明確匯出入口；分享前請自行檢查匯出內容。",
            "登出不等於刪除服務端帳號或所有本機資料；本頁沒有提供帳號刪除或完整資料清除功能。",
            "此說明不承諾第三方服務的保存期限或刪除方式，應另以服務提供者的實際政策為準。")
        "licenses" -> "開源元件與授權參考" to listOf(
            "下列來源依此專案建置宣告整理，是元件索引，尚非完整第三方授權聲明或隨包授權全文。",
            "AndroidX Activity、Lifecycle、Jetpack Compose Material 3：app/build.gradle.kts 與 gradle/libs.versions.toml 宣告的 AndroidX 元件。",
            "Kotlin 與 kotlinx.coroutines：gradle/libs.versions.toml 的 Kotlin／coroutines 宣告。",
            "OkHttp：com.squareup.okhttp3:okhttp，由版本目錄宣告。",
            "AndroidSVG 1.4：com.caverock:androidsvg-aar，用於讀取已安裝 Maps 的 SVG 圖示；Apache License 2.0。",
            "Firebase Authentication：app/build.gradle.kts 的 Firebase BOM 與 firebase-auth 宣告。",
            "此倉庫目前沒有可供此頁重製的完整 LICENSE／NOTICE 清單。正式散布前仍須核對實際依賴及傳遞依賴、收錄各自授權全文與必要聲明；本頁不宣稱已完成。",
            "原廠 APK、私有字型、圖片與登入設定不屬於此開源元件索引，也不由本頁授予使用權。")
        "terms" -> "服務條款狀態" to listOf(
            "OpenRideSync 自身服務條款尚未定稿。此頁只說明目前狀態，沒有同意按鈕，也不代表你接受任何新協議。",
            "Google、Firebase、Honda 或其他外部服務的使用條件仍應依其實際流程及條款確認；本 App 不代替這些服務提供授權或保證。",
            "研究功能仍不完整。請勿把目前介面、歷史原型驗收或本機測試當作可安全上路的保證。")
        else -> "使用說明" to listOf(
            "帳號與登入：回到設定中的帳號入口，查看真實登入、帳號讀取與登出狀態。",
            "資料讀取：車庫與騎乘紀錄是分開的功能；到各自頁面操作並確認結果，沒有全域同步進度。",
            "外觀：選擇跟隨系統、日間或夜間，會保存到此 App 的本機設定。",
            "權限與設定：從設定導引了解功能，再到權限頁檢查系統授權。查看說明本身不會授權、配對或連車。",
            "診斷：從診斷與匯出頁檢查結果，只有明確匯出才會交由你選擇保存位置。",
            "文件依據：apps/open-roadsync/android/README.md、apps/open-roadsync/auth/README.md，以及 docs/decisions/ADR-0002-APP-RECONSTRUCTION.md。此處提供離線摘要，不會自動開啟外部網站。")
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("settings_document_$id"))
    paragraphs.forEach { Text(it) }
}
