package org.openridesync.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import org.openridesync.app.runtime.OemRideWeatherIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.media.session.PlaybackState
import org.openridesync.app.media.MediaPlayback
import org.openridesync.app.media.DispatchResult

/** Stateless production content. The optional background draws only an already-resolved vehicle asset. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun HomeOverviewContent(status: ShellStatus, overview: HomeOverview,
    onOpen: (String) -> Unit, modifier: Modifier = Modifier,
    showFirstPairHint: Boolean = false, onDismissFirstPairHint: () -> Unit = {},
    onMediaPrevious: ((String) -> Unit)? = null, onMediaToggle: ((String) -> Unit)? = null,
    onMediaNext: ((String) -> Unit)? = null,
    accountName: String = "帳號名稱未提供", vehicleColor: Color? = null,
    fallbackBackground: @Composable BoxScope.() -> Unit = {},
    vehicleBackground: @Composable BoxScope.(HomeVehicle) -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    fun openFeature(route: String) = onOpen(if (status.signedIn) route else "account")
    Scaffold(modifier) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val twoColumns = maxWidth >= 360.dp && LocalDensity.current.fontScale < 1.3f
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("home_screen")
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("OpenRideSync", style = MaterialTheme.typography.headlineLarge)
                if (showFirstPairHint) {
                    Surface(shape = RoundedCornerShape(24.dp), color = scheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().testTag("home_first_pair_hint")) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("準備加入第一台車？", style = MaterialTheme.typography.titleMedium)
                            Text("可以先查看車輛與配對說明，也能稍後再設定。開啟車庫不會自動配對或連線。")
                            TextButton(onClick = { onOpen("vehicles") }, modifier = Modifier.testTag("home_first_pair_open")) {
                                Text("查看車輛與配對")
                            }
                            TextButton(onClick = onDismissFirstPairHint, modifier = Modifier.testTag("home_first_pair_dismiss")) {
                                Text("稍後再說")
                            }
                        }
                    }
                }
                HomePair(twoColumns, 1.18f, 1f, {
                    HomeTile("帳號", if (status.signedIn) accountName else "尚未登入",
                        "home_account", { onOpen("account") }, color = scheme.primaryContainer)
                }, {
                    HomeTile("天氣", overview.weather.value, "home_weather", { openFeature("weather") },
                        icon = { Box(Modifier.testTag("home_weather_icon")) {
                            OemRideWeatherIcon(overview.weatherKind, fallbackColor = scheme.onSurface)
                        } },
                        detail = overview.weatherPlace)
                })
                Surface(onClick = { openFeature("vehicles") }, shape = RoundedCornerShape(28.dp),
                    color = vehicleColor?.let { lerp(scheme.surface, it, .35f) } ?: scheme.secondaryContainer,
                    border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag("home_vehicles")) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp)) {
                        // Catalog art is decorative only; it never supplies a primary vehicle identity.
                        Box(Modifier.matchParentSize().testTag("home_vehicle_background")) {
                            overview.vehicle?.let { vehicleBackground(it) } ?: fallbackBackground()
                        }
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
                            Color.Transparent, scheme.surface.copy(alpha = .96f)))))
                        Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("我的車輛", style = MaterialTheme.typography.labelLarge)
                            Text(overview.vehicle?.name ?: if (overview.garageHasNoPrimary) "尚未設定" else "主要車輛",
                                style = MaterialTheme.typography.headlineSmall)

                        }
                    }
                }
                HomePair(twoColumns, 1f, 1.18f, {
                    HomeTile("系統媒體", overview.media.value, "home_media", { openFeature("media") },
                        detail = overview.media.detail.takeIf { overview.mediaControl != null }.orEmpty(), extra = {
                            overview.mediaControl?.let { target ->
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = { onMediaPrevious?.invoke(target.id) },
                                        enabled = onMediaPrevious != null && target.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                                        modifier = Modifier.testTag("home_media_previous")) { Text("上一首") }
                                    val playing = target.playback == MediaPlayback.PLAYING
                                    val toggleAction = if (playing) PlaybackState.ACTION_PAUSE else
                                        if (target.playback in setOf(MediaPlayback.NONE, MediaPlayback.STOPPED, MediaPlayback.PAUSED))
                                            PlaybackState.ACTION_PLAY else 0L
                                    OutlinedButton(onClick = { onMediaToggle?.invoke(target.id) },
                                        enabled = onMediaToggle != null && target.actions and toggleAction != 0L,
                                        modifier = Modifier.testTag("home_media_toggle")) { Text(if (playing) "暫停" else "播放") }
                                    OutlinedButton(onClick = { onMediaNext?.invoke(target.id) },
                                        enabled = onMediaNext != null && target.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                                        modifier = Modifier.testTag("home_media_next")) { Text("下一首") }
                                }
                            }
                            overview.mediaResult?.let { result ->
                                Text(when (result) {
                                    DispatchResult.DISPATCHED -> "控制請求已送出，等待播放器回報。"
                                    DispatchResult.UNSUPPORTED -> "播放器目前不支援這個操作。"
                                    DispatchResult.STALE -> "媒體工作階段已變更，請重新確認播放器。"
                                    DispatchResult.ACCESS_REQUIRED -> "媒體存取授權已變更，請到權限頁檢查。"
                                    DispatchResult.UNAVAILABLE -> "目前無法送出媒體控制，請開啟音樂頁查看。"
                                }, Modifier.testTag("home_media_result"), style = MaterialTheme.typography.bodySmall)
                            }
                        })
                }, {
                    HomeTile("最近騎乘", overview.trip.value, "home_trips", { openFeature("trips") },
                        detail = overview.tripDate,
                        color = scheme.tertiaryContainer)
                })
                HomeTile("儀表連線狀態", status.rideMessage, "home_ride", { onOpen("ride") })
                Button(onClick = { onOpen("setup") }, modifier = Modifier.fillMaxWidth().testTag("home_setup")) {
                    Text("開始設定／繼續導引")
                }
                OutlinedButton(onClick = { onOpen("settings") }, modifier = Modifier.fillMaxWidth().testTag("home_settings")) {
                    Text("設定、帳號與協助")
                }
                OutlinedButton(onClick = { openFeature("messages") }, modifier = Modifier.fillMaxWidth().testTag("home_messages")) {
                    Text("訊息通知與快速回覆")
                }
                OutlinedButton(onClick = { openFeature("calls") }, modifier = Modifier.fillMaxWidth().testTag("home_calls")) {
                    Text("通話通知與控制")
                }
                TextButton(onClick = { onOpen("diagnostics") }, modifier = Modifier.testTag("home_diagnostics")) {
                    Text("診斷與匯出（不需登入）")
                }
            }
        }
    }
}

@Composable
private fun HomePair(twoColumns: Boolean, firstWeight: Float, secondWeight: Float,
    first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (twoColumns) Row(Modifier.fillMaxWidth().testTag("home_tile_pair"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(firstWeight)) { first() }; Box(Modifier.weight(secondWeight)) { second() }
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) { first(); second() }
}

@Composable
private fun HomeTile(title: String, value: String, tag: String,
    onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    detail: String = "", icon: (@Composable () -> Unit)? = null, extra: @Composable ColumnScope.() -> Unit = {}) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = color, tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            if (icon != null) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                icon()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(value, style = MaterialTheme.typography.titleLarge)
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(value, style = MaterialTheme.typography.titleLarge)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
            extra()
        }
    }
}
