package org.openridesync.app.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.navigation.MainTab
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class MainTabShellTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tabsOnlyDispatchExplicitSelectionAndRetainSingleOwner() {
        var routes by mutableStateOf(listOf("home"))
        val clicks = mutableListOf<String>()
        compose.setContent { OpenRideSyncTheme { MainTabShell(routes, { clicks += it; routes = MainTab.select(routes,it) }) { Text(routes.last()) } } }
        compose.runOnIdle { assertTrue(clicks.isEmpty()) }
        for (tab in MainTab.entries) {
            compose.onNodeWithTag("main_tab_${tab.route}").performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(tab,MainTab.selected(routes)) }
        }
        compose.runOnIdle { assertEquals(MainTab.entries.map { it.route },clicks) }
    }
    @Test fun welcomeDoesNotExposeTabs() {
        compose.setContent { OpenRideSyncTheme { MainTabShell(listOf("home","welcome"), { error("unexpected") }) { Text("Welcome fixture") } } }
        compose.onNodeWithText("Welcome fixture").assertIsDisplayed()
        MainTab.entries.forEach { compose.onNodeWithTag("main_tab_${it.route}").assertDoesNotExist() }
    }
    @Test fun connectedRideAndPreviewUseTheSameImmersiveChrome() {
        val connected = mutableStateOf(true)
        compose.setContent { OpenRideSyncTheme {
            MainTabShell(listOf("home", "ride"), { error("unexpected") }, immersive = connected.value) {
                Text("Ride fixture")
            }
        } }
        compose.onNodeWithText("Ride fixture").assertIsDisplayed()
        compose.onNodeWithTag("main_tab_shell").assertDoesNotExist()
        compose.runOnIdle { connected.value = false }
        compose.onNodeWithTag("main_tab_shell").assertExists()
        MainTab.entries.forEach { compose.onNodeWithTag("main_tab_${it.route}").assertExists() }
    }
    @Test fun fiveLabelsFitSmallScreenWithDoubleText() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
            OpenRideSyncTheme { Box(Modifier.requiredSize(320.dp,480.dp).testTag("frame")) {
                MainTabShell(listOf("home","customize"), {}) { Text("測試內容") }
            } }
        } }
        val frame=compose.onNodeWithTag("frame").getUnclippedBoundsInRoot()
        MainTab.entries.forEach {
            val label=compose.onNodeWithText(it.label);label.assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            label.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Label must not clip: ${it.label}", layouts.isNotEmpty() && layouts.none { result -> result.hasVisualOverflow })
            val bounds=label.getUnclippedBoundsInRoot()
            assertTrue(bounds.left>=frame.left && bounds.right<=frame.right)
            assertTrue(bounds.top>=frame.top && bounds.bottom<=frame.bottom)
        }
    }
    @Test fun sideSystemNavigationInsetsRemainAvailableToContent() {
        val insets = WindowInsets(left = 48, top = 0, right = 0, bottom = 24)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            OpenRideSyncTheme { MainTabShell(listOf("home"), {}, navigationInsets = insets) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
                    Box(Modifier.fillMaxSize().testTag("safe_content"))
                }
            } }
        } }
        val shell = compose.onNodeWithTag("main_tab_shell").getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("safe_content").getUnclippedBoundsInRoot()
        assertEquals(shell.left.value + 48f, content.left.value, .1f)
    }

}
