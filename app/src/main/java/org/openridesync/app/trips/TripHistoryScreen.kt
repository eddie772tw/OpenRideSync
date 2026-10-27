package org.openridesync.app.trips

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import org.openridesync.app.auth.AuthStatus
import org.openridesync.honda.wire.HondaWire.DailyTrips
import org.openridesync.honda.wire.HondaWire.TripDetail

@Composable
fun TripHistoryScreen(onBack: () -> Unit, onAccount: () -> Unit = {}) {
    val model: TripViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val auth by model.session.state.collectAsStateWithLifecycle()
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
    val back = { if (!model.back()) { model.onStop(); onBack() } }
    BackHandler(onBack = back)
    key(model.session.revision) {
        TripHistoryContent(state, auth.status == AuthStatus.SIGNED_IN, back,
            model::refresh, model::retry, model::loadMore, model::openDay, model::cancel,
            { model.onStop(); onAccount() })
    }
}

/** Synthetic UI tests supply data here; this composable never initiates service calls itself. */
@Composable
internal fun TripHistoryContent(
    state: TripState, signedIn: Boolean, onBack: () -> Unit,
    onRefresh: () -> Unit, onRetry: () -> Unit, onMore: () -> Unit,
    onDay: (DailyTrips) -> Unit, onCancel: () -> Unit, onAccount: () -> Unit = {},
) {
    val scrollStates = rememberSaveableStateHolder()
    var routeId by rememberSaveable(state.selectedDay?.tripDate, signedIn) { mutableStateOf<String?>(null) }
    val route = state.details?.dailyTripDetailsList?.firstOrNull { it.tripUuid == routeId }
    val back = { if (routeId != null) routeId = null else onBack() }
    BackHandler(enabled = routeId != null, onBack = back)
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = back, modifier = Modifier.testTag("trips_back")) { Text("返回") }
                Text(if (routeId != null) "路線詳情" else if (state.selectedDay == null) "騎乘紀錄" else "當日騎乘", Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onRefresh, enabled = signedIn && !state.busy && routeId == null) { Text(if (!state.hasLoaded && state.selectedDay == null) "讀取紀錄" else "更新") }
            }
            scrollStates.SaveableStateProvider("${state.offsetMillis}:${routeId ?: "history"}") {
            LazyColumn(Modifier.fillMaxSize().testTag("trips_list"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!signedIn) item {
                    Text("請先登入，再讀取此帳號的騎乘紀錄。")
                    Button(onClick = onAccount, modifier = Modifier.testTag("trips_account")) { Text("前往帳號") }
                }
                else {
                    item { Text("讀取、更新、重試、載入更早紀錄或展開日期才會查詢雲端。離頁或進入背景會取消未完成讀取；返回不會自動重試。", style = MaterialTheme.typography.bodySmall) }
                    if (state.busy) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = onCancel) { Text("取消讀取") }
                    }
                    if (state.storageError) item { Text("本機快取無法讀寫。畫面資料不代表已儲存。") }
                    if (state.cancelled) item {
                        Text("已取消讀取，尚未完成此次更新。")
                        TextButton(onClick = onRetry) { Text("重新讀取") }
                    }
                    if (state.errorCode != null) item {
                        val account = tripNeedsAccount(state.errorCode)
                        Text(if (account) "帳號需要重新登入。" else "暫時無法讀取騎乘紀錄。")
                        OutlinedButton(onClick = if (account) onAccount else onRetry, enabled = !state.busy,
                            modifier = Modifier.testTag(if (account) "trips_account" else "trips_retry")) { Text(if (account) "前往帳號" else "重試") }
                    }
                    val day = state.selectedDay
                    if (routeId != null) {
                        if (state.detailsCached) item { Text("目前顯示本機快取的當日明細。") }
                        if (route != null) item { TripRouteDetail(route, is24Hour) }
                        else item { Text("此路線目前無可用資料。請返回日期後明確重新讀取。") }
                    } else {
                        if (state.cached) item { Text("目前顯示本機快取，更新後才代表最新雲端資料。") }
                        item {
                            TripCard("騎乘總覽" + if (state.summaryCached) "（快取）" else "") {
                                Text("總距離（全部歷史）：${state.summary?.let { TripFormat.distance(it.totalDistance, summary = true) } ?: "未提供"}")
                                Text("騎乘次數（已載入範圍）：${if (state.hasLoaded || state.days.isNotEmpty()) loadedTripCount(state.days).toString() else "尚未讀取"}")
                                Text("平均速度（全部歷史）：${tripSummarySpeed(state.summary)?.let { TripFormat.speed(it) } ?: "未提供"}")
                                Text("平均速度以總距離 ÷ 總時間計算；次數依已載入日期的不同騎乘識別碼計算。", style = MaterialTheme.typography.bodySmall)
                                if (state.hasMore) Text("還有更早紀錄尚未載入。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (state.summaryErrorCode != null) item {
                            Text(if (tripNeedsAccount(state.summaryErrorCode)) "騎乘總覽需要重新登入；下方保留可用紀錄。" else "騎乘總覽暫時無法更新；下方為可用的每日紀錄。")
                            if (tripNeedsAccount(state.summaryErrorCode) && !tripNeedsAccount(state.errorCode))
                                TextButton(onClick = onAccount, modifier = Modifier.testTag("trips_summary_account")) { Text("前往帳號") }
                        }
                        if (tripListIsEmpty(state)) item { Text("尚無騎乘紀錄。") }
                        if (!state.hasLoaded && day == null && !state.busy && !state.cancelled && state.errorCode == null) item { Text("尚未讀取紀錄。按下讀取紀錄後才會查詢。") }
                        // Selected day fallback also supports direct synthetic/content callers.
                        val visibleDays = if (day != null && state.days.none { it.tripDate == day.tripDate }) state.days + day else state.days
                        visibleDays.forEachIndexed { index, value ->
                            val expanded = day?.tripDate == value.tripDate
                            item(key = value.tripDate) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (index == 0 || TripFormat.monthKey(value.startTime) != TripFormat.monthKey(visibleDays[index - 1].startTime))
                                        Text(TripFormat.month(value.startTime), style = MaterialTheme.typography.titleMedium)
                                    Card(Modifier.fillMaxWidth().clickable(enabled = expanded || (!state.busy && day == null && value.tripUuidsCount > 0)) {
                                        if (expanded) onBack() else onDay(value)
                                    }.testTag("trip_day_${value.tripDate}")) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(TripFormat.date(value.startTime, includeYear = expanded), style = MaterialTheme.typography.titleMedium)
                                            Text(TripFormat.timeRange(value.startTime, value.endTime, is24Hour))
                                            TripMetrics(value.totalDistance, value.totalTime, value.avgSpeed)
                                            Text("${value.tripUuidsList.distinct().size} 段騎乘・" + if (expanded) "收合日期 ▴" else "展開日期 ▾")
                                            if (value.vehicleDisplayNameList.isNotEmpty()) Text(value.vehicleDisplayNameList.joinToString("、"))
                                        }
                                    }
                                }
                            }
                            if (expanded) {
                                if (state.detailsCached) item { Text("目前顯示本機快取的當日明細。") }
                                state.details?.let { details ->
                                    if (details.dailyTripDetailsCount < value.tripUuidsCount) item { Text("部分騎乘未取得可顯示的路徑資料。") }
                                    item { TripCard("當日總覽") { TripMetrics(details.totalDistance, details.totalTime, details.avgSpeed) } }
                                    itemsIndexed(details.dailyTripDetailsList, key = { _, trip -> "route:${trip.tripUuid}" }) { tripIndex, trip ->
                                        TripCard("第 ${tripIndex + 1} 段騎乘") {
                                            val geometry = remember(trip) { tripRoute(trip.tripLocationsList) }
                                            geometry?.let { Text(TripFormat.timeRange(it.locations.first().timestamp, it.locations.last().timestamp, is24Hour)) }
                                            Text("距離：${TripFormat.distance(trip.totalDistance)}")
                                            Text("平均速度：${TripFormat.speed(trip.avgSpeed)}")
                                            OutlinedButton(onClick = { routeId = trip.tripUuid }, modifier = Modifier.testTag("trip_route_${trip.tripUuid}")) { Text("查看路線") }
                                        }
                                    }
                                }
                                item { Text("收合目前日期後可展開其他日期。", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (state.hasMore) item {
                            OutlinedButton(onClick = onMore, enabled = !state.busy && state.errorCode == null && day == null,
                                modifier = Modifier.fillMaxWidth().testTag("trips_more")) { Text("載入更早紀錄") }
                        }
                    }
                    item { Text("距離以公里、速度以公里／小時顯示；日期時間採裝置時區。", style = MaterialTheme.typography.bodySmall) }
                }
            }
            }
        }
    }
}

