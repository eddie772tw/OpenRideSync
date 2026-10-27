package org.openridesync.app.vehicles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.guides.GuideKind
import org.openridesync.app.guides.VehicleGuideScreen
import org.openridesync.honda.wire.HondaWire.Model
import org.openridesync.app.pairing.PairingViewModel
import org.openridesync.app.pairing.PairingScreen
import org.openridesync.app.pairing.PairingStage
import org.openridesync.app.pairing.PairingEntry
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.app.runtime.RideViewModel
import org.openridesync.app.runtime.RideStage
import org.openridesync.app.runtime.RideStatusCard
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect

@Composable
fun VehicleCatalogScreen(onBack: () -> Unit, onRide: () -> Unit = {}, onAccount: () -> Unit = {}) {
    val model: VehicleViewModel = viewModel()
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.cancel()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.cancel() }
    }
    val pairing: PairingViewModel = viewModel()
    val pairingState by pairing.state.collectAsStateWithLifecycle()
    val ride: RideViewModel = viewModel()
    val rideState by ride.connection.state.collectAsStateWithLifecycle()
    val ridePermission by ride.permissionRequest.collectAsStateWithLifecycle()
    LaunchedEffect(pairing) {
        // Ignore the current value when composition is recreated; only a real return reloads.
        pairing.state.map { it.stage }.distinctUntilChanged().drop(1).collect {
            if (it == PairingStage.IDLE) model.reloadPairedVehicles()
        }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val auth by model.session.state.collectAsStateWithLifecycle()
    val back = { if (!model.back()) onBack() }
    BackHandler(onBack = back)
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val detail = state.detail
    val year = state.detailYear ?: selectedYear(detail, null)
    if (pairingState.stage != PairingStage.IDLE && auth.status == AuthStatus.SIGNED_IN) {
        PairingScreen(pairing, pairingState, onPaired = model::openGarage)
        return
    }
    if (state.guide != null && auth.status == AuthStatus.SIGNED_IN) {
        VehicleGuideScreen(model, state, back, onAccount = onAccount, onStartPairing = detail?.let { selected -> ({ if (!model.state.value.garageMutationBusy && !model.state.value.garageMutationError && !model.state.value.garageLoading) { model.cancel(); pairing.start(selected, year) } }) })
        return
    }
    VehicleCatalogContent(state, auth.status == AuthStatus.SIGNED_IN, rideState, ridePermission != null,
        VehicleActions(onBack = back, onRefresh = model::refresh, onCancel = model::cancel,
            onAdd = model::openCatalog, onScope = model::changeScope, onReloadGarage = model::reloadPairedVehicles,
            onRide = onRide, onDisconnect = ride.connection::disconnect, onCancelPermission = ride::cancelPermissionRequest,
            onConnect = { if (!model.state.value.garageMutationBusy && !model.state.value.garageMutationError && !model.state.value.garageLoading) { ride.connect(it); onRide() } }, onSavedInfo = model::showSavedVehicle,
            onGarageChange = { change, vehicle -> model.changeSavedVehicle(change, vehicle) {
                ride.permissionRequest.value == null && pairing.state.value.stage == PairingStage.IDLE &&
                    ride.connection.state.value.stage in setOf(RideStage.IDLE, RideStage.FAILED)
            } },
            onModel = { model.showModel(it) }, onCatalog = model::selectCatalog, onYear = model::selectYear,
            onPair = { if (!model.state.value.garageMutationBusy && !model.state.value.garageMutationError && !model.state.value.garageLoading) detail?.let { pairing.start(it, year) } },
            onSave = { detail?.let { model.saveSelection(it, year) } }, onGuide = model::openGuide, onAccount = onAccount), pageStates, model.imageRequests)
}

internal data class VehicleActions(
    val onBack: () -> Unit = {}, val onRefresh: () -> Unit = {}, val onCancel: () -> Unit = {},
    val onAdd: () -> Unit = {}, val onScope: (Boolean) -> Unit = {}, val onReloadGarage: () -> Unit = {},
    val onRide: () -> Unit = {}, val onDisconnect: () -> Unit = {}, val onCancelPermission: () -> Unit = {},
    val onConnect: (PairedVehicleStore.Vehicle) -> Unit = {}, val onSavedInfo: (PairedVehicleStore.Vehicle) -> Unit = {},
    val onModel: (Model) -> Unit = {}, val onCatalog: (org.openridesync.honda.wire.HondaWire.ModelCatalog) -> Unit = {},
    val onYear: (Int) -> Unit = {}, val onPair: () -> Unit = {}, val onSave: () -> Unit = {},
    val onGuide: (GuideKind) -> Unit = {}, val onAccount: () -> Unit = {},
    val onGarageChange: (GarageChange, PairedVehicleStore.Vehicle) -> Unit = { _, _ -> },
)

