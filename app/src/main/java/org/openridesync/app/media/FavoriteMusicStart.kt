package org.openridesync.app.media

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.openridesync.diagnostics.DiagnosticJournal

internal enum class FavoriteStartStatus {
    NONE, STARTING, START_IN_PROGRESS, INTERRUPTED, AUTH_REQUIRED, NO_SELECTION, SELECTION_CHANGED, APP_UNAVAILABLE,
    OPEN_APP_REQUIRED, ACCESS_REQUIRED, SESSION_UNAVAILABLE, MULTIPLE_SESSIONS,
    CALL_ACTIVE, CALL_UNCONFIRMED, PLAY_UNAVAILABLE, PLAY_REQUESTED, PLAYING, USER_SELECTED
}

internal fun favoriteStartMessage(status: FavoriteStartStatus): String = when (status) {
    FavoriteStartStatus.NONE -> "尚未測試常用播放器。"
    FavoriteStartStatus.STARTING -> "正在啟動常用播放器並尋找它的媒體工作階段…"
    FavoriteStartStatus.START_IN_PROGRESS -> "另一個常用播放器啟動仍在進行中，請等它完成後再試。"
    FavoriteStartStatus.INTERRUPTED -> "播放器啟動已中止。連線恢復後可自行重試。"
    FavoriteStartStatus.AUTH_REQUIRED -> "登入狀態已變更，請重新登入後再測試。"
    FavoriteStartStatus.NO_SELECTION -> "尚未指定常用播放器。請先在音樂控制頁選取。"
    FavoriteStartStatus.SELECTION_CHANGED -> "常用播放器設定已變更；這次啟動已停止。"
    FavoriteStartStatus.APP_UNAVAILABLE -> "常用播放器未安裝、已停用或無法啟動。請重新選取。"
    FavoriteStartStatus.OPEN_APP_REQUIRED -> "系統未允許在背景開啟播放器。請在前景重新測試或自行開啟播放器。"
    FavoriteStartStatus.ACCESS_REQUIRED -> "需要在系統設定開啟 OpenRideSync 的通知存取，才能找到播放器工作階段。"
    FavoriteStartStatus.SESSION_UNAVAILABLE -> "未找到常用播放器的可用工作階段；請在播放器內開始播放後再試。"
    FavoriteStartStatus.MULTIPLE_SESSIONS -> "此播放器同時提供多個工作階段，請在音樂控制頁自行選取。"
    FavoriteStartStatus.CALL_ACTIVE -> "偵測到通話或來電，未啟動播放器。"
    FavoriteStartStatus.CALL_UNCONFIRMED -> "尚無法確認通話狀態，未啟動播放器。若目前沒有通話，可到系統設定重新開啟 OpenRideSync 的通知存取後再試。"
    FavoriteStartStatus.PLAY_UNAVAILABLE -> "播放器目前未提供可用的播放控制。"
    FavoriteStartStatus.PLAY_REQUESTED -> "已送出播放請求；是否出聲以播放器後續回報為準。"
    FavoriteStartStatus.PLAYING -> "播放器工作階段已回報正在播放。"
    FavoriteStartStatus.USER_SELECTED -> "已保留你在音樂控制頁選取的工作階段。"
}

/** One saved player, one launch, one bounded session lookup, and at most one play request. */
internal class FavoriteMusicStarter(context: Context, private val journal: DiagnosticJournal?) {
    private val app = context.applicationContext
    private companion object { val starts = FavoriteStartGate() }

    suspend fun start(
        canContinue: () -> Boolean,
        hasSelection: () -> Boolean,
        attach: (NativeMedia) -> Boolean,
        detach: () -> Unit = {},
        callBlocked: () -> Boolean = { false },
    ): FavoriteStartStatus {
        val ticket = starts.acquire() ?: return FavoriteStartStatus.START_IN_PROGRESS
        try { return startOwned(canContinue, hasSelection, attach, detach, callBlocked) }
        finally { starts.release(ticket) }
    }

