package org.openridesync.app.auth

import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWebException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*

enum class AuthStatus { UNAVAILABLE, SIGNED_OUT, SIGNING_IN, SIGNED_IN, ERROR }
enum class AuthFailureClass {
    FIREBASE_AUTH_INVALID_CREDENTIALS_EXCEPTION, FIREBASE_AUTH_INVALID_USER_EXCEPTION,
    FIREBASE_AUTH_USER_COLLISION_EXCEPTION, FIREBASE_AUTH_WEB_EXCEPTION, FIREBASE_AUTH_EXCEPTION,
    FIREBASE_NETWORK_EXCEPTION, FIREBASE_TOO_MANY_REQUESTS_EXCEPTION, FIREBASE_EXCEPTION,
    ILLEGAL_ARGUMENT_EXCEPTION, UNKNOWN
}
enum class FirebaseFailureCode {
    NOT_PROVIDED, UNRECOGNIZED, ERROR_INTERNAL_ERROR, ERROR_INVALID_API_KEY, ERROR_APP_NOT_AUTHORIZED,
    ERROR_OPERATION_NOT_ALLOWED, ERROR_INVALID_CREDENTIAL, ERROR_INVALID_CUSTOM_TOKEN,
    ERROR_CUSTOM_TOKEN_MISMATCH, ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL, ERROR_USER_DISABLED,
    ERROR_USER_NOT_FOUND, ERROR_INVALID_USER_TOKEN, ERROR_USER_TOKEN_EXPIRED, ERROR_NETWORK_REQUEST_FAILED,
    ERROR_TOO_MANY_REQUESTS, ERROR_WEB_INTERNAL_ERROR, ERROR_INVALID_CERT_HASH, ERROR_MISSING_CLIENT_IDENTIFIER
}
enum class AuthProviderRestriction { NONE, API_KEY_ANDROID_APP_BLOCKED, API_KEY_SERVICE_BLOCKED, API_KEY_HTTP_REFERRER_BLOCKED, API_KEY_IP_ADDRESS_BLOCKED, API_KEY_INVALID, API_KEY_IOS_APP_BLOCKED }
enum class AuthNetworkReason { DNS, CONNECT, TIMEOUT, TLS, PERMISSION, UNKNOWN }
data class AuthFailure(val category: AuthFailureClass, val firebaseCode: FirebaseFailureCode, val restriction: AuthProviderRestriction, val networkReason: AuthNetworkReason? = null) {
    val summary: String get() = "${category.name}/${firebaseCode.name}/${restriction.name}" + (networkReason?.let { "/${it.name}" } ?: "")
    val journalCode: ErrorCode get() = when {
        networkReason == AuthNetworkReason.TIMEOUT -> ErrorCode.TIMEOUT
        networkReason != null -> ErrorCode.NETWORK
        category == AuthFailureClass.FIREBASE_AUTH_EXCEPTION || category.name.startsWith("FIREBASE_AUTH_") -> ErrorCode.UNAUTHORIZED
        else -> ErrorCode.UNKNOWN
    }
}
/** Revision keeps a same-status account replacement observable without exposing its UID. */
data class AuthState(val status: AuthStatus, val code: String = "NONE", val failure: AuthFailure? = null,
    val revision: Long = 0)

/** A successful residence read for this authentication revision, kept in RAM only. */
internal data class HondaCountrySnapshot(val code: String? = null, val revision: Long = 0,
    val accountRevision: Long = 0)

