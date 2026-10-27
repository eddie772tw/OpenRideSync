package org.openridesync.app.runtime

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openridesync.app.weather.HomeWeatherState
import org.openridesync.app.weather.WeatherKind

internal fun signalText(signal: RideSignal): String = when (signal.availability) {
    SignalAvailability.READY -> signal.level?.takeIf { it in 0..4 }?.let { "$it/4" } ?: "強度未知"
    SignalAvailability.OFF -> "已關閉"
    SignalAvailability.DISCONNECTED -> "未連線"
    SignalAvailability.NO_SIM -> "無 SIM"
    SignalAvailability.UNSUPPORTED -> "不支援"
    SignalAvailability.UNAVAILABLE -> "未知"
}

/** Keep phone controls reachable even when large text wraps the status row in a short window. */
@Composable
internal fun RideStatusLayout(status: RideDeviceStatus?, weather: HomeWeatherState = HomeWeatherState(),
    onWeather: (() -> Unit)? = null, onPermissions: (() -> Unit)? = null,
    weatherSelected: Boolean = false, statusSelected: Int? = null, content: @Composable () -> Unit) {
    val statusScroll = rememberScrollState()
    LaunchedEffect(weatherSelected) { if (weatherSelected) statusScroll.scrollTo(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusMaxHeight = maxHeight * .4f
        Column(Modifier.fillMaxSize()) {
            if (status != null) Box(Modifier.heightIn(max = statusMaxHeight)
                .verticalScroll(statusScroll).testTag("ride_status_viewport")) {
                RideStatusBar(status, weather, onWeather, onPermissions, weatherSelected, statusSelected)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** Glanceable phone observations; HU may show the same facts without an actionable weather entry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RideStatusBar(status: RideDeviceStatus, weather: HomeWeatherState = HomeWeatherState(),
    onWeather: (() -> Unit)? = null, onPermissions: (() -> Unit)? = null, weatherSelected: Boolean = false,
    statusSelected: Int? = null) {
    val ink = Color.White
    val low = status.batteryPercent?.let { it in 0..15 } == true
    Surface(color = Color(0xff202124), contentColor = ink, modifier = Modifier.fillMaxWidth().testTag("ride_status_bar")) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val gap = 8.dp
            val minimum = 72.dp + 56.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val columns = when {
                maxWidth >= minimum * 4 + gap * 3 -> 4
                maxWidth >= minimum * 2 + gap -> 2
                else -> 1
            }
            val tile = Modifier.width((maxWidth - gap * (columns - 1)) / columns)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap), maxItemsInEachRow = columns) {
                val reading = weather.current?.weather?.takeIf { weather.signedIn && weather.enabled }
                val weatherValue = reading?.temperature(weather.unit) ?: when {
                    !weather.signedIn -> "未登入"
                    !weather.enabled -> "已停用"
                    !weather.locationGranted -> "未授權"
                    weather.loading -> "更新中"
                    else -> "未更新"
                }
                val weatherDescription = listOfNotNull("天氣", weatherValue, reading?.description,
                    if (reading != null) weather.fetchedAtMillis?.let {
                        "上次更新 ${android.text.format.DateFormat.format("MM/dd HH:mm", it)}"
                    } ?: "更新時間未知" else null).joinToString("，")
                StatusMetric(weatherValue, weatherDescription, "ride_status_weather", ink, tile,
                    actionable = true, onClick = onWeather, selected = weatherSelected || statusSelected == 0) {
                    OemRideWeatherIcon(reading?.kind ?: WeatherKind.UNKNOWN)
                }
                val battery = status.batteryPercent?.takeIf { it in 0..100 }
                val batteryValue = battery?.let { "$it%" } ?: "未知"
                StatusMetric(batteryValue,
                    "${if (status.charging) "手機・充電中" else if (low) "手機・低電量" else "手機電量"}，$batteryValue",
                    "ride_status_battery", if (low) Color(0xffffb4ab) else ink, tile, selected = statusSelected == 1) {
                    Canvas(Modifier.size(48.dp)) {
                        drawRoundRect(color = if (low) Color(0xffffb4ab) else ink,
                            topLeft = Offset(2.dp.toPx(), 10.dp.toPx()), size = Size(39.dp.toPx(), 28.dp.toPx()),
                            cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(3.dp.toPx()))
                        drawRect(ink, Offset(44.dp.toPx(), 19.dp.toPx()), Size(4.dp.toPx(), 10.dp.toPx()))
                        if (battery != null && battery > 0) drawRect(if (low) Color(0xffffb4ab) else ink,
                            Offset(6.dp.toPx(), 14.dp.toPx()), Size(31.dp.toPx() * battery / 100f, 20.dp.toPx()))
                        if (status.charging) {
                            val bolt = Path().apply {
                                moveTo(26.dp.toPx(), 12.dp.toPx()); lineTo(16.dp.toPx(), 26.dp.toPx())
                                lineTo(23.dp.toPx(), 26.dp.toPx()); lineTo(19.dp.toPx(), 37.dp.toPx())
                                lineTo(32.dp.toPx(), 21.dp.toPx()); lineTo(25.dp.toPx(), 21.dp.toPx()); close()
                            }
                            drawPath(bolt, ink, style = Stroke(2.dp.toPx()))
                            drawPath(bolt, Color(0xff202124))
                        }
                    }
                }
                StatusMetric(compactSignalText(status.mobile), signalDescription("行動訊號", status.mobile),
                    "ride_status_mobile", ink, tile, selected = statusSelected == 2) { SignalIcon(status.mobile, false) }
                val wifiNeedsSettings = status.wifi.availability == SignalAvailability.READY &&
                    status.wifi.nameIssue in setOf(SignalNameIssue.PERMISSION_REQUIRED, SignalNameIssue.LOCATION_OFF)
                StatusMetric(compactSignalText(status.wifi), signalDescription("Wi‑Fi", status.wifi),
                    "ride_status_wifi", ink, tile, actionable = wifiNeedsSettings,
                    onClick = if (wifiNeedsSettings) onPermissions else null, selected = statusSelected == 3) { SignalIcon(status.wifi, true) }
                if (status.internet == false) Text("離線", color = Color(0xffffd59c),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("ride_status_offline"))
            }
        }
    }
}

@Composable
private fun StatusMetric(value: String, description: String, tag: String, color: Color, modifier: Modifier,
    actionable: Boolean = false, onClick: (() -> Unit)? = null, selected: Boolean = false, icon: @Composable () -> Unit) {
    val intoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) intoView.bringIntoView() }
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(48.dp).testTag("${tag}_icon"), contentAlignment = Alignment.Center) { icon() }
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                color = color, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val frame = modifier.bringIntoViewRequester(intoView).testTag(tag).semantics(mergeDescendants = true) {
        contentDescription = description
        this.selected = selected
    }
    val border = if (selected) BorderStroke(2.dp,
        Brush.horizontalGradient(listOf(Color(0xff4ae779), Color(0xff277853)))) else null
    if (actionable) Surface(onClick = { onClick?.invoke() }, enabled = onClick != null,
        color = Color(0xff30343b), shape = RoundedCornerShape(12.dp), border = border, modifier = frame, content = content)
    else Surface(color = Color(0xff30343b), shape = RoundedCornerShape(12.dp), border = border, modifier = frame, content = content)
}

