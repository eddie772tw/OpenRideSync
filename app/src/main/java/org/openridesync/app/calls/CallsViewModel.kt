package org.openridesync.app.calls

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus

internal data class CallsPageState(
    val signedIn: Boolean = false,
    val observing: Boolean = false,
    val inbox: CallInboxState = CallInboxState(),
    val result: CallDispatchResult? = null,
)

/** Visible-page owner. Caller information and PendingIntents never enter saved state. */
internal class CallsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenRideSyncApplication
    private val auth = AuthSession.get()
    private val mutableState = MutableStateFlow(CallsPageState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var uid: String? = null
    private var revision = -1L
    private var backend: NativeCalls? = null
    private var observation: Job? = null

    init {
        viewModelScope.launch {
            auth.state.collect {
                if (backend != null && !sameIdentity()) stopBackend()
                if (it.status != AuthStatus.SIGNED_IN) mutableState.value = CallsPageState()
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
            val current = NativeCalls(app, app.journal)
            backend = current
            mutableState.value = CallsPageState(signedIn = true, observing = true)
            observation = viewModelScope.launch {
                current.state.collect {
                    if (backend !== current) return@collect
                    if (!visible || !sameIdentity()) { stopBackend(); return@collect }
                    mutableState.value = mutableState.value.copy(inbox = it)
                }
            }
            current.start()
        } else backend?.refresh()
    }

    fun dispatch(id: String, action: CallAction) {
        if (!visible || !sameIdentity()) { stopBackend(); return }
        val current = backend ?: return
        val result = current.dispatch(id, action)
        if (backend === current && sameIdentity()) mutableState.value = mutableState.value.copy(result = result)
        else stopBackend()
    }

    private fun sameIdentity() = uid != null && auth.state.value.status == AuthStatus.SIGNED_IN &&
        auth.currentUser()?.uid == uid && auth.revision == revision

    private fun stopBackend() {
        val current = backend
        backend = null
        observation?.cancel(); observation = null
        current?.close()
        uid = null; revision = -1L
        mutableState.value = CallsPageState(signedIn = auth.state.value.status == AuthStatus.SIGNED_IN)
    }

    override fun onCleared() { stopBackend(); super.onCleared() }
}
