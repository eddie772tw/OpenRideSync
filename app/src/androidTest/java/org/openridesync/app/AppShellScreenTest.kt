package org.openridesync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.ui.*

class AppShellScreenTest {
    @get:Rule val compose = createComposeRule()
    private val unavailable = ShellStatus(false, "此版本未配置登入", "未連線")

    @Test fun introductionHasExplicitSkipAndNeverStartsSetupOnComposition() {
        var begin = 0; var skip = 0
        compose.setContent { MaterialTheme { WelcomeScreen({ begin++ }, { skip++ }) } }
        compose.runOnIdle { assertEquals(0, begin + skip) }
        compose.onNodeWithTag("welcome_skip").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, begin); assertEquals(1, skip) }
    }
    @Test fun introductionCanExplicitlyStartGuide() {
        var begin = 0
        compose.setContent { MaterialTheme { WelcomeScreen({ begin++ }, {}) } }
        compose.onNodeWithTag("welcome_begin").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, begin) }
    }
    @Test fun unconfiguredHomeHasActionableAccountAndLocalDiagnostics() {
        val routes = mutableListOf<String>()
        compose.setContent { MaterialTheme { HomeScreen(unavailable, routes::add) } }
        compose.runOnIdle { assertTrue(routes.isEmpty()) }
        compose.onNodeWithTag("home_media").performScrollTo().performClick()
        compose.onNodeWithTag("home_diagnostics").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("account", "diagnostics"), routes) }
    }
    @Test fun signedInHomeOpensActualFeatureRoutesWithoutClaimingReady() {
        val routes = mutableListOf<String>()
        compose.setContent { MaterialTheme { HomeScreen(ShellStatus(true, "已登入", "未連線"), routes::add) } }
        for (route in listOf("vehicles", "media", "weather", "trips", "ride")) {
            compose.onNodeWithTag("home_$route").performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("vehicles", "media", "weather", "trips", "ride"), routes) }
    }
    @Test fun setupAllowsDeferralAndRoutesMissingLoginToAccount() {
        val routes = mutableListOf<String>()
        compose.setContent { MaterialTheme { SetupScreen(unavailable, routes::add, {}, initialStep = 1) } }
        compose.onNodeWithTag("setup_vehicles").performScrollTo().performClick()
        compose.onNodeWithTag("setup_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("setup_home").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("account", "permissions", "home"), routes) }
    }
    @Test fun settingsExposesHelpAndHidesDeveloperToolsOutsideDebug() {
        val routes = mutableListOf<String>()
        compose.setContent { MaterialTheme { SettingsScreen(routes::add, {}, false) } }
        compose.onNodeWithTag("settings_preview").assertDoesNotExist()
        compose.onNodeWithTag("settings_handlebar").assertDoesNotExist()
        compose.onNodeWithTag("settings_setup").performScrollTo().performClick()
        compose.onNodeWithTag("settings_permissions").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("setup", "permissions"), routes) }
    }
    @Test fun guidePagesAdvanceAndBackWithoutClaimingFeatureCompletion() {
        val steps = mutableListOf<Int>(); var exited = false
        compose.setContent { MaterialTheme { SetupScreen(unavailable, {}, { exited = true }, onStepChanged = steps::add) } }
        compose.onNodeWithTag("setup_next").performScrollTo().performClick()
        compose.onNodeWithTag("setup_permissions").assertExists()
        compose.onNodeWithTag("setup_next").performScrollTo().performClick()
        compose.onNodeWithTag("setup_explore").assertExists()
        compose.onNodeWithTag("shell_back").performScrollTo().performClick()
        compose.onNodeWithTag("setup_permissions").assertExists()
        compose.runOnIdle { assertEquals(listOf(1, 2, 1), steps); assertFalse(exited) }
    }

}
