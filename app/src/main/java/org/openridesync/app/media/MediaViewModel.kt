package org.openridesync.app.media

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import org.openridesync.app.runtime.CallContext
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.runtime.RideStage
import org.openridesync.app.runtime.RidePageState
import org.openridesync.diagnostics.DiagnosticJournal.*

internal data class MediaPageState(
    val media: MediaState = MediaState(),
    val signedIn: Boolean = false,
    val observing: Boolean = false,
    val result: DispatchResult? = null,
    val settingsUnavailable: Boolean = false,
    val rideConnected: Boolean = false,
    val rideSelection: MediaSessionChoice? = null,
    val rideSelectionFailed: Boolean = false,
    val rideMusic: RidePageState = RidePageState(),
    val rideMusicFailed: Boolean = false,
    val phoneStateGranted: Boolean = false,
    val calls: CallContext = CallContext(),
    val autoReturn: Boolean = false,
)

/** Page-scoped controllers; a stopped page never retains session callbacks or a prior identity. */
internal class MediaViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenRideSyncApplication
    private val auth = AuthSession.get()
    private val mutableState = MutableStateFlow(MediaPageState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var uid: String? = null
    private var revision = -1L
    private var backend: NativeMedia? = null
    private var observation: Job? = null

    init {
        viewModelScope.launch {
            auth.state.collect {
                if (backend != null && !sameIdentity()) stopBackend()
                if (it.status != AuthStatus.SIGNED_IN) mutableState.value = MediaPageState()
            }
        }
        viewModelScope.launch {
            app.ridePreferences.autoReturn.collect {
                if (visible && sameIdentity()) mutableState.value = withRide(mutableState.value)
            }
        }
        viewModelScope.launch {
            combine(app.ride.state, app.ride.media, app.ride.musicPage, app.ride.calls) { _, _, _, _ -> Unit }.collect {
                if (visible && sameIdentity()) mutableState.value = withRide(mutableState.value)
            }
        }
    }

    fun onStart() { visible = true; refresh() }
    fun onStop() { visible = false; stopBackend() }

    fun refresh() {
        if (!visible) return
        if (auth.state.value.status != AuthStatus.SIGNED_IN || auth.currentUser()?.uid == null) {
            stopBackend(); return
        }
        if (backend != null && !sameIdentity()) stopBackend()
        if (backend == null) {
            uid = auth.currentUser()?.uid
            revision = auth.revision
            val current = NativeMedia(app, app.journal)
            backend = current
            mutableState.value = withRide(MediaPageState(signedIn = true, observing = true))
            observation = viewModelScope.launch {
                current.state.collect { media ->
                    if (backend !== current) return@collect
                    if (!visible || !sameIdentity()) { stopBackend(); return@collect }
                    mutableState.value = withRide(mutableState.value.copy(media = media, signedIn = true, observing = true))
                }
            }
            current.start()
            // Only restore the exact user-selected token retained by this live ride.
            app.ride.selectedMediaToken()?.let { current.selectToken(it) }
        } else backend?.refresh()
        mutableState.value = withRide(mutableState.value)
    }

    private fun sameIdentity() = auth.state.value.status == AuthStatus.SIGNED_IN &&
        uid != null && uid == auth.currentUser()?.uid && revision == auth.revision

    private fun active(): NativeMedia? {
        if (!visible || !sameIdentity()) { stopBackend(); return null }
        return backend
    }

    fun select(id: String) {
        val current = active() ?: return
        if (usingRideMedia()) {
            val retained = app.ride.selectMediaChoice(id)
            mutableState.value = withRide(mutableState.value.copy(
                result = if (retained) null else DispatchResult.STALE, rideSelectionFailed = !retained))
            return
        }
        val selected = current.select(id)
        if (backend !== current || !sameIdentity()) { stopBackend(); return }
        val connected = app.ride.state.value.stage == RideStage.CONNECTED
        val retained = if (selected && connected) current.selectedToken()?.let { app.ride.selectMedia(it) } == true else false
        if (backend !== current || !sameIdentity()) { stopBackend(); return }
        mutableState.value = withRide(mutableState.value.copy(result = if (selected) null else DispatchResult.STALE,
            rideSelectionFailed = selected && connected && !retained))
    }
    fun play() = dispatch { it.play() }
    fun pause() = dispatch { it.pause() }
    fun previous() = dispatch { it.previous() }
    fun next() = dispatch { it.next() }
    fun homePrevious(id: String) = homeDispatch(id, { it.previous() }, { it.previous(id) })
    fun homeToggle(id: String) = homeDispatch(id, { it.toggle() }, { it.toggle(id) })
    fun homeNext(id: String) = homeDispatch(id, { it.next() }, { it.next(id) })
    fun setAutoReturn(enabled: Boolean) {
        if (active() != null) app.ridePreferences.setAutoReturn(enabled)
    }
    fun openRideMusic() {
        if (active() == null) return
        val opened = app.ride.openMusicPage()
        if (sameIdentity()) mutableState.value = withRide(mutableState.value.copy(rideMusicFailed = !opened))
    }
    private fun dispatch(command: (NativeMedia) -> DispatchResult) {
        val current = active() ?: return
        val result = if (usingRideMedia()) {
            mutableState.value.media.selectedId?.let { app.ride.dispatchMedia(it, command) } ?: DispatchResult.STALE
        } else command(current)
        if (backend === current && sameIdentity()) mutableState.value = mutableState.value.copy(result = result)
        else stopBackend()
    }
    private fun homeDispatch(id: String, rideCommand: (NativeMedia) -> DispatchResult,
        localCommand: (NativeMedia) -> DispatchResult) {
        val current = active() ?: return
        val result = when {
            !mutableState.value.observing || mutableState.value.media.homeControlTarget()?.id != id -> DispatchResult.STALE
            usingRideMedia() -> app.ride.dispatchMedia(id, rideCommand)
            else -> localCommand(current)
        }
        if (backend === current && sameIdentity()) mutableState.value = mutableState.value.copy(result = result)
        else stopBackend()
    }

    fun openAccessSettings() {
        if (active() == null) return
        val operation = app.journal?.newOperation()
        try {
            app.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            mutableState.value = mutableState.value.copy(settingsUnavailable = false)
            app.journal?.record(Component.MEDIA, operation, Event.MEDIA_ACCESS, Outcome.OBSERVED, ErrorCode.NONE)
        } catch (_: RuntimeException) {
            mutableState.value = mutableState.value.copy(settingsUnavailable = true)
            app.journal?.record(Component.MEDIA, operation, Event.MEDIA_ACCESS, Outcome.FAILED, ErrorCode.UNAVAILABLE)
        }
    }

    private fun stopBackend() {
        val current = backend
        backend = null
        observation?.cancel(); observation = null
        current?.close()
        uid = null; revision = -1L
        mutableState.value = MediaPageState(signedIn = auth.state.value.status == AuthStatus.SIGNED_IN)
    }

    private fun withRide(value: MediaPageState): MediaPageState {
        val connected = app.ride.state.value.stage == RideStage.CONNECTED
        val retained = app.ride.media.value
        val local = backend?.state?.value ?: value.media
        return value.copy(rideConnected = connected, media = mediaPageSelection(local, retained.takeIf { connected }),
            autoReturn = app.ridePreferences.autoReturn.value,
            phoneStateGranted = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
            calls = app.ride.calls.value,
            rideSelection = retained?.sessions?.singleOrNull { it.id == retained.selectedId },
            rideMusic = app.ride.musicPage.value,
            rideMusicFailed = connected && value.rideMusicFailed,
            rideSelectionFailed = connected && value.rideSelectionFailed)
    }

    private fun usingRideMedia() = backend?.state?.value?.access == MediaAccess.GRANTED && backend?.state?.value?.selectedId == null &&
        app.ride.state.value.stage == RideStage.CONNECTED && app.ride.media.value?.selectedId != null

    override fun onCleared() { stopBackend(); super.onCleared() }
}

/** Preserve an explicit page selection; otherwise expose the live ride's browser-owned selection. */
internal fun mediaPageSelection(local: MediaState, retained: MediaState?): MediaState =
    if (local.access == MediaAccess.GRANTED && local.selectedId == null && retained?.access == MediaAccess.GRANTED &&
        retained.selectedId != null) retained else local