/** SDK messages are inspected locally and discarded; only closed enum values escape. */
internal object AuthErrors {
    fun map(error: Throwable?): AuthFailure {
        var category = AuthFailureClass.UNKNOWN
        var code = FirebaseFailureCode.NOT_PROVIDED
        var restriction = AuthProviderRestriction.NONE
        var typedNetwork: AuthNetworkReason? = null
        var messageNetwork: AuthNetworkReason? = null
        var networkFailure = false
        var current = error
        repeat(4) {
            val value = current ?: return@repeat
            if (value is FirebaseNetworkException) networkFailure = true
            if (typedNetwork == null) typedNetwork = when (value) {
                is java.net.UnknownHostException -> AuthNetworkReason.DNS
                is java.net.SocketTimeoutException -> AuthNetworkReason.TIMEOUT
                is javax.net.ssl.SSLException, is java.security.cert.CertificateException -> AuthNetworkReason.TLS
                is java.net.ConnectException, is java.net.NoRouteToHostException -> AuthNetworkReason.CONNECT
                is SecurityException -> AuthNetworkReason.PERMISSION
                else -> null
            }
            if (messageNetwork == null && (value is FirebaseException || value is java.io.IOException || value is SecurityException)) {
                val text = value.message.orEmpty().take(8192)
                // Do not match Firebase's generic "such as timeout ... unreachable host" example.
                messageNetwork = when {
                    Regex("(?i)unable to resolve host|no address associated with hostname|\\bUnknownHostException\\b").containsMatchIn(text) -> AuthNetworkReason.DNS
                    Regex("(?i)\\b(?:connect|read|connection|socket) timed out\\b|\\bSocketTimeoutException\\b").containsMatchIn(text) -> AuthNetworkReason.TIMEOUT
                    Regex("(?i)\\b(?:SSLHandshakeException|SSLPeerUnverifiedException|CertPathValidatorException|CERTIFICATE_VERIFY_FAILED)\\b|trust anchor for certification path not found").containsMatchIn(text) -> AuthNetworkReason.TLS
                    Regex("(?i)\\b(?:EACCES|EPERM)\\b|permission denied|operation not permitted").containsMatchIn(text) -> AuthNetworkReason.PERMISSION
                    Regex("(?i)connection refused|failed to connect to|network is unreachable|no route to host").containsMatchIn(text) -> AuthNetworkReason.CONNECT
                    else -> null
                }
            }
            if (category == AuthFailureClass.UNKNOWN) category = when (value) {
                is FirebaseAuthInvalidCredentialsException -> AuthFailureClass.FIREBASE_AUTH_INVALID_CREDENTIALS_EXCEPTION
                is FirebaseAuthInvalidUserException -> AuthFailureClass.FIREBASE_AUTH_INVALID_USER_EXCEPTION
                is FirebaseAuthUserCollisionException -> AuthFailureClass.FIREBASE_AUTH_USER_COLLISION_EXCEPTION
                is FirebaseAuthWebException -> AuthFailureClass.FIREBASE_AUTH_WEB_EXCEPTION
                is FirebaseAuthException -> AuthFailureClass.FIREBASE_AUTH_EXCEPTION
                is FirebaseNetworkException -> AuthFailureClass.FIREBASE_NETWORK_EXCEPTION
                is FirebaseTooManyRequestsException -> AuthFailureClass.FIREBASE_TOO_MANY_REQUESTS_EXCEPTION
                is FirebaseException -> AuthFailureClass.FIREBASE_EXCEPTION
                is IllegalArgumentException -> AuthFailureClass.ILLEGAL_ARGUMENT_EXCEPTION
                else -> AuthFailureClass.UNKNOWN
            }
            if (value is FirebaseAuthException && code == FirebaseFailureCode.NOT_PROVIDED) {
                code = FirebaseFailureCode.entries.firstOrNull { it.name == value.errorCode && it != FirebaseFailureCode.NOT_PROVIDED && it != FirebaseFailureCode.UNRECOGNIZED }
                    ?: FirebaseFailureCode.UNRECOGNIZED
            }
            if (value is FirebaseException && restriction == AuthProviderRestriction.NONE) {
                val message = value.message.orEmpty().take(8192)
                restriction = AuthProviderRestriction.entries.firstOrNull {
                    it != AuthProviderRestriction.NONE && Regex("(?<![A-Za-z0-9_])${it.name}(?![A-Za-z0-9_])").containsMatchIn(message)
                } ?: AuthProviderRestriction.NONE
            }
            current = value.cause
        }
        val network = if (networkFailure || code == FirebaseFailureCode.ERROR_NETWORK_REQUEST_FAILED || typedNetwork != null) {
            typedNetwork ?: messageNetwork ?: AuthNetworkReason.UNKNOWN
        } else null
        return AuthFailure(category, code, restriction, network)
    }
}

/** Firebase's named app owns native refresh/persistence; no tokens cross activity results. */
class AuthSession private constructor(context: Context, private val journal: DiagnosticJournal?) {
    internal val config = runCatching { AuthClientConfig.load(context) }.getOrNull()
    private val auth: FirebaseAuth? = runCatching {
        val value = checkNotNull(config)
        val options = FirebaseOptions.Builder().setProjectId(value.projectId).setApiKey(value.apiKey)
            .setApplicationId(value.applicationId).build()
        val app = FirebaseApp.getApps(context).find { it.name == NAME }
            ?: FirebaseApp.initializeApp(context.applicationContext, options, NAME)
        check(app.options.applicationId == value.applicationId && app.options.projectId == value.projectId && app.options.apiKey == value.apiKey)
        FirebaseAuth.getInstance(app)
    }.getOrNull()
    private val mutableState = MutableStateFlow(AuthState(when {
        auth == null -> AuthStatus.UNAVAILABLE
        auth.currentUser != null -> AuthStatus.SIGNED_IN
        else -> AuthStatus.SIGNED_OUT
    }))
    val state = mutableState.asStateFlow()
    private val mutableCountry = MutableStateFlow(HondaCountrySnapshot())
    internal val countryOfResidence = mutableCountry.asStateFlow()
    private var operation: String? = null
    private var submitted = false
    private var cancelled = false
    @Volatile var revision: Long = 0
        private set
    private var knownUid = auth?.currentUser?.uid

    init {
        auth?.addAuthStateListener {
            if (knownUid != it.currentUser?.uid) { knownUid = it.currentUser?.uid; revision++ }
            if (operation == null) publish(if (it.currentUser == null) AuthStatus.SIGNED_OUT else AuthStatus.SIGNED_IN)
            else { expireCountry(); mutableState.value = mutableState.value.copy(revision = revision) }
        }
        journal?.record(Component.AUTH, null, Event.STATE_CHANGE,
            if (auth == null) Outcome.UNAVAILABLE else Outcome.OBSERVED,
            if (auth == null) ErrorCode.UNSUPPORTED else ErrorCode.NONE)
    }