private fun compactSignalText(signal: RideSignal): String = when {
    signal.availability == SignalAvailability.READY -> signal.name?.takeIf(String::isNotBlank) ?: when (signal.nameIssue) {
        SignalNameIssue.PERMISSION_REQUIRED -> "未授權"
        SignalNameIssue.LOCATION_OFF -> "定位關閉"
        else -> "未知"
    }
    signal.availability == SignalAvailability.OFF -> "關閉"
    signal.availability == SignalAvailability.DISCONNECTED -> "未連線"
    else -> signalText(signal)
}

private fun signalDescription(label: String, signal: RideSignal): String =
    listOfNotNull(label, signal.name.takeIf { signal.availability == SignalAvailability.READY }, signalText(signal),
        when (signal.nameIssue) {
            SignalNameIssue.PERMISSION_REQUIRED -> "顯示名稱需要精確位置授權，可到權限與功能自行決定"
            SignalNameIssue.LOCATION_OFF -> "顯示名稱需要開啟手機定位，可到權限與功能查看說明"
            else -> null
        }).joinToString("，")

@Composable
private fun SignalIcon(signal: RideSignal, wifi: Boolean) {
    val level = signal.level?.takeIf { signal.availability == SignalAvailability.READY && it in 0..4 }
    Canvas(Modifier.size(48.dp)) {
        if (wifi) {
            drawCircle(Color.White.copy(alpha = if (level != null && level > 0) 1f else .3f), 3.dp.toPx(), Offset(size.width / 2, 45.dp.toPx()))
            for (i in 1..3) {
                val radius = (i * 10).dp.toPx()
                drawArc(Color.White.copy(alpha = if (level != null && level > i) 1f else .3f), 225f, 90f,
                    false, Offset(size.width / 2 - radius, 45.dp.toPx() - radius), Size(radius * 2, radius * 2), style = Stroke(3.dp.toPx()))
            }
        } else for (i in 1..4) {
            val height = (i * 11).dp.toPx()
            drawRect(Color.White.copy(alpha = if (level != null && level >= i) 1f else .3f),
                Offset((4 + (i - 1) * 11).dp.toPx(), size.height - height), Size(7.dp.toPx(), height))
        }
    }
}

@SuppressLint("DiscouragedApi") // Public builds can omit the indexed OEM resource bundle.
@Composable
internal fun OemRideWeatherIcon(kind: WeatherKind, fallbackColor: Color = Color.White) {
    val name = when (kind) {
        WeatherKind.SUNNY -> "sunny"
        WeatherKind.CLEAR -> "clear"
        WeatherKind.FEW_CLOUDS_DAY -> "few_clouds_day"
        WeatherKind.FEW_CLOUDS_NIGHT -> "few_clouds_night"
        WeatherKind.SCATTERED_CLOUDS_DAY, WeatherKind.SCATTERED_CLOUDS_NIGHT, WeatherKind.BROKEN_CLOUDS -> "cloudy"
        WeatherKind.DRIZZLE, WeatherKind.RAIN -> "rain"
        WeatherKind.THUNDERSTORM -> "thunderstorm"
        WeatherKind.SNOW -> "snow"
        WeatherKind.ATMOSPHERE -> "mist"
        WeatherKind.UNKNOWN -> "l"
    }
    val resources = LocalResources.current
    val id = resources.getIdentifier("oem_ride_weather_$name", "drawable", LocalContext.current.packageName)
    if (kind != WeatherKind.UNKNOWN && id != 0)
        Image(painterResource(id), contentDescription = null, modifier = Modifier.size(48.dp))
    else Text("—", color = fallbackColor, style = MaterialTheme.typography.headlineLarge)
}
