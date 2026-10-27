package org.openridesync.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.BuildConfig
import org.openridesync.app.ui.theme.AppearanceMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
class SettingsHubTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renderingHasNoCallbacksAndRoutesOnlyExplicitClicks() {
        val routes = mutableListOf<String>()
        var changes = 0
        compose.setContent { MaterialTheme {
            SettingsHubContent(routes::add, {}, AppearanceMode.SYSTEM, { changes++ }, developer = false)
        } }
        compose.runOnIdle { assertTrue(routes.isEmpty()); assertEquals(0, changes) }
        val expected = listOf("account", "vehicles", "trips", "trip-recording", "messages", "quick-replies", "recognition-language",
            "calls", "navigation", "permissions", "setup", "diagnostics")
        for (route in expected) {
            compose.onNodeWithTag("settings_$route").performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(expected, routes) }
        compose.onNodeWithTag("settings_preview").assertDoesNotExist()
        compose.onNodeWithTag("settings_handlebar").assertDoesNotExist()
    }

    @Test fun appearanceRadiosReflectControlledSelection() {
        val mode = mutableStateOf(AppearanceMode.SYSTEM)
        compose.setContent { MaterialTheme {
            SettingsHubContent({}, {}, mode.value, { mode.value = it })
        } }
        compose.onNodeWithTag("settings_appearance_system").assertIsSelected()
        for (name in listOf("dark", "light", "system")) {
            compose.onNodeWithTag("settings_appearance_$name").performScrollTo().performClick().assertIsSelected()
        }
        compose.runOnIdle { assertEquals(AppearanceMode.SYSTEM, mode.value) }
    }

    @Test fun everyDocumentOpensLocallyAndBackReturnsToSettings() {
        var exited = 0
        var routes = 0
        compose.setContent { MaterialTheme {
            SettingsHubContent({ routes++ }, { exited++ }, AppearanceMode.SYSTEM, {})
        } }
        for (id in listOf("about", "privacy", "licenses", "terms", "docs")) {
            compose.onNodeWithTag("settings_$id").performScrollTo().performClick()
            compose.onNodeWithTag("settings_document_$id").assertExists()
            compose.onNodeWithTag("shell_back").performScrollTo().performClick()
            compose.onNodeWithTag("settings_screen").assertExists()
        }
        compose.runOnIdle { assertEquals(0, routes); assertEquals(0, exited) }
        compose.onNodeWithTag("shell_back").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, exited) }
    }

    @Test fun developerControlsRequireActualDebugBuild() {
        val routes = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            SettingsHubContent(routes::add, {}, AppearanceMode.SYSTEM, {}, developer = true)
        } }
        if (BuildConfig.DEBUG) {
            compose.onNodeWithTag("settings_preview").performScrollTo().performClick()
            compose.onNodeWithTag("settings_handlebar").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(listOf("ride-preview", "handlebar-test"), routes) }
        } else {
            compose.onNodeWithTag("settings_preview").assertDoesNotExist()
            compose.onNodeWithTag("settings_handlebar").assertDoesNotExist()
        }
    }

    @Test fun aboutShowsArtifactIdentityAndConfigurationWithoutImplyingSignIn() {
        val configured = mutableStateOf(false)
        var routes = 0
        compose.setContent { MaterialTheme {
            SettingsHubContent({ routes++ }, {}, AppearanceMode.SYSTEM, {}, hondaConfigured = configured.value)
        } }
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithText("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
        compose.onNodeWithText("來源版本：${BuildConfig.SOURCE_COMMIT}").assertExists()
        compose.onNodeWithText(clientConfigurationMessage(false)).assertExists()
        compose.runOnIdle { configured.value = true }
        compose.onNodeWithText(clientConfigurationMessage(true)).assertExists()
        compose.onNodeWithText(clientConfigurationMessage(false)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, routes) }
    }
}