/** UI-only seam: rendering state never starts a catalog request, pairing or a connection. */
@Composable
internal fun VehicleCatalogContent(state: VehicleState, signedIn: Boolean,
    rideState: org.openridesync.app.runtime.RideState = org.openridesync.app.runtime.RideState(),
    permissionPending: Boolean = false, actions: VehicleActions = VehicleActions(),
    scrollStates: androidx.compose.runtime.saveable.SaveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder(),
    imageRequests: VehicleImageRequests = remember { VehicleImageRequests() }) {
    val detail = state.detail
    val page = vehiclePage(state)
    val year = state.detailYear ?: selectedYear(detail, null)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = actions.onBack, modifier = Modifier.testTag("vehicles_back")) { Text("返回") }
                Text(when (page) {
                    VehiclePage.GARAGE -> "車庫"
                    VehiclePage.CATALOG -> "新增車輛"
                    VehiclePage.SERIES -> "選擇型號"
                    else -> "車型資訊"
                }, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                if (detail == null) TextButton(onClick = actions.onRefresh,
                    enabled = signedIn && !state.busy && !state.garageLoading) { Text(if (page == VehiclePage.GARAGE) "重讀車庫" else "更新") }
            }
            val pageKey = page.name + ":" + state.ownCountry + ":" + state.countryCode + ":" + (state.activeSeries?.model?.modelSeriesLabel ?: "") + ":" + (detail?.modelLabel ?: "") + ":" + (state.detailVehicleMac ?: "")
            scrollStates.SaveableStateProvider(pageKey) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = vehicleGridColumns(maxWidth.value, LocalDensity.current.fontScale)
                LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("vehicle_list"),
                    contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!signedIn) fullWidthItem {
                        Text("請先登入，再查看此帳號的車庫與車型資料。")
                        Button(onClick = actions.onAccount, modifier = Modifier.testTag("vehicles_account")) { Text("前往帳號") }
                    } else if (page == VehiclePage.GARAGE) {
                        fullWidthItem {
                            GaragePrimaryHero(primaryGarageVehicle(state), imageRequests, actions.onSavedInfo)
                        }
                        fullWidthItem {
                            Text("已保存車輛", style = MaterialTheme.typography.titleLarge)
                            Text("本機配對紀錄；車型偏好不代表已配對。", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = actions.onAdd, modifier = Modifier.fillMaxWidth().testTag("garage_add")) { Text("新增車輛") }
                        }
                        if (state.garageLoading || state.garageMutationBusy) fullWidthItem { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (state.garageMutationError) fullWidthItem {
                            Text("車庫更新結果尚未確認，請重新讀取後再連線或管理。", Modifier.testTag("garage_change_error"))
                            TextButton(onClick = actions.onReloadGarage, enabled = !state.garageLoading && !state.garageMutationBusy) { Text("重新讀取車庫") }
                        }
                        if (state.garageError) fullWidthItem {
                            Text("暫時無法讀取本機車庫，已保留可用內容。", Modifier.testTag("garage_error"))
                            TextButton(onClick = actions.onReloadGarage, enabled = !state.garageLoading) { Text("重新讀取車庫") }
                        } else if (!state.garageLoading && state.garageLoaded && state.pairedVehicles.isEmpty()) fullWidthItem {
                            Text("尚無已保存車輛。從新增車輛選擇車款與年式，再閱讀設定說明並明確開始配對。", Modifier.testTag("garage_empty"))
                        }
                        if (!state.garageLoaded && !state.garageLoading && !state.garageError) fullWidthItem {
                            Text("尚未讀取本機車庫。", Modifier.testTag("garage_unread"))
                            TextButton(onClick = actions.onReloadGarage) { Text("讀取車庫") }
                        }
                        items(state.pairedVehicles.filter { it.mac != primaryGarageVehicle(state)?.mac }, key = { "garage_${it.mac}" }) { vehicle ->
                            GarageVehicleTile(vehicle, imageRequests) { actions.onSavedInfo(vehicle) }
                        }
                        fullWidthItem { RideStatusCard(rideState, permissionPending, actions.onDisconnect, actions.onCancelPermission) }
                        if (rideState.stage != RideStage.IDLE || permissionPending) fullWidthItem {
                            OutlinedButton(onClick = actions.onRide, modifier = Modifier.fillMaxWidth()) { Text("開啟儀表連線畫面") }
                        }
                        fullWidthItem { Text("請勿同時使用另一個 App 控制同一台車。", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        if (state.busy) fullWidthItem {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (detail == null) TextButton(onClick = actions.onCancel) { Text("取消讀取") }
                            else Text("正在儲存車型偏好…")
                        }
                        if (state.cancelled && detail == null) fullWidthItem {
                            Text("已取消讀取；這不代表目錄沒有車型。", Modifier.testTag("vehicles_cancelled"))
                            TextButton(onClick = actions.onRefresh, enabled = !state.busy) { Text("重新讀取") }
                        }
                        if (state.errorCode != null) fullWidthItem {
                            Text(if (state.errorCode in setOf("AUTH_REQUIRED", "UNAUTHENTICATED")) "帳號需要重新登入。"
                                else "暫時無法更新車型資料，已保留可用內容。請稍後重試。", Modifier.testTag("vehicles_error"))
                            if (state.errorCode in setOf("AUTH_REQUIRED", "UNAUTHENTICATED"))
                                TextButton(onClick = actions.onAccount) { Text("前往帳號") }
                            else TextButton(onClick = if (detail == null) actions.onRefresh else actions.onSave, enabled = !state.busy) { Text("重試") }
                        }
                        if (state.garageMutationError && savedDetailVehicle(state) == null) fullWidthItem {
                            Text("車庫更新結果尚未確認，請重新讀取後再連線或管理。", Modifier.testTag("garage_change_error"))
                            TextButton(onClick = actions.onReloadGarage, enabled = !state.garageLoading && !state.garageMutationBusy) { Text("重新讀取車庫") }
                        }
                        if (state.storageError) fullWidthItem { Text("部分本機資料無法讀取或保存；請稍後重新更新。") }
                        if (detail != null) {
                            savedDetailVehicle(state)?.let { vehicle ->
                                fullWidthItem {
                                    GarageSavedDetails(vehicle, rideState.stage, permissionPending, actions,
                                        state.garageMutationBusy || state.garageLoading, state.garageMutationError)
                                }
                            }
                            fullWidthItem { Text(modelName(detail), style = MaterialTheme.typography.headlineSmall) }
                            fullWidthItem { VehicleImage(imageId(detail), Modifier.fillMaxWidth().height(220.dp), imageRequests) }
                            if (detail.displacement > 0) fullWidthItem { Text("排氣量：${detail.displacement} cc") }
                            fullWidthItem { Text("選擇年式", style = MaterialTheme.typography.titleMedium) }
                            if (detail.modelYearsCount == 0) fullWidthItem { Text("Honda 尚未提供此型號的年份資料。") }
                            else if (year == 0) fullWidthItem { Text("此車輛年式尚未確認；可選擇年式，不選則保留未確認。") }
                            items(detail.modelYearsList.distinct(), span = { GridItemSpan(maxLineSpan) }) { option ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { actions.onYear(option) }) {
                                    RadioButton(selected = year == option, enabled = !state.busy, onClick = { actions.onYear(option) })
                                    Text("$option 年式", Modifier.padding(top = 12.dp))
                                }
                            }
                            fullWidthItem { PairingEntry(detail.meter.generation, !state.busy && !state.garageMutationBusy && !state.garageMutationError && !state.garageLoading, actions.onPair) }
                            fullWidthItem {
                                Button(onClick = actions.onSave, enabled = !state.busy && canSaveVehiclePreference(detail, year),
                                    modifier = Modifier.fillMaxWidth().testTag("vehicle_save")) { Text("儲存車型偏好") }
                                Text("只保存車型與年式，不代表已與車輛配對或連線。", style = MaterialTheme.typography.bodySmall)
                                if (!canSaveVehiclePreference(detail, year)) Text("請先從上方選擇年式，再儲存車型偏好。")
                            }
                            if (state.selectionSaved && state.selection?.model?.modelLabel == detail.modelLabel && state.selection.year == year) fullWidthItem {
                                Text("已保存車型與年式偏好；配對仍需另行操作。", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            fullWidthItem {
                                OutlinedButton(onClick = { actions.onGuide(GuideKind.SETUP) }, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().testTag("vehicle_setup_guide")) { Text("車輛設定說明") }
                                OutlinedButton(onClick = { actions.onGuide(GuideKind.TUTORIAL) }, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().testTag("vehicle_tutorial_guide")) { Text("使用教學預覽") }
                            }
                        } else {
                            if (page == VehiclePage.CATALOG) {
                                fullWidthItem {
                                    Text("選擇車款，接著確認型號與年式。瀏覽目錄不會開始配對。")
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("只顯示居住地車型", Modifier.weight(1f).padding(top = 12.dp))
                                        Switch(checked = state.ownCountry, onCheckedChange = actions.onScope,
                                            enabled = !state.busy, modifier = Modifier.testTag("vehicles_own_country"))
                                    }
                                }
                                state.selection?.let { selected -> fullWidthItem {
                                    Card(onClick = { actions.onModel(selected.model) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text("已儲存的車型偏好（尚非配對狀態）")
                                            Text(modelName(selected.model))
                                            Text(if (selected.year == 0) "年份未提供" else "${selected.year} 年式")
                                        }
                                    }
                                } }
                            } else fullWidthItem { Text("選擇實際型號；返回可繼續瀏覽先前車款，不會自動更新目錄。") }
                            state.countryCode?.let { fullWidthItem {
                                Text("資料居住地：$it" + if (state.cached) "（上次保存，尚未確認最新資料）" else "", style = MaterialTheme.typography.bodySmall)
                            } }
                            if (page == VehiclePage.SERIES) items(state.models, key = { it.modelLabel }) { entry ->
                                VehicleCard(entry, null, !state.busy, imageRequests) { actions.onModel(entry) }
                            } else items(state.catalogs, key = { it.model.modelLabel }) { entry ->
                                VehicleCard(entry.model, if (entry.modelCount > 1) "${entry.modelCount} 個型號" else null, !state.busy, imageRequests) { actions.onCatalog(entry) }
                            }
                            if (vehicleListIsEmpty(state)) fullWidthItem {
                                Text(if (page == VehiclePage.SERIES) "此車系目前沒有可顯示的型號。" else "此範圍目前沒有車款，可更新或切換地區範圍。", Modifier.testTag("vehicles_empty"))
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
internal fun GarageVehicleCard(name: String, year: Int, state: PairedVehicleStore.State, inFocus: Boolean,
    onConnect: (() -> Unit)? = null, connectEnabled: Boolean = true, onDetails: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().testTag("garage_vehicle")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(if (year == 0) "年式尚未確認" else "$year 年式")
            Text(garageStateLabel(state))
            if (inFocus) Text("目前選定車輛", style = MaterialTheme.typography.labelMedium)
            Text("本機保存資料", style = MaterialTheme.typography.bodySmall)
            if (onDetails != null) OutlinedButton(onClick = onDetails, modifier = Modifier.testTag("garage_details")) { Text("車型與設定說明") }
            if (state !in setOf(PairedVehicleStore.State.PAIRED, PairedVehicleStore.State.READY))
                Text("此保存狀態尚不能在本 App 連線。可查看車型與設定說明；不會自動完成教學或韌體步驟。", style = MaterialTheme.typography.bodySmall)
            if (onConnect != null) Button(onClick = onConnect, enabled = connectEnabled,
                modifier = Modifier.testTag("garage_connect")) { Text("連線") }
        }
    }
}

@Composable
private fun VehicleCard(model: Model, subtitle: String?, enabled: Boolean = true, imageRequests: VehicleImageRequests, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleImage(imageId(model), Modifier.fillMaxWidth().height(130.dp), imageRequests)
            Text(modelName(model), style = MaterialTheme.typography.titleMedium)
            Text(subtitle ?: model.modelYearsList.joinToString("、").ifBlank { "年份未提供" })
        }
    }
}

/** A grid item can emit several roots; group full-width sections so their children never overlap. */
private fun LazyGridScope.fullWidthItem(content: @Composable ColumnScope.() -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
