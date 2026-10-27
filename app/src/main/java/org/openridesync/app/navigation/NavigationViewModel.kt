package org.openridesync.app.navigation

import android.app.Application
import android.app.KeyguardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.runtime.RideCallContext
import org.openridesync.app.runtime.RideSpeechFailure
import org.openridesync.app.runtime.speakRideLines
import org.openridesync.diagnostics.DiagnosticJournal.*

internal data class NavigationPageState(
    val signedIn: Boolean = false,
    val authRevision: Long = -1,
    val observation: NavigationObservation = NavigationObservation(),
    val launch: NavigationLaunchResult? = null,
    val cancel: NavigationCancelResult? = null,
    val speech: String? = null,
    val speaking: Boolean = false,
)

/** Visible phone page owns its observation and speech; no saved route or background control. */
internal class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenRideSyncApplication
    private val auth = AuthSession.get()
    private val mutableState = MutableStateFlow(NavigationPageState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var uid: String? = null
    private var revision = -1L
    private var backend: NativeNavigation? = null
    private var observation: Job? = null
    private var speech: Job? = null
    private var speechVersion = 0L
    private var calls: RideCallContext? = null

    init {
        viewModelScope.launch {
            auth.state.collect {
                if (backend != null && !sameIdentity()) stopBackend()
                if (it.status != AuthStatus.SIGNED_IN) mutableState.value = NavigationPageState()
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
            uid = auth.currentUser()?.uid; revision = auth.revision
            val current = NativeNavigation(app, app.journal)
            backend = current
            calls = RideCallContext(app).also { it.start() }
            mutableState.value = NavigationPageState(signedIn = true, authRevision = revision)
            observation = viewModelScope.launch {
                current.state.collect {
                    if (backend !== current) return@collect
                    if (!visible || !sameIdentity()) { stopBackend(); return@collect }
                    mutableState.value = mutableState.value.copy(observation = it)
                }
            }
            current.start()
        } else { calls?.refresh(); backend?.refresh() }
    }

    fun launch(query: String) {
        if (!visible || !sameIdentity()) { stopBackend(); return }
        val operation = app.journal?.newOperation()
        app.journal?.record(Component.NAVIGATION, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
        val intent = mapsDestinationIntent(query)
        val result = when {
            app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false -> NavigationLaunchResult.LOCKED
            intent == null -> NavigationLaunchResult.INVALID
            intent.resolveActivity(app.packageManager) == null -> NavigationLaunchResult.UNAVAILABLE
            else -> try { app.startActivity(intent); NavigationLaunchResult.DISPATCHED }
                catch (_: RuntimeException) { NavigationLaunchResult.UNAVAILABLE }
        }
        val error = when (result) {
            NavigationLaunchResult.DISPATCHED -> ErrorCode.NONE
            NavigationLaunchResult.INVALID, NavigationLaunchResult.LOCKED -> ErrorCode.FAILED_PRECONDITION
            NavigationLaunchResult.UNAVAILABLE -> ErrorCode.UNAVAILABLE
        }
        app.journal?.record(Component.NAVIGATION, operation, Event.REQUEST,
            if (result == NavigationLaunchResult.DISPATCHED) Outcome.OBSERVED else Outcome.FAILED, error)
        mutableState.value = mutableState.value.copy(launch = result)
    }

    fun cancel(id: String) {
        if (!visible || !sameIdentity()) { stopBackend(); return }
        val current = backend ?: return
        stopSpeech()
        mutableState.value = mutableState.value.copy(cancel = current.cancel(id))
    }

    fun repeat(id: String) {
        if (!visible || !sameIdentity()) { stopBackend(); return }
        val current = backend ?: return
        stopSpeech()
        val instruction = current.currentInstruction(id) ?: run {
            mutableState.value = mutableState.value.copy(speech = "導航通知已變更，請重新查看。"); return
        }
        calls?.refresh()
        fun valid() = visible && sameIdentity() && backend === current && current.isCurrent(id) &&
            calls?.state?.value?.musicPopupBlocked == false
        if (!valid()) {
            mutableState.value = mutableState.value.copy(speech = "通話狀態尚未就緒或正在通話，暫不播報。"); return
        }
        val lines = listOfNotNull(instruction.nextStep, instruction.instruction)
        if (lines.any { it.length > android.speech.tts.TextToSpeech.getMaxSpeechInputLength() }) {
            mutableState.value = mutableState.value.copy(speech = "這筆指示超過語音引擎長度限制，請在 Maps 查看。"); return
        }
        val version = speechVersion
        speech = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(speaking = true, speech = "正在播報目前指示…")
            try {
                speakRideLines(app, lines, app.journal, app.journal?.newOperation() ?: java.util.UUID.randomUUID().toString(), ::valid)
                mutableState.value = mutableState.value.copy(speech = "語音引擎已完成播報。")
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: RideSpeechFailure) { mutableState.value = mutableState.value.copy(speech = "播報未完成；請檢查通話、勿擾、音量與語音引擎。") }
            finally { if (version == speechVersion) mutableState.value = mutableState.value.copy(speaking = false) }
        }
    }

    fun stopSpeech() { speechVersion++; speech?.cancel(); speech = null; mutableState.value = mutableState.value.copy(speaking = false, speech = null) }
    private fun sameIdentity() = uid != null && auth.state.value.status == AuthStatus.SIGNED_IN &&
        auth.currentUser()?.uid == uid && auth.revision == revision
    private fun stopBackend() {
        stopSpeech()
        val current = backend; backend = null
        observation?.cancel(); observation = null
        current?.close(); calls?.close(); calls = null
        uid = null; revision = -1L
        mutableState.value = NavigationPageState(signedIn = auth.state.value.status == AuthStatus.SIGNED_IN)
    }
    override fun onCleared() { stopBackend(); super.onCleared() }
}
