package org.openridesync.app.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FavoriteMusicScreen(onBack: () -> Unit, model: FavoriteMusicViewModel = viewModel(), onAccount: () -> Unit = {}) {
    val state by model.state.collectAsState()
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
    BackHandler { model.onStop(); onBack() }
    FavoriteMusicContent(state, { model.onStop(); onBack() }, model::refresh, model::select, onAccount)
}

@Composable
internal fun FavoriteMusicContent(state: FavoriteMusicState, onBack: () -> Unit, onRefresh: () -> Unit,
    onSelect: (String?) -> Unit, onAccount: () -> Unit = {}) {
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(20.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("常用播放器", style = MaterialTheme.typography.headlineMedium) }
            item { OutlinedButton(onClick = onBack) { Text("返回") } }
            if (!state.signedIn) item {
                Text("帳號狀態已變更，請先完成登入。")
                Button(onClick = onAccount, modifier = Modifier.testTag("favorite-account")) { Text("前往帳號") }
            }
            else {
                item { Text("選取常用播放器；再次點選可取消。此選擇儲存在這台手機，選取時不會立即播放音樂。") }
                item { Text("下次車輛連線成功時，會嘗試開啟此播放器並繼續播放。播放器須提供可辨識的媒體工作階段與播放控制；結果以播放器回報為準。", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedButton(onClick = onRefresh, enabled = state.observing && !state.loading) { Text("重新整理播放器") } }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.error || state.staleSelection) item {
                    Text(if (state.staleSelection) "播放器已移除或無法使用，原選擇未變更，請重新整理。" else "目前無法讀取播放器，請重新整理。")
                }
                item {
                    Row(Modifier.fillMaxWidth().selectable(state.selected == null, enabled = state.observing && !state.loading,
                        role = Role.RadioButton, onClick = { onSelect(null) }).padding(12.dp).testTag("favorite-none"),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.selected == null, onClick = null)
                        Text("不指定常用播放器", Modifier.padding(start = 12.dp))
                    }
                }
                if (!state.loading && !state.error && !state.staleSelection && state.apps.isEmpty()) item {
                    Text("沒有找到可選的播放器。安裝 RoadSync 支援的播放器後，請重新整理。")
                }
                if (!state.loading && !state.error && !state.staleSelection && state.selected != null && state.apps.none { it.packageName == state.selected }) item {
                    Text("原先選取的播放器目前不可用。你可以保留設定、改選播放器或取消指定。")
                }
                items(state.apps, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().selectable(state.selected == app.packageName, enabled = state.observing && !state.loading,
                        role = Role.RadioButton, onClick = { onSelect(app.packageName) }).padding(12.dp).testTag("favorite-${app.packageName}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = state.selected == app.packageName, onClick = null)
                        FavoriteMusicIcon(app.packageName)
                        Text(app.label, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteMusicIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName, context) {
        value = withContext(Dispatchers.IO) {
            try { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
            catch (_: RuntimeException) { null }
        }
    }
    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
}
