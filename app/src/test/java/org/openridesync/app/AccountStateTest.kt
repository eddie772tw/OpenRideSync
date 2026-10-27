package org.openridesync.app

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ui.residence
import java.util.Locale

class AccountStateTest {
    @Test fun partialCountryFailureKeepsAccountButRequiresLoginOnlyForIdentityErrors() {
        val partial = AccountState(details = HondaAccountDetails("synthetic-honda-id", "JP"), countryErrorCode = "NETWORK")
        assertNotNull(partial.details)
        assertNull(partial.countryCode)
        assertFalse(partial.requiresLogin)
        assertFalse(partial.copy(countryErrorCode = "NOT_FOUND").requiresLogin)
        assertTrue(partial.copy(countryErrorCode = "UNAUTHENTICATED").requiresLogin)
        assertTrue(AccountState(errorCode = "NOT_FOUND").requiresLogin)
        assertFalse(AccountState(cancelled = true).requiresLogin)
    }

    @Test fun residenceUsesHondaCodeAndCurrentLanguageWithoutGuessingMissingCountry() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("Taiwan", residence("TW"))
            assertEquals("尚未取得", residence(null))
            assertEquals("未識別的居住地", residence(""))
            assertEquals("未識別的居住地", residence("TW/private"))
        } finally { Locale.setDefault(previous) }
    }
}
