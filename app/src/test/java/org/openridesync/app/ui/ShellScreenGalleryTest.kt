package org.openridesync.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.openridesync.app.ui.theme.AppearanceMode
import androidx.compose.ui.test.assertIsSelected
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real shell composables with explicit synthetic state; no application singleton or live service. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellScreenGalleryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var host: View
    private val signedOut = ShellStatus(false, "尚未登入（視覺審查 fixture）", "尚未啟動服務")
    private val signedIn = ShellStatus(true, "已登入（合成視覺 fixture，沒有真實帳號）", "尚未連線")

    @Test fun welcome() = gallery("01-welcome", "welcome_screen") { WelcomeScreen({}, {}) }
    @Test fun setupAccount() = gallery("02-setup-account", "setup_screen") { SetupScreen(signedOut, {}, {}, initialStep = 0) }
    @Test fun setupPermissionsVehicle() = gallery("03-setup-permissions-vehicle", "setup_screen") { SetupScreen(signedOut, {}, {}, initialStep = 1) }
    @Test fun setupExplore() = gallery("04-setup-explore", "setup_screen") { SetupScreen(signedOut, {}, {}, initialStep = 2) }
    @Test fun homeSignedOut() = gallery("05-home-signedout", "home_screen") { HomeOverviewContent(signedOut, homeOverview(signedOut), {}) }
    @Test fun homeSyntheticSignedIn() = gallery("06-home-synthetic-signedin", "home_screen") { HomeOverviewContent(signedIn, homeOverview(signedIn), {}) }
    @Test fun settings() = gallery("07-settings", "settings_screen") { SettingsHubContent({}, {}, AppearanceMode.LIGHT, {}, developer = false) }
    @Test fun developerSettings() = gallery("08-settings-developer", "settings_screen") { SettingsHubContent({}, {}, AppearanceMode.LIGHT, {}, developer = true) }

    private fun gallery(name: String, scrollTag: String, content: @Composable () -> Unit) {
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
        assertFalse(RuntimeEnvironment.getApplication().assets.list("").orEmpty().contains("auth-client.json"))
        val route = when {
            name.startsWith("01-") -> "welcome"
            name.startsWith("02-") || name.startsWith("03-") || name.startsWith("04-") -> "setup"
            name.startsWith("05-") || name.startsWith("06-") -> "home"
            else -> "settings"
        }
        val routes = if (route == "home") listOf("home") else listOf("home", route)
        compose.setContent {
            val view = LocalView.current
            SideEffect { host = view }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                OpenRideSyncTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(390.dp, 844.dp).background(MaterialTheme.colorScheme.background)
                        .testTag("gallery_viewport"), propagateMinConstraints = true) {
                        MainTabShell(routes, {}) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (route in setOf("welcome", "setup")) compose.onNodeWithTag("main_tab_shell").assertDoesNotExist()
        else compose.onNodeWithTag("main_tab_$route").assertIsSelected()
        var page = 0
        while (true) {
            capture("$name-${page.toString().padStart(2, '0')}")
            val node = compose.onNodeWithTag(scrollTag)
            val axis = node.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val before = axis.value()
            if (before >= axis.maxValue() - 0.5f) break
            check(page < 20) { "Gallery must reach the actual end, not silently truncate" }
            val step = (node.fetchSemanticsNode().boundsInRoot.height * 0.75f).coerceAtMost(650f)
            check(step > 0f) { "Scrollable viewport must be visible" }
            node.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, step) }
            compose.waitForIdle()
            check(axis.value() > before) { "Gallery scroll did not advance" }
            page++
        }
    }

    private fun capture(name: String) {
        val bounds = compose.onNodeWithTag("gallery_viewport").fetchSemanticsNode().boundsInRoot
        val bitmap = compose.runOnIdle {
            check(host.isLaidOut && host.width > 0 && host.height > 0)
            check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= host.width && bounds.bottom <= host.height)
            Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { translate(-bounds.left, -bounds.top); host.draw(this) }
            }
        }
        try {
            val directory = File(System.getProperty("uiGalleryOutputDir") ?: "build/reports/ui-gallery")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(directory, "$name.fixture.txt").writeText("fixture=synthetic; current production content and MainTabShell navigation policy\n" +
                "profile=390x844; density=1; fontScale=1.0; appearance=LIGHT\n" +
                "testSource=app/src/test/java/org/openridesync/app/ui/ShellScreenGalleryTest.kt\n" +
                "buildSourceCommit=${org.openridesync.app.BuildConfig.SOURCE_COMMIT}; build-time identifier only\n" +
                "sourceVersion=${System.getProperty("uiGallerySourceVersion") ?: "not supplied; see gallery build manifest"}\n")
            assertEquals(390, bitmap.width); assertEquals(844, bitmap.height)
            val first = bitmap.getPixel(0, 0)
            assertEquals(255, android.graphics.Color.alpha(first))
            assertTrue((0 until bitmap.height step 4).any { y -> (0 until bitmap.width step 4).any { x -> bitmap.getPixel(x, y) != first } })
        } finally { bitmap.recycle() }
    }
}
