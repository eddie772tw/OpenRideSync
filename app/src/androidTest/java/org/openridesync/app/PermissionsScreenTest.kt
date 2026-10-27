package org.openridesync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.ui.AccessCard
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog

/** Does not grant, revoke or reset any permission on the user's phone. */
class PermissionsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explanationPrecedesExplicitRequestAndDeniedAccessKeepsSettingsAvailable() {
        var requests = 0
        var settings = 0
        val granted = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            AccessCard(AppAccess.PHONE, granted.value, { requests++ }, { settings++ })
        } }
        compose.onNodeWithText(AppAccess.PHONE.explanation).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(0, settings) }
        compose.onNodeWithTag("permission_PHONE_request").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("permission_PHONE_status").assertTextEquals("尚未開啟")
        compose.onNodeWithTag("permission_PHONE_settings").performClick()
        compose.runOnIdle { assertEquals(1, settings); granted.value = true }
        compose.onNodeWithTag("permission_PHONE_request").assertDoesNotExist()
        compose.onNodeWithTag("permission_PHONE_status").assertTextEquals("已開啟")
    }

    @Test fun optionalNotificationDoesNotGateContinueAndClosingDoesNotRequestAnything() {
        var continues = 0
        var cancels = 0
        val required = mutableStateOf(emptySet<AppAccess>())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme {
            PermissionDialog(listOf(AppAccess.NOTIFICATIONS), required.value, "繼續連線",
                { continues++ }, { cancels++ })
        } }
        compose.onNodeWithTag("permission_continue").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, continues); required.value = setOf(AppAccess.NOTIFICATIONS) }
        if (AppAccess.NOTIFICATIONS.granted(context)) compose.onNodeWithTag("permission_continue").assertIsEnabled()
        else compose.onNodeWithTag("permission_continue").assertIsNotEnabled()
        compose.onNodeWithTag("permission_later").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun wifiNameExplainsOptionalPreciseLocationBeforeAnyRequest() {
        var requests = 0
        var locations = 0
        compose.setContent { MaterialTheme {
            AccessCard(AppAccess.WIFI_NAME, false, { requests++ }, {}, onLocationSettings = { locations++ })
        } }
        compose.onNodeWithText(AppAccess.WIFI_NAME.explanation).assertExists()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(0, locations) }
        compose.onNodeWithTag("permission_WIFI_NAME_request").performClick()
        compose.onNodeWithTag("permission_WIFI_NAME_location_settings").performClick()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(1, locations) }
    }

    @Test fun locationExplainsWeatherAndRecoveryAndOpensLocationSettingsOnlyOnClick() {
        var requests = 0
        var locations = 0
        compose.setContent { MaterialTheme {
            AccessCard(AppAccess.LOCATION, false, { requests++ }, {}, onLocationSettings = { locations++ })
        } }
        compose.onNodeWithText("所在地天氣與自動重連").assertExists()
        compose.onNodeWithText(AppAccess.LOCATION.explanation).assertExists()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(0, locations) }
        compose.onNodeWithTag("permission_LOCATION_location_settings").performClick()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(1, locations) }
    }
}