    private suspend fun startOwned(
        canContinue: () -> Boolean,
        hasSelection: () -> Boolean,
        attach: (NativeMedia) -> Boolean,
        detach: () -> Unit,
        callBlocked: () -> Boolean,
    ): FavoriteStartStatus {
        val preferences = app.getSharedPreferences("music", Context.MODE_PRIVATE)
        val chosen = preferences.getString("favorite", null)
        if (chosen.isNullOrEmpty()) return FavoriteStartStatus.NO_SELECTION
        fun choiceChanged() = preferences.getString("favorite", null) != chosen
        fun blockedStatus() = if (MediaAccessService.callSummary.value.unknown)
            FavoriteStartStatus.CALL_UNCONFIRMED else FavoriteStartStatus.CALL_ACTIVE
        if (chosen !in preferredMusicPackages ||
            !withContext(Dispatchers.IO) { installedFavoriteMusic(app).any { it.packageName == chosen } })
            return FavoriteStartStatus.APP_UNAVAILABLE
        if (!canContinue()) return FavoriteStartStatus.NONE
        if (hasSelection()) return FavoriteStartStatus.USER_SELECTED
        if (choiceChanged()) return FavoriteStartStatus.SELECTION_CHANGED
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        if (process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
            return FavoriteStartStatus.OPEN_APP_REQUIRED
        val intent = app.packageManager.getLeanbackLaunchIntentForPackage(chosen)
            ?: app.packageManager.getLaunchIntentForPackage(chosen)
            ?: return FavoriteStartStatus.APP_UNAVAILABLE
        val observer = NativeMedia(app, journal)
        var transferred = false
        try {
            observer.start()
            if (observer.state.value.access == MediaAccess.REQUIRED) return FavoriteStartStatus.ACCESS_REQUIRED
            if (observer.state.value.access != MediaAccess.GRANTED) return FavoriteStartStatus.SESSION_UNAVAILABLE
            if (!canContinue()) return FavoriteStartStatus.NONE
            if (choiceChanged()) return FavoriteStartStatus.SELECTION_CHANGED
            if (callBlocked()) return blockedStatus()
            try { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: RuntimeException) { return FavoriteStartStatus.OPEN_APP_REQUIRED }
            observer.connectBrowser(chosen)
            val acquisition = FavoriteSessionAcquisition()
            repeat(11) { index ->
                if (!canContinue()) return FavoriteStartStatus.NONE
                if (hasSelection()) return FavoriteStartStatus.USER_SELECTED
                if (choiceChanged()) return FavoriteStartStatus.SELECTION_CHANGED
                if (callBlocked()) return blockedStatus()
                observer.refresh()
                var snapshot = observer.state.value
                if (snapshot.access == MediaAccess.REQUIRED) return FavoriteStartStatus.ACCESS_REQUIRED
                if (snapshot.access != MediaAccess.GRANTED) return FavoriteStartStatus.SESSION_UNAVAILABLE
                fun decision() = acquisition.inspect(
                    snapshot.sessions.filter { it.packageName == chosen }.map { it.id },
                    observer.browserStatus.value in setOf(MediaBrowserStatus.CONNECTING, MediaBrowserStatus.SUSPENDED),
                    deadlineReached = index == 10,
                )
                var next = decision()
                if (next == FavoriteSessionAcquisition.Decision.StopBrowser) {
                    observer.stopBrowser(MediaBrowserStatus.FAILED)
                    snapshot = observer.state.value
                    if (snapshot.access == MediaAccess.REQUIRED) return FavoriteStartStatus.ACCESS_REQUIRED
                    if (snapshot.access != MediaAccess.GRANTED) return FavoriteStartStatus.SESSION_UNAVAILABLE
                    next = decision()
                }
                val selected = when (next) {
                    FavoriteSessionAcquisition.Decision.Wait -> { delay(1_000); return@repeat }
                    FavoriteSessionAcquisition.Decision.Ambiguous -> return FavoriteStartStatus.MULTIPLE_SESSIONS
                    FavoriteSessionAcquisition.Decision.Unavailable,
                    FavoriteSessionAcquisition.Decision.StopBrowser -> return FavoriteStartStatus.SESSION_UNAVAILABLE
                    is FavoriteSessionAcquisition.Decision.Select -> next.id
                }
                // Select the observed ID, not whichever token now happens to share the package.
                if (!observer.select(selected)) return FavoriteStartStatus.SESSION_UNAVAILABLE
                val ready = observer.state.value
                if (ready.sessions.count { it.packageName == chosen } > 1)
                    return FavoriteStartStatus.MULTIPLE_SESSIONS
                if (ready.playback != MediaPlayback.PLAYING && !ready.canPlay) {
                    if (index < 10) delay(1_000)
                    return@repeat
                }
                if (observer.selectedToken() == null || observer.state.value.selectedId != selected)
                    return FavoriteStartStatus.SESSION_UNAVAILABLE
                // selectedToken refreshes sources too; do not miss ambiguity introduced by that refresh.
                if (observer.state.value.sessions.count { it.packageName == chosen } > 1)
                    return FavoriteStartStatus.MULTIPLE_SESSIONS
                if (!canContinue()) return FavoriteStartStatus.NONE
                if (hasSelection()) return FavoriteStartStatus.USER_SELECTED
                if (choiceChanged()) return FavoriteStartStatus.SELECTION_CHANGED
                if (callBlocked()) return blockedStatus()
                if (!attach(observer)) return if (hasSelection()) FavoriteStartStatus.USER_SELECTED
                    else FavoriteStartStatus.SESSION_UNAVAILABLE
                transferred = true // The ride now owns callbacks and the browser binding together.
                if (!canContinue()) { detach(); return FavoriteStartStatus.NONE }
                if (choiceChanged()) { detach(); return FavoriteStartStatus.SELECTION_CHANGED }
                if (callBlocked()) { detach(); return blockedStatus() }
                return when {
                    observer.state.value.playback == MediaPlayback.PLAYING -> FavoriteStartStatus.PLAYING
                    else -> when (observer.play()) {
                        DispatchResult.DISPATCHED -> FavoriteStartStatus.PLAY_REQUESTED
                        DispatchResult.ACCESS_REQUIRED -> FavoriteStartStatus.ACCESS_REQUIRED
                        else -> FavoriteStartStatus.PLAY_UNAVAILABLE
                    }
                }
            }
            return if (acquisition.foundSession) FavoriteStartStatus.PLAY_UNAVAILABLE else FavoriteStartStatus.SESSION_UNAVAILABLE
        } finally { if (!transferred) observer.close() }
    }
}
