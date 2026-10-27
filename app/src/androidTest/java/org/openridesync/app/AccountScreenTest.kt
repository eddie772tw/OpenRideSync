package org.openridesync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.auth.AuthState
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.ui.AccountScreen

/** Synthetic UI checks; never calls Google/Honda or signs out an actual session. */
class AccountScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun diagnosticsIsReachableWithoutAuthConfiguration() {
        var opened = 0
        compose.setContent { MaterialTheme {
            AccountScreen(AuthState(AuthStatus.UNAVAILABLE), AccountState(), null, null,
                {}, {}, {}, {}, { opened++ }, {}, {}, {})
        } }
        compose.onNodeWithTag("account_login").assertIsNotEnabled()
        compose.onNodeWithTag("account_media").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("account_diagnostics").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun countryFailureDoesNotSubstituteLegacyCountryOrHondaId() {
        compose.setContent { MaterialTheme {
            AccountScreen(AuthState(AuthStatus.SIGNED_IN),
                AccountState(details = HondaAccountDetails("legacy-honda-id", "JP"), countryErrorCode = "NOT_FOUND"),
                "synthetic@example.invalid", "synthetic-firebase-uid", {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("synthetic-firebase-uid").assertExists()
        compose.onNodeWithText("legacy-honda-id").assertDoesNotExist()
        compose.onNodeWithText("尚未取得").assertExists()
        compose.onNodeWithTag("account_read").performScrollTo().assertIsEnabled()
    }

    @Test fun signOutRequiresConfirmationAndCancelDoesNothing() {
        var signOuts = 0
        compose.setContent { MaterialTheme {
            AccountScreen(AuthState(AuthStatus.SIGNED_IN), AccountState(), null, "synthetic-uid",
                {}, {}, {}, { signOuts++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("account_sign_out").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, signOuts) }
        compose.onNodeWithTag("account_cancel_sign_out").performClick()
        compose.runOnIdle { assertEquals(0, signOuts) }
        compose.onNodeWithTag("account_sign_out").performClick()
        compose.onNodeWithTag("account_confirm_sign_out").performClick()
        compose.runOnIdle { assertEquals(1, signOuts) }
    }
    @Test fun detailModeKeepsDiagnosticRecoveryWithoutFeatureMenu() {
        var diagnostics = 0; var login = 0
        val auth = androidx.compose.runtime.mutableStateOf(AuthState(AuthStatus.UNAVAILABLE))
        compose.setContent { MaterialTheme {
            AccountScreen(auth.value, AccountState(), null, null,
                { login++ }, {}, {}, {}, { diagnostics++ }, {}, {}, {},
                onBack = {}, showFeatureLinks = false)
        } }
        compose.runOnIdle { assertEquals(0, diagnostics); assertEquals(0, login); auth.value = AuthState(AuthStatus.ERROR) }
        compose.onNodeWithTag("account_vehicles").assertDoesNotExist()
        compose.onNodeWithTag("account_back").assertExists()
        compose.onNodeWithTag("account_diagnostics").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, diagnostics); assertEquals(0, login) }
    }
}
