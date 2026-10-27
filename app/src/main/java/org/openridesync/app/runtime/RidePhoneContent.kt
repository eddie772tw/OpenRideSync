package org.openridesync.app.runtime

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.ui.OemUiAsset
import org.openridesync.app.ui.OemUiIcon

internal val RideState.phoneLocked: Boolean
    get() = stage == RideStage.CONNECTED && mode == Gen1Input.Function.MODE_HU

/** Phone presentation; only observed vehicle feedback releases the lock. */
@Composable
internal fun RidePhoneContent(state: RideState, onOpenRide: (() -> Unit)? = null,
    onRequestSab: (() -> Unit)? = null,
    content: @Composable () -> Unit) {
    BackHandler(enabled = state.phoneLocked) { /* Only an observed mode change releases this screen. */ }
    if (state.phoneLocked) {
        MaterialTheme(colorScheme = RideColors) {
        Surface(Modifier.fillMaxSize().testTag("ride_phone_locked")
            .semantics { paneTitle = "儀表模式" }, color = RideBackground, contentColor = Color.White) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(color = Color(0xffd64747), contentColor = Color.White,
                    modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("儀表模式", style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() })
                    Text(state.name?.takeIf(String::isNotBlank) ?: "車輛已連線", style = MaterialTheme.typography.titleLarge)
                    Text("若要繼續使用 App，請先在車輛儀表切換回 RoadSync 模式。",
                        modifier = Modifier.testTag("ride_phone_switch_hint"), style = MaterialTheme.typography.titleMedium)
                    Text("連線仍由背景服務維持。收到 RoadSync 模式回報後，會自動恢復原本頁面。")
                    Text("原廠指引為長按車上左鍵；請以車輛儀表的操作提示為準。")
                    if (onRequestSab != null) RoadSyncModeRequest(state.modeRequest, onRequestSab, locked = true)
                }
                }
            }
        }
        }
        return
    }
    val unknown = state.stage == RideStage.CONNECTED && state.mode != Gen1Input.Function.MODE_SAB
    if (state.stage == RideStage.IDLE || (onOpenRide == null && !(unknown && onRequestSab != null))) {
        content(); return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusMaxHeight = maxHeight * 0.5f
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().heightIn(max = statusMaxHeight)
                    .verticalScroll(rememberScrollState()).testTag("ride_phone_status")) {
                Column {
                    Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(when (state.stage) {
                                RideStage.CONNECTING -> "車輛連線中"
                                RideStage.RECONNECTING -> "正在重新連接車輛"
                                RideStage.STOPPING -> "正在中斷車輛連線"
                                RideStage.FAILED -> "車輛連線失敗"
                                else -> if (state.mode == Gen1Input.Function.MODE_SAB) "RoadSync 模式" else "已連線・等待車輛回報模式"
                            }, style = MaterialTheme.typography.titleSmall)
                            state.name?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (onOpenRide != null) TextButton(onClick = onOpenRide,
                            modifier = Modifier.testTag("ride_phone_open")) { Text("連線狀態") }
                    }
                    if (unknown && onRequestSab != null) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoadSyncModeRequest(state.modeRequest, onRequestSab, locked = false)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                content()
            }
        }
    }
}

@Composable
private fun RoadSyncModeRequest(stage: ModeRequestStage, onRequest: () -> Unit, locked: Boolean) {
    Button(onClick = onRequest, enabled = stage !in setOf(ModeRequestStage.REQUESTING, ModeRequestStage.WAITING_FOR_MODE),
        modifier = Modifier.testTag("ride_phone_request_sab")) {
        Text(if (locked) "要求切回 RoadSync" else "要求 RoadSync 模式")
    }
    Text(when (stage) {
        ModeRequestStage.IDLE -> if (locked) "也可由 App 提出切換要求；收到車輛回報前仍維持儀表模式。"
            else "可由 App 明確要求 RoadSync 模式；收到車輛回報前，手把功能保持停用。"
        ModeRequestStage.REQUESTING -> "正在送出 RoadSync 切換要求…"
        ModeRequestStage.WAITING_FOR_MODE -> "車輛已接受要求，等待 RoadSync 模式回報…"
        ModeRequestStage.REJECTED -> "車輛未接受切換要求。可依儀表提示操作或重試。"
        ModeRequestStage.TIMED_OUT -> if (locked) "仍未收到 RoadSync 模式回報，畫面保持鎖定。可依儀表提示操作或重試。"
            else "仍未收到 RoadSync 模式回報，手把功能保持停用。可依儀表提示操作或重試。"
        ModeRequestStage.UNAVAILABLE -> "目前無法送出要求，請依儀表提示操作。"
    }, Modifier.testTag("ride_phone_mode_request_state"))
}
