package org.openridesync.app.honda

import android.os.Build
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.honda.HondaReadClient
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One operation, one identity and at most one forced token refresh across read RPCs.
 * Construct/use inside runInterruptible(Dispatchers.IO); never retain this in UI or saved state.
 */
class HondaSessionRead(
    private val session: AuthSession,
    private val journal: DiagnosticJournal?,
    val operationId: String = journal?.newOperation() ?: UUID.randomUUID().toString(),
    private val expectedUid: String? = session.currentUser()?.uid,
    private val expectedRevision: Long = session.revision,
) : AutoCloseable {
    init { check(Looper.myLooper() != Looper.getMainLooper()) }
    private val apiKey by lazy { checkNotNull(session.hondaApiKey()) }
    private val clientDelegate = lazy { HondaReadClient(Build.VERSION.SDK_INT) }
    private val client by clientDelegate
    private var idToken: String? = null
    private var refreshed = false
    private var closed = false

    private fun checkIdentity() {
        if (closed || Thread.currentThread().isInterrupted || expectedUid == null ||
            session.revision != expectedRevision || session.currentUser()?.uid != expectedUid ||
            session.state.value.status != AuthStatus.SIGNED_IN) throw CancellationException()
    }

    private fun token(refresh: Boolean): String {
        val event = if (refresh) Event.TOKEN_REFRESH else Event.TOKEN_READ
        journal?.record(Component.AUTH, operationId, event, Outcome.STARTED, ErrorCode.NONE)
        try {
            checkIdentity()
            val value = Tasks.await(session.getIdToken(refresh), 20, TimeUnit.SECONDS).token
            checkIdentity()
            check(!value.isNullOrEmpty())
            journal?.record(Component.AUTH, operationId, event, Outcome.SUCCEEDED, ErrorCode.NONE)
            return value
        } catch (error: Exception) {
            recordFailure(Component.AUTH, event, error)
            throw error
        }
    }

    fun <T> read(event: Event, action: (HondaReadClient, String, String) -> T): T {
        checkIdentity()
        if (idToken == null) idToken = token(false)
        fun attempt(): T {
            journal?.record(Component.API, operationId, event, Outcome.STARTED, ErrorCode.NONE)
            try {
                checkIdentity()
                val result = action(client, apiKey, checkNotNull(idToken))
                checkIdentity()
                if (event == Event.HONDA_GET_COUNTRY && result is org.openridesync.honda.wire.HondaWire.Country)
                    session.rememberCountry(result.code, expectedUid, expectedRevision)
                journal?.record(Component.API, operationId, event, Outcome.SUCCEEDED, ErrorCode.NONE)
                return result
            } catch (error: Exception) {
                recordFailure(Component.API, event, error)
                throw error
            }
        }
        return try { attempt() }
        catch (error: StatusRuntimeException) {
            if (error.status.code != Status.Code.UNAUTHENTICATED || refreshed) throw error
            refreshed = true
            idToken = token(true)
            attempt()
        }
    }

    /** Mutations never use the read retry path: an ambiguous completion retains local pending data. */
    fun <T> write(event: Event, action: (HondaReadClient, String, String) -> T): T {
        checkIdentity()
        if (idToken == null) idToken = token(false)
        journal?.record(Component.API, operationId, event, Outcome.STARTED, ErrorCode.NONE)
        try {
            checkIdentity()
            val result = action(client, apiKey, checkNotNull(idToken))
            checkIdentity()
            journal?.record(Component.API, operationId, event, Outcome.SUCCEEDED, ErrorCode.NONE)
            return result
        } catch (error: Exception) {
            recordFailure(Component.API, event, error)
            throw error
        }
    }

    private fun recordFailure(component: Component, event: Event, error: Exception) {
        journal?.record(component, operationId, event,
            if (error is CancellationException) Outcome.CANCELLED else Outcome.FAILED,
            diagnosticError(errorCode(error)))
    }

    override fun close() {
        closed = true
        idToken = null
        if (clientDelegate.isInitialized()) client.close()
    }

    companion object {
        /** Never return exception descriptions, metadata, provider text or account values. */
        fun errorCode(error: Throwable): String {
            val source = if (error is ExecutionException) error.cause ?: error else error
            return when (source) {
                is StatusRuntimeException -> source.status.code.name
                is TimeoutException, is java.net.SocketTimeoutException -> "TIMEOUT"
                is FirebaseAuthException -> "AUTH_REQUIRED"
                is FirebaseNetworkException, is java.io.IOException -> "NETWORK"
                is CancellationException, is InterruptedException -> "CANCELLED"
                else -> "UNAVAILABLE"
            }
        }

        fun diagnosticError(code: String): ErrorCode = when (code) {
            "UNAUTHENTICATED", "AUTH_REQUIRED" -> ErrorCode.UNAUTHORIZED
            "TIMEOUT", "DEADLINE_EXCEEDED" -> ErrorCode.TIMEOUT
            "NETWORK", "UNAVAILABLE" -> ErrorCode.NETWORK
            "CANCELLED" -> ErrorCode.CANCELLED
            "OK" -> ErrorCode.NONE
            else -> runCatching { ErrorCode.valueOf(code) }.getOrDefault(ErrorCode.UNKNOWN)
        }
    }
}