    fun currentUser(): FirebaseUser? = auth?.currentUser
    fun hondaApiKey(): String? = config?.hondaApiKey

    private fun publish(status: AuthStatus, code: String = "NONE", failure: AuthFailure? = null) {
        expireCountry(status)
        mutableState.value = AuthState(status, code, failure, revision)
    }

    @Synchronized private fun expireCountry(status: AuthStatus = mutableState.value.status) {
        val old = mutableCountry.value
        if (old.accountRevision != revision || (status != AuthStatus.SIGNED_IN && old.code != null))
            mutableCountry.value = HondaCountrySnapshot(revision = old.revision + 1, accountRevision = revision)
    }

    /** Called only after the existing read's identity checks; never initiates another RPC. */
    @Synchronized internal fun rememberCountry(code: String, uid: String?, expectedRevision: Long) {
        if (!code.matches(Regex("[A-Z]{2}")) || uid == null || revision != expectedRevision ||
            currentUser()?.uid != uid || state.value.status != AuthStatus.SIGNED_IN) return
        val old = mutableCountry.value
        if (old.code != code || old.accountRevision != expectedRevision)
            mutableCountry.value = HondaCountrySnapshot(code, old.revision + 1, expectedRevision)
    }

    fun getIdToken(forceRefresh: Boolean = false): Task<GetTokenResult> =
        (if (mutableState.value.status == AuthStatus.SIGNED_IN) auth?.currentUser?.getIdToken(forceRefresh) else null)
            ?: Tasks.forException(IllegalStateException("AUTH_UNAVAILABLE_OR_SIGNED_OUT"))

    /** Main thread only. One browser flow; do not replace a pending Firebase exchange. */
    internal fun begin(): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(auth != null && operation == null)
        revision++
        return (journal?.newOperation() ?: java.util.UUID.randomUUID().toString()).also {
            operation = it; submitted = false; cancelled = false
            publish(AuthStatus.SIGNING_IN)
            journal?.record(Component.AUTH, it, Event.START, Outcome.STARTED, ErrorCode.NONE)
        }
    }

    internal fun submit(id: String, token: String, complete: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (operation != id || submitted || cancelled || auth == null) { complete(false); return }
        submitted = true
        val exchange = try { auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)) }
        catch (error: Exception) {
            operation = null; submitted = false
            val failure = AuthErrors.map(error)
            publish(AuthStatus.ERROR, failure.summary, failure)
            journal?.record(Component.AUTH, id, Event.STATE_CHANGE, Outcome.FAILED, failure.journalCode)
            complete(false)
            return
        }
        exchange.addOnCompleteListener { task ->
            if (operation != id) { complete(false); return@addOnCompleteListener }
            if (cancelled) auth.signOut()
            val succeeded = task.isSuccessful && !cancelled
            val failure = if (!cancelled && !task.isSuccessful) AuthErrors.map(task.exception) else null
            val code = if (cancelled) "CANCELLED" else if (task.isSuccessful) "NONE" else checkNotNull(failure).summary
            operation = null; submitted = false
            publish(if (succeeded) AuthStatus.SIGNED_IN else if (cancelled) AuthStatus.SIGNED_OUT else AuthStatus.ERROR, code, failure)
            journal?.record(Component.AUTH, id, Event.STATE_CHANGE,
                if (succeeded) Outcome.SUCCEEDED else if (cancelled) Outcome.CANCELLED else Outcome.FAILED,
                if (succeeded) ErrorCode.NONE else if (cancelled) ErrorCode.CANCELLED else checkNotNull(failure).journalCode)
            complete(succeeded)
        }
    }

    internal fun cancel(id: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (operation != id) return
        cancelled = true
        if (submitted) return // SDK task cannot be cancelled: its completion signs out before another flow.
        operation = null
        publish(if (auth?.currentUser == null) AuthStatus.SIGNED_OUT else AuthStatus.SIGNED_IN, "CANCELLED")
        journal?.record(Component.AUTH, id, Event.STOP, Outcome.CANCELLED, ErrorCode.CANCELLED)
    }

    fun signOut() {
        check(Looper.myLooper() == Looper.getMainLooper())
        revision++
        operation?.let(::cancel)
        auth?.signOut()
        if (!submitted) publish(if (auth == null) AuthStatus.UNAVAILABLE else AuthStatus.SIGNED_OUT)
        journal?.record(Component.AUTH, null, Event.STOP, Outcome.OBSERVED, ErrorCode.NONE)
    }

    companion object {
        private const val NAME = "OpenRideSyncHonda"
        @Volatile private var instance: AuthSession? = null
        @Synchronized fun init(context: Context, journal: DiagnosticJournal? = null): AuthSession =
            instance ?: AuthSession(context.applicationContext, journal).also { instance = it }
        fun get(): AuthSession = checkNotNull(instance) { "AUTH_NOT_INITIALIZED" }
    }
}
