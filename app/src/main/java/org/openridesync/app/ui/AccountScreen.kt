package org.openridesync.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.openridesync.app.AccountState
import org.openridesync.app.auth.AuthState
import org.openridesync.app.auth.AuthStatus
import java.util.Locale

/** Field authorities match OEM AccountInfo; identifiers never enter diagnostics or saved state. */
@Composable
fun AccountScreen(
    auth: AuthState, account: AccountState, email: String?, firebaseUserId: String?,
    onLogin: () -> Unit, onRead: () -> Unit, onCancel: () -> Unit,
    onSignOut: () -> Unit, onDiagnostics: () -> Unit, onVehicles: () -> Unit, onTrips: () -> Unit,
    onMedia: () -> Unit, onWeather: () -> Unit = {}, onRide: () -> Unit = {},
    onBack: (() -> Unit)? = null, showFeatureLinks: Boolean = true,
    onPermissions: () -> Unit = {}, onDeveloper: (() -> Unit)? = null, onHandlebarTest: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(auth.status) { if (auth.status != AuthStatus.SIGNED_IN) confirmSignOut = false }
    val signedIn = auth.status == AuthStatus.SIGNED_IN

    Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            onBack?.let { TextButton(onClick = it, modifier = Modifier.testTag("account_back")) { Text("返回") } }
            Text("OpenRideSync", style = MaterialTheme.typography.labelLarge)
            Text("帳號資訊", style = MaterialTheme.typography.headlineMedium)
            if (signedIn) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AccountField("電子郵件", email?.takeIf { it.isNotBlank() } ?: "未提供")
                        HorizontalDivider()
                        AccountField("使用者 ID", firebaseUserId ?: "暫時無法取得")
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("OpenRideSync User ID", firebaseUserId))
                            // Android 13+ supplies its own clipboard feedback, as in OEM AccountInfo.
                            if (android.os.Build.VERSION.SDK_INT <= 32) scope.launch { snackbars.showSnackbar("已複製使用者 ID") }
                        }, enabled = !firebaseUserId.isNullOrBlank(), modifier = Modifier.testTag("account_copy_id")) {
                            Text("複製使用者 ID")
                        }
                        HorizontalDivider()
                        AccountField("居住地", residence(account.countryCode), Modifier.testTag("account_residence"))
                    }
                }
                Text(accountMessage(account), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (account.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消帳號讀取") }
                } else if (account.requiresLogin) {
                    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().testTag("account_login")) { Text("重新進行 Google 登入") }
                } else {
                    Button(onClick = onRead, modifier = Modifier.fillMaxWidth().testTag("account_read")) { Text("讀取 Honda 帳號") }
                    TextButton(onClick = onLogin, modifier = Modifier.fillMaxWidth().testTag("account_login")) { Text("重新進行 Google 登入") }
                }
                OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth().testTag("account_sign_out")) {
                    Text("登出 OpenRideSync")
                }
            } else {
                Text(when (auth.status) {
                    AuthStatus.UNAVAILABLE -> "此版本尚未設定帳號登入。你仍可使用本機診斷功能。"
                    AuthStatus.SIGNING_IN -> "正在完成 Google 登入，請稍候。"
                    AuthStatus.ERROR -> "登入未完成，請稍後再試或查看診斷資訊。"
                    else -> "請先在原廠 Honda RoadSync App 完成首次註冊與初始設定，再使用同一個 Google 帳號登入（目前版本不支援直接註冊新帳號）。"
                }, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (auth.status == AuthStatus.SIGNING_IN) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = onLogin, enabled = auth.status != AuthStatus.UNAVAILABLE && auth.status != AuthStatus.SIGNING_IN,
                    modifier = Modifier.fillMaxWidth().testTag("account_login")) { Text("Google 登入") }
            }
            TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth().testTag("account_diagnostics")) { Text("診斷與匯出") }
            if (showFeatureLinks) {
            HorizontalDivider()
            OutlinedButton(onClick = onVehicles, enabled = signedIn, modifier = Modifier.fillMaxWidth().testTag("account_vehicles")) { Text("車輛配對與連線") }
            OutlinedButton(onClick = onRide, enabled = signedIn, modifier = Modifier.fillMaxWidth().testTag("account_ride")) { Text("儀表連線狀態") }
            OutlinedButton(onClick = onTrips, enabled = signedIn, modifier = Modifier.fillMaxWidth().testTag("account_trips")) { Text("騎乘紀錄") }
            OutlinedButton(onClick = onMedia, enabled = signedIn, modifier = Modifier.fillMaxWidth().testTag("account_media")) { Text("音樂控制") }
            OutlinedButton(onClick = onWeather, enabled = signedIn, modifier = Modifier.fillMaxWidth().testTag("account_weather")) { Text("天氣與預報") }
            TextButton(onClick = onPermissions, modifier = Modifier.fillMaxWidth().testTag("account_permissions")) { Text("權限與功能") }
            onDeveloper?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth().testTag("account_developer")) { Text("開發選項：儀表模式預覽") } }
            onHandlebarTest?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth().testTag("account_handlebar_test")) { Text("開發選項：實車按鍵引導測試") } }
            }
            Text("開發版本：提供帳號、車庫連線、騎乘紀錄、音樂控制、天氣與診斷；完整騎乘功能仍在建置中。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmSignOut && signedIn) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("登出 OpenRideSync？") },
        text = { Text("這會登出此 App。Honda 帳號與雲端資料會保留，之後可使用相同 Google 帳號登入。") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }, modifier = Modifier.testTag("account_confirm_sign_out")) { Text("登出") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }, modifier = Modifier.testTag("account_cancel_sign_out")) { Text("取消") } },
    )
}

@Composable
private fun AccountField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

internal fun residence(code: String?): String = when {
    code == null -> "尚未取得"
    !code.matches(Regex("[A-Z]{2}")) -> "未識別的居住地"
    else -> Locale.Builder().setLanguage(Locale.getDefault().language).setRegion(code).build().displayCountry
}

private fun accountMessage(state: AccountState): String = when {
    state.busy -> "正在更新 Honda 帳號資訊…"
    state.registrationRequired -> "找不到對應的 Honda 帳號。請先在原廠 Honda RoadSync App 完成首次註冊與條款設定後，再回來重新登入或讀取。"
    state.requiresLogin -> "請重新登入，以確認 Honda 帳號及居住地。"
    state.cancelled -> "已取消更新。可隨時重新讀取帳號資訊。"
    state.errorCode != null -> "暫時無法讀取 Honda 帳號，請稍後再試。"
    state.countryErrorCode != null -> "Honda 帳號已確認，居住地暫時無法讀取。請稍後再試。"
    state.details != null && state.countryCode != null -> "Honda 帳號資訊已更新。"
    else -> "登入已完成，請讀取 Honda 帳號資訊。"
}
