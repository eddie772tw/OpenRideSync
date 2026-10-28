package org.openridesync.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.app.honda.HondaSessionRead.Companion.errorCode
import org.openridesync.app.honda.HondaSessionRead.Companion.diagnosticError
import org.openridesync.honda.wire.HondaWire.GetCountryOfResidenceRequest

data class HondaAccountDetails(val userId: String, val countryCode: String)

data class AccountState(
    val busy: Boolean = false,
    val details: HondaAccountDetails? = null,
    val countryCode: String? = null,
    val errorCode: String? = null,
    val countryErrorCode: String? = null,
    val cancelled: Boolean = false,
) {
    val registrationRequired: Boolean get() = errorCode == "NOT_FOUND"
    val requiresLogin: Boolean get() = registrationRequired || errorCode in LOGIN_ERRORS || countryErrorCode in LOGIN_ERRORS
    private companion object { val LOGIN_ERRORS = setOf("UNAUTHENTICATED", "AUTH_REQUIRED") }
}

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    val session = AuthSession.get()
    private val journal = (application as OpenRideSyncApplication).journal
    private val mutableState = MutableStateFlow(AccountState())
    val state = mutableState.asStateFlow()
    private var readJob: Job? = null
    private var generation = 0L
    private var observedRevision = session.revision

    init {
        viewModelScope.launch {
            session.state.collect {
                if (it.status != AuthStatus.SIGNED_IN || it.revision != observedRevision) clearRead()
                observedRevision = it.revision
                if (it.status == AuthStatus.SIGNED_IN && mutableState.value == AccountState()) readAccount()
            }
        }
    }

    private fun clearRead() {
        generation++
        readJob?.cancel()
        readJob = null
        mutableState.value = AccountState()
    }

    fun prepareLogin() = clearRead()
    fun signOut() { clearRead(); session.signOut() }
    fun cancelRead() { clearRead(); mutableState.value = AccountState(cancelled = true) }

    fun readAccount() {
        if (mutableState.value.busy || session.state.value.status != AuthStatus.SIGNED_IN) return
        val uid = session.currentUser()?.uid ?: return
        val revision = session.revision
        val requestGeneration = ++generation
        mutableState.value = AccountState(busy = true)
        readJob = viewModelScope.launch {
            val operation = journal?.newOperation() ?: java.util.UUID.randomUUID().toString()
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    HondaSessionRead(session, journal, operation, uid, revision).use { read ->
                        val user = read.read(Event.HONDA_GET_USER) { client, key, token -> client.getUser(key, token) }
                        check(user.userId.isNotBlank())
                        val details = HondaAccountDetails(user.userId, user.countryCode)
                        try {
                            val value = read.read(Event.HONDA_GET_COUNTRY) { client, key, token ->
                                client.getCountryOfResidence(GetCountryOfResidenceRequest.getDefaultInstance(), key, token)
                            }
                            AccountState(details = details, countryCode = value.code)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            AccountState(details = details, countryErrorCode = errorCode(error))
                        }
                    }
                }
                ensureActive()
                if (generation == requestGeneration && revision == session.revision && session.currentUser()?.uid == uid)
                    mutableState.value = result
            } catch (_: CancellationException) {
                journal?.record(Component.API, operation, Event.STOP, Outcome.CANCELLED, ErrorCode.CANCELLED)
                if (generation == requestGeneration) mutableState.value = AccountState(cancelled = true)
            } catch (error: Exception) {
                journal?.record(Component.UI, operation, Event.STATE_CHANGE, Outcome.FAILED, diagnosticError(errorCode(error)))
                if (generation == requestGeneration && revision == session.revision && session.currentUser()?.uid == uid) {
                    val status = errorCode(error)
                    mutableState.value = AccountState(errorCode = status)
                }
            }
        }
    }

}
