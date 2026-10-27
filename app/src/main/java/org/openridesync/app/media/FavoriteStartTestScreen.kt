package org.openridesync.app.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.openridesync.app.BuildConfig
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.runtime.RideCallContext

/** Development check of the same real-player path used after a vehicle connects. */
@Composable
internal fun FavoriteStartTestScreen(onBack: () -> Unit) {
    if (!BuildConfig.DEBUG) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(20.dp)) {
                Text("此測試僅供開發版本使用。")
                OutlinedButton(onClick = onBack) { Text("返回") }
            }
        }
        return
    }
    val app = LocalContext.current.applicationContext as OpenRideSyncApplication
    val auth = remember { AuthSession.get() }
    val authState by auth.state.collectAsStateWithLifecycle()
    val signedIn = authState.status == AuthStatus.SIGNED_IN && auth.currentUser()?.uid != null
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(FavoriteStartStatus.NONE) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    BackHandler { job?.cancel(); onBack() }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("常用播放器啟動測試", style = MaterialTheme.typography.headlineMedium)
            Text("這是實際媒體操作。按下測試後會嘗試開啟已指定的播放器並開始播放，可能發出聲音；不需連接車輛。")
            if (!signedIn) Text("請先登入，才能測試常用播放器。")
            Button(onClick = {
                val uid = auth.currentUser()?.uid ?: return@Button
                val revision = auth.revision
                fun sameIdentity() = auth.state.value.status == AuthStatus.SIGNED_IN &&
                    auth.currentUser()?.uid == uid && auth.revision == revision
                status = FavoriteStartStatus.STARTING
                job = scope.launch {
                    var selected: NativeMedia? = null
                    var calls: RideCallContext? = null
                    try {
                        val callObserver = RideCallContext(app).also { calls = it; it.start() }
                        withTimeoutOrNull(2_000) { callObserver.state.first { !it.notificationUnknown || !it.cellularIdle || it.notificationActive || it.notificationIncoming } }
                        val result = FavoriteMusicStarter(app, app.journal).start(
                            canContinue = ::sameIdentity,
                            hasSelection = { false },
                            attach = { source -> selected = source; true },
                            detach = { selected?.close(); selected = null },
                            callBlocked = { calls?.state?.value?.musicPopupBlocked != false },
                        )
                        status = if (result == FavoriteStartStatus.NONE && !sameIdentity()) FavoriteStartStatus.AUTH_REQUIRED else result
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: RuntimeException) { status = FavoriteStartStatus.APP_UNAVAILABLE }
                    finally { selected?.close(); calls?.close() }
                }
            }, enabled = signedIn && job?.isActive != true,
                modifier = Modifier.testTag("favorite-start-test")) { Text("測試啟動並播放") }
            Text(favoriteStartMessage(status), modifier = Modifier.testTag("favorite-start-status"))
            OutlinedButton(onClick = { job?.cancel(); onBack() }) { Text("返回") }
        }
    }
}
