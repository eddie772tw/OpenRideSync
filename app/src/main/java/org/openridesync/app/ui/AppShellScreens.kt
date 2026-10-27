package org.openridesync.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.openridesync.app.BuildConfig

/** Presentation facts only. This is not a permission, pairing, or vehicle-readiness gate. */
internal data class ShellStatus(val signedIn: Boolean, val accountMessage: String, val rideMessage: String)

@Composable
private fun ShellPage(title: String, tag: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            onBack?.let { TextButton(onClick = it, modifier = Modifier.testTag("shell_back")) { Text("返回") } }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}

@Composable
private fun Entry(title: String, detail: String, action: String, tag: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(action) }
        }
    }
}

@Composable
internal fun WelcomeScreen(onBegin: () -> Unit, onSkip: () -> Unit,
    hondaConfigured: Boolean = BuildConfig.HONDA_CONFIGURED) {
    ShellPage("歡迎使用 OpenRideSync", "welcome_screen") {
        Text("Alpha · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("這是第一個使用者版本的 Alpha 開發版，並非 Honda 官方 App。請先確認此版本的相容性與驗收範圍。")
        Text(clientConfigurationMessage(hondaConfigured))
        Text("先了解帳號、權限與車輛設定，再選擇要開啟的功能。你可以略過，之後從首頁的「開始設定」回來。")
        Text("查看介紹不會登入、要求權限、掃描或連接車輛。每項操作都需要你另行選擇。")
        Button(onClick = onBegin, modifier = Modifier.fillMaxWidth().testTag("welcome_begin")) { Text("開始設定") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().testTag("welcome_skip")) { Text("稍後設定，先看首頁") }
    }
}

internal fun clientConfigurationMessage(configured: Boolean): String = if (configured)
    "此版本已包含原服務登入設定。這不代表已登入；請到帳號頁確認登入與資料讀取結果。"
else
    "此版本未包含原服務登入設定，無法登入或使用車輛服務。可先查看設定導引、權限說明與本機診斷。"

@Composable
internal fun SetupScreen(status: ShellStatus, onOpen: (String) -> Unit, onBack: () -> Unit,
    initialStep: Int = 0, onStepChanged: (Int) -> Unit = {}) {
    var step by rememberSaveable { mutableIntStateOf(initialStep.takeIf { it in 0..2 } ?: 0) }
    fun move(next: Int) { step = next; onStepChanged(next) }
    val back = { if (step > 0) move(step - 1) else onBack() }
    BackHandler(onBack = back)
    key(step) {
    ShellPage("開始設定", "setup_screen", back) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("步驟 ${step + 1}／3 · ${listOf("帳號", "權限與車輛", "開始探索")[step]}",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("setup_step"))
                Text("帳號 → 權限與車輛 → 開始探索")
                Text("這是導覽位置，不是完成百分比，也不代表已授權或可以上路。")
            }
        }
        when (step) {
            0 -> {
                Text("使用原本的 Google 帳號", style = MaterialTheme.typography.titleLarge)
                Text(status.accountMessage)
                Text("帳號頁會顯示登入與 Honda 帳號讀取的真實結果。車型與騎乘紀錄仍由各功能入口讀取；此處沒有背景批次下載。")
                Entry("帳號管理", "未配置或不想現在登入，都可以繼續查看導覽及本機功能。",
                    "查看帳號與登入", "setup_account") { onOpen("account") }
                Button(onClick = { move(1) }, modifier = Modifier.fillMaxWidth().testTag("setup_next")) {
                    Text(if (status.signedIn) "繼續：權限與車輛" else "先繼續導覽，稍後登入")
                }
            }
            1 -> {
                Text("只開啟你要使用的功能", style = MaterialTheme.typography.titleLarge)
                Entry("權限與功能", "查看手機的實際授權狀態。可以拒絕或稍後處理；系統設定返回後會重新讀取。不是每個功能都需要所有權限。",
                    "查看權限與功能", "setup_permissions") { onOpen("permissions") }
                Entry("車輛與配對", if (status.signedIn) "到車庫確認已保存車輛，或查看車款與配對說明。進入導引不會自動配對或連線。"
                    else "需要登入才能讀取車型與車庫。沒有完成登入時，不會開始搜尋、配對或連線。",
                    if (status.signedIn) "查看車輛與配對" else "先查看帳號", "setup_vehicles") { onOpen(if (status.signedIn) "vehicles" else "account") }
                Button(onClick = { move(2) }, modifier = Modifier.fillMaxWidth().testTag("setup_next")) { Text("繼續：探索摘要") }
            }
            else -> {
                Text("從首頁開始探索", style = MaterialTheme.typography.titleLarge)
                Text(status.accountMessage)
                Text("目前連線：${status.rideMessage}")
                Text("${if (status.signedIn) "帳號相關功能可從首頁進入，仍需各自的權限、網路或可用播放器。" else "目前可用：設定導引、權限說明、連線狀態與本機診斷；帳號功能需要登入。"}")
                Text("導覽走到這裡不代表設定全部完成。連車前請確認帳號、權限與這個版本的相容性；完整功能與長時間穩定性仍在驗證。")
                Button(onClick = { onOpen("home") }, modifier = Modifier.fillMaxWidth().testTag("setup_explore")) { Text("開始探索 Alpha") }
            }
        }
        TextButton(onClick = { onOpen("home") }, modifier = Modifier.fillMaxWidth().testTag("setup_home")) { Text("稍後繼續，先回首頁") }
    }
    }
}

@Composable
internal fun HomeScreen(status: ShellStatus, onOpen: (String) -> Unit) {
    HomeOverviewContent(status, homeOverview(status), onOpen)
}

@Composable
internal fun SettingsScreen(onOpen: (String) -> Unit, onBack: () -> Unit, developer: Boolean,
    accountMessage: String = "登入與帳號讀取狀態請查看帳號頁。") {
    val preferences = org.openridesync.app.ui.theme.rememberAppearancePreferences()
    val appearance by preferences.mode.collectAsState()
    SettingsHubContent(onOpen, onBack, appearance, preferences::setMode,
        accountMessage = accountMessage, developer = developer)
}
