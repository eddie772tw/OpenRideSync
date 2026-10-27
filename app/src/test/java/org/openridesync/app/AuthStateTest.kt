package org.openridesync.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openridesync.app.auth.AuthState
import org.openridesync.app.auth.AuthStatus

class AuthStateTest {
    @Test fun accountReplacementEmitsEvenWhenBothAccountsAreSignedIn() = runBlocking {
        val state = MutableStateFlow(AuthState(AuthStatus.SIGNED_IN, revision = 1))
        val observed = mutableListOf<AuthState>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { state.take(2).toList(observed) }
        state.value = AuthState(AuthStatus.SIGNED_IN, revision = 2)
        withTimeout(1_000) { collector.join() }
        assertEquals(listOf(1L, 2L), observed.map { it.revision })
        assertEquals(listOf(AuthStatus.SIGNED_IN, AuthStatus.SIGNED_IN), observed.map { it.status })
    }
}
