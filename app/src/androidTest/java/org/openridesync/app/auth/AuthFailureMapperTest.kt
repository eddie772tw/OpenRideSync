package org.openridesync.app.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic SDK exception mapping only: no Firebase initialization, credentials or requests. */
@RunWith(AndroidJUnit4::class)
class AuthFailureMapperTest {
    @Test fun mapsCanonicalSdkCodeAndRestrictionWithoutCopyingMessage() {
        val failure = AuthErrors.map(RuntimeException("synthetic-private-wrapper", FirebaseAuthException(
            "ERROR_INTERNAL_ERROR", "synthetic-private-content {\"reason\":\"API_KEY_ANDROID_APP_BLOCKED\"}"
        )))
        assertEquals(AuthFailureClass.FIREBASE_AUTH_EXCEPTION, failure.category)
        assertEquals(FirebaseFailureCode.ERROR_INTERNAL_ERROR, failure.firebaseCode)
        assertEquals(AuthProviderRestriction.API_KEY_ANDROID_APP_BLOCKED, failure.restriction)
        assertEquals("FIREBASE_AUTH_EXCEPTION/ERROR_INTERNAL_ERROR/API_KEY_ANDROID_APP_BLOCKED", failure.summary)
        assertFalse(failure.toString().contains("synthetic-private"))
        assertFalse(failure.summary.contains("synthetic-private"))
    }

    @Test fun handlesGenericSdkNetworkAndRateLimitFailures() {
        val credential = AuthErrors.map(FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "synthetic-private"))
        assertEquals(AuthFailureClass.FIREBASE_AUTH_INVALID_CREDENTIALS_EXCEPTION, credential.category)
        assertEquals(FirebaseFailureCode.ERROR_INVALID_CREDENTIAL, credential.firebaseCode)
        assertEquals(AuthFailureClass.FIREBASE_NETWORK_EXCEPTION,
            AuthErrors.map(FirebaseNetworkException("synthetic-private")).category)
        assertEquals(AuthFailureClass.FIREBASE_TOO_MANY_REQUESTS_EXCEPTION,
            AuthErrors.map(FirebaseTooManyRequestsException("synthetic-private")).category)
        val generic = AuthErrors.map(FirebaseException("{\"reason\":\"API_KEY_SERVICE_BLOCKED\"}"))
        assertEquals(AuthFailureClass.FIREBASE_EXCEPTION, generic.category)
        assertEquals(FirebaseFailureCode.NOT_PROVIDED, generic.firebaseCode)
        assertEquals(AuthProviderRestriction.API_KEY_SERVICE_BLOCKED, generic.restriction)
    }

    @Test fun rejectsUnknownCodesPartialMarkersAndUntrustedExceptionText() {
        val unknown = AuthErrors.map(FirebaseAuthException("synthetic-private-code", "synthetic-private-message"))
        assertEquals(FirebaseFailureCode.UNRECOGNIZED, unknown.firebaseCode)
        assertFalse(unknown.toString().contains("synthetic-private"))
        assertEquals(AuthProviderRestriction.NONE,
            AuthErrors.map(FirebaseException("NOT_API_KEY_ANDROID_APP_BLOCKED_SUFFIX")).restriction)
        assertEquals(AuthProviderRestriction.NONE,
            AuthErrors.map(Exception("API_KEY_ANDROID_APP_BLOCKED")).restriction)
        assertEquals(AuthProviderRestriction.NONE,
            AuthErrors.map(FirebaseException("x".repeat(8192) + " API_KEY_ANDROID_APP_BLOCKED")).restriction)
        assertEquals(AuthFailureClass.UNKNOWN, AuthErrors.map(null).category)
    }

    @Test fun classifiesKnownNetworkCausesAndUsesNetworkJournalCodes() {
        val cases = listOf(
            java.net.UnknownHostException("synthetic-private-host") to AuthNetworkReason.DNS,
            java.net.ConnectException("synthetic-private-endpoint") to AuthNetworkReason.CONNECT,
            java.net.SocketTimeoutException("synthetic-private-endpoint") to AuthNetworkReason.TIMEOUT,
            javax.net.ssl.SSLHandshakeException("synthetic-private-certificate") to AuthNetworkReason.TLS,
            SecurityException("synthetic-private-permission") to AuthNetworkReason.PERMISSION
        )
        for ((cause, expected) in cases) {
            // Typed causes override any less precise wrapper message classification.
            val error = FirebaseNetworkException("Connection refused synthetic-private").apply { initCause(cause) }
            val failure = AuthErrors.map(error)
            assertEquals(expected, failure.networkReason)
            assertEquals(if (expected == AuthNetworkReason.TIMEOUT) ErrorCode.TIMEOUT else ErrorCode.NETWORK, failure.journalCode)
            assertFalse(failure.toString().contains("synthetic-private"))
        }
        assertEquals(ErrorCode.UNAUTHORIZED,
            AuthErrors.map(FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "synthetic-private")).journalCode)
    }

    @Test fun recognizesExplicitWrappedNetworkMessagesWithoutGuessingFromGenericExample() {
        for ((message, expected) in listOf(
            "Unable to resolve host synthetic-private" to AuthNetworkReason.DNS,
            "Failed to connect to synthetic-private" to AuthNetworkReason.CONNECT,
            "Read timed out synthetic-private" to AuthNetworkReason.TIMEOUT,
            "Trust anchor for certification path not found synthetic-private" to AuthNetworkReason.TLS,
            "socket failed: EPERM (Operation not permitted) synthetic-private" to AuthNetworkReason.PERMISSION,
            "A network error (such as timeout, interrupted connection or unreachable host) has occurred." to AuthNetworkReason.UNKNOWN
        )) {
            val failure = AuthErrors.map(FirebaseNetworkException(message))
            assertEquals(expected, failure.networkReason)
            assertFalse(failure.summary.contains("synthetic-private"))
        }
        assertEquals(AuthNetworkReason.UNKNOWN, AuthErrors.map(FirebaseNetworkException("x".repeat(8192) + " Read timed out")).networkReason)
        assertNull(AuthErrors.map(Exception("Unable to resolve host synthetic-private")).networkReason)
    }
}