@Composable
private fun TripMetrics(distance: Float, seconds: Long, speed: Float) {
    Text("距離：${TripFormat.distance(distance)}")
    Text("騎乘時間：${TripFormat.duration(seconds)}（時:分）")
    Text("平均速度：${TripFormat.speed(speed)}")
}

@Composable
private fun TripCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Local schematic only: no tile provider, network access, geocoding, or location permission. */
@Composable
private fun TripRouteDetail(trip: TripDetail, is24Hour: Boolean) {
    val route = remember(trip) { tripRoute(trip.tripLocationsList) }
    TripCard("路線示意（無街道底圖）") {
        Text("距離：${TripFormat.distance(trip.totalDistance)}")
        Text("平均速度：${TripFormat.speed(trip.avgSpeed)}")
        if (route == null) {
            Text("路線資料缺少或無效，無法繪製。")
        } else {
            val start = route.locations.first()
            val end = route.locations.last()
            Text("起點：約 ${routeCoordinate(start)}")
            Text("開始時間：${TripFormat.timeRange(start.timestamp, start.timestamp, is24Hour)}")
            Text("終點：約 ${routeCoordinate(end)}")
            Text("結束時間：${TripFormat.timeRange(end.timestamp, end.timestamp, is24Hour)}")
            Text(TripFormat.date(start.timestamp, includeYear = true))
            if (TripFormat.monthKey(start.timestamp) != TripFormat.monthKey(end.timestamp) ||
                java.time.Instant.ofEpochSecond(start.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate() !=
                java.time.Instant.ofEpochSecond(end.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
                Text("結束日期：${TripFormat.date(end.timestamp, includeYear = true)}")
            if (route.coarse) Text("含概略定位點，路線與端點位置可能不精確。")
            if (route.points.size == 1) Text("僅有一個定位點，起點與終點重合。")
            else if (route.points.distinct().size == 1) Text("定位點重合，無法顯示移動路徑。")
            Text("北方朝上；依定位時間連線，非道路導航。圓環為起點，實心點為終點。", style = MaterialTheme.typography.bodySmall)
            val line = MaterialTheme.colorScheme.primary
            val finish = MaterialTheme.colorScheme.tertiary
            val background = MaterialTheme.colorScheme.surfaceVariant
            Canvas(Modifier.fillMaxWidth().height(280.dp).testTag("trip_route_canvas")
                .semantics { contentDescription = "路線示意（無街道底圖），${route.points.size} 個定位點，圓環起點與實心終點" }) {
                drawRect(background)
                val extent = (minOf(size.width, size.height) - 40.dp.toPx()).coerceAtLeast(0f)
                fun position(point: RoutePoint) = Offset(size.width / 2 + (point.x - 0.5f) * extent,
                    size.height / 2 + (point.y - 0.5f) * extent)
                val path = Path()
                route.points.forEachIndexed { index, point ->
                    val at = position(point)
                    if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                }
                drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
                drawCircle(line, radius = 9.dp.toPx(), center = position(route.points.first()), style = Stroke(width = 3.dp.toPx()))
                drawCircle(finish, radius = 5.dp.toPx(), center = position(route.points.last()))
            }
        }
    }
}
