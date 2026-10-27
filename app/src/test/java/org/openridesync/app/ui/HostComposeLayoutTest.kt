package org.openridesync.app.ui

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import kotlin.math.roundToInt
import java.io.File
import org.junit.rules.TestName
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host-only rendering and callback tests. A plain Application and ComponentActivity host the real
 * shell composables; no MainActivity, ViewModel, service, account, permission request or radio is
 * started. Native-graphics captures are structural evidence, not device or visual acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostComposeLayoutTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val testName = TestName()
    private var captureIndex = 0
    private var expectedDark = false
    private lateinit var hostView: View

    @Before
    @SuppressLint("DiscouragedApi")
    fun requirePublicSyntheticFixture() {
        val application = RuntimeEnvironment.getApplication()
        assertEquals(Application::class.java, application.javaClass)
        // OpenRideSyncTheme resolves optional fonts dynamically. Fail rather than accidentally
        // accepting a private-resource rendering as the clean/public fixture.
        for ((type, name) in listOf(
            "font" to "oem_ui_regular", "font" to "oem_ui_medium",
            "drawable" to "oem_ui_bluetooth", "drawable" to "oem_ui_music",
        )) {
            assertEquals("This host fixture must not load private resource $name", 0,
                application.resources.getIdentifier(name, type, application.packageName))
        }
        assertFalse("This fixture must not contain private login configuration",
            application.assets.list("").orEmpty().contains("auth-client.json"))
    }

    @Test
    fun smallLightWelcomeKeepsBothActionsReachable() {
        val actions = mutableListOf<String>()
        setFixture(dark = false, fontScale = 1f) {
            WelcomeScreen({ actions += "begin" }, { actions += "skip" })
        }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText("Alpha · ${org.openridesync.app.BuildConfig.VERSION_NAME} (${org.openridesync.app.BuildConfig.VERSION_CODE})").assertExists()
        compose.onNodeWithText(clientConfigurationMessage(false)).assertExists()
        assertRenderedViewport()
        clickReachable("welcome_begin")
        clickReachable("welcome_skip")
        compose.runOnIdle { assertEquals(listOf("begin", "skip"), actions) }
    }

    @Test
    fun smallDarkWelcomeWithDoubleSizeTextKeepsBothActionsReachable() {
        val actions = mutableListOf<String>()
        setFixture(dark = true, fontScale = 2f) {
            WelcomeScreen({ actions += "begin" }, { actions += "skip" })
        }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        assertRenderedViewport()
        clickReachable("welcome_skip")
        clickReachable("welcome_begin")
        assertRenderedViewport()
        compose.runOnIdle { assertEquals(listOf("skip", "begin"), actions) }
    }

    @Test
    fun longSignedOutHomeInDarkLargeTextKeepsAccountAndLocalRoutesReachable() {
        val routes = mutableListOf<String>()
        setFixture(dark = true, fontScale = 2f) {
            HomeScreen(ShellStatus(false, LONG_ACCOUNT, LONG_RIDE), routes::add)
        }
        compose.runOnIdle { assertTrue(routes.isEmpty()) }
        compose.onNodeWithText(LONG_ACCOUNT).assertDoesNotExist()
        compose.onNodeWithText("尚未登入").assertExists()
        compose.onNodeWithText(LONG_RIDE).assertExists()
        assertRenderedViewport()
        clickReachable("home_setup")
        clickReachable("home_settings")
        for (route in listOf("vehicles", "media", "weather", "trips", "ride", "diagnostics")) {
            clickReachable("home_$route", allowTallTile = route == "ride")
        }
        assertRenderedViewport()
        compose.runOnIdle {
            assertEquals(listOf("setup", "settings", "account", "account", "account", "account", "ride", "diagnostics"), routes)
        }
    }

    @Test
    fun longSignedInHomeInLightLargeTextKeepsEveryFeatureRouteReachable() {
        val routes = mutableListOf<String>()
        setFixture(dark = false, fontScale = 2f) {
            val status = ShellStatus(true, "已登入", LONG_RIDE)
            HomeOverviewContent(status, homeOverview(status), routes::add, accountName = LONG_ACCOUNT)
        }
        compose.runOnIdle { assertTrue(routes.isEmpty()) }
        compose.onNodeWithText(LONG_ACCOUNT).assertExists()
        compose.onNodeWithText(LONG_RIDE).assertExists()
        assertRenderedViewport()
        for (route in listOf("vehicles", "media", "weather", "trips", "ride", "diagnostics")) {
            clickReachable("home_$route", allowTallTile = route == "ride")
        }
        compose.runOnIdle {
            assertEquals(listOf("vehicles", "media", "weather", "trips", "ride", "diagnostics"), routes)
        }
    }

    @Test
    fun darkLargeTextSetupCanAdvanceReturnAndDeferWithLongStatus() {
        val routes = mutableListOf<String>()
        val steps = mutableListOf<Int>()
        var exits = 0
        setFixture(dark = true, fontScale = 2f) {
            SetupScreen(ShellStatus(false, LONG_ACCOUNT, LONG_RIDE), routes::add,
                { exits++ }, onStepChanged = steps::add)
        }
        compose.runOnIdle { assertTrue(routes.isEmpty()); assertTrue(steps.isEmpty()); assertEquals(0, exits) }
        clickReachable("setup_account")
        clickReachable("setup_next")
        compose.onNodeWithTag("setup_step").assertTextEquals("步驟 2／3 · 權限與車輛")
        clickReachable("setup_permissions")
        clickReachable("setup_vehicles")
        clickReachable("setup_next")
        compose.onNodeWithTag("setup_step").assertTextEquals("步驟 3／3 · 開始探索")
        clickReachable("setup_explore")
        assertRenderedViewport()
        clickReachable("shell_back")
        compose.onNodeWithTag("setup_step").assertTextEquals("步驟 2／3 · 權限與車輛")
        clickReachable("setup_home")
        compose.runOnIdle {
            assertEquals(listOf(1, 2, 1), steps)
            assertEquals(listOf("account", "permissions", "account", "home", "home"), routes)
            assertEquals(0, exits)
        }
    }

    @Test
    fun lightLargeTextSettingsKeepsProductionRoutesAndBackReachable() {
        val routes = mutableListOf<String>()
        var backs = 0
        setFixture(dark = false, fontScale = 2f) {
            SettingsScreen(routes::add, { backs++ }, developer = false)
        }
        compose.onNodeWithTag("settings_preview").assertDoesNotExist()
        compose.onNodeWithTag("settings_handlebar").assertDoesNotExist()
        compose.runOnIdle { assertTrue(routes.isEmpty()); assertEquals(0, backs) }
        for (route in listOf("account", "permissions", "setup", "diagnostics")) {
            clickReachable("settings_$route")
        }
        assertRenderedViewport()
        clickReachable("shell_back")
        compose.runOnIdle {
            assertEquals(listOf("account", "permissions", "setup", "diagnostics"), routes)
            assertEquals(1, backs)
        }
    }

    @Test
    fun darkLargeTextDeveloperSettingsDispatchesOnlyExplicitCallbacks() {
        val routes = mutableListOf<String>()
        setFixture(dark = true, fontScale = 2f) { SettingsScreen(routes::add, {}, developer = true) }
        compose.runOnIdle { assertTrue(routes.isEmpty()) }
        clickReachable("settings_preview")
        clickReachable("settings_handlebar")
        assertRenderedViewport()
        compose.runOnIdle { assertEquals(listOf("ride-preview", "handlebar-test"), routes) }
    }

    private fun setFixture(dark: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        expectedDark = dark
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            // This is a controlled Compose viewport/font scale, not an Android settings change.
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OpenRideSyncTheme(darkTheme = dark) {
                    Box(Modifier.requiredSize(320.dp, 480.dp)
                        .background(MaterialTheme.colorScheme.background).testTag(VIEWPORT),
                        propagateMinConstraints = true) {
                        content()
                    }
                }
            }
        }
    }

    private fun clickReachable(tag: String, allowTallTile: Boolean = false) {
        val action = compose.onNodeWithTag(tag)
        action.performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val actionBounds = action.getUnclippedBoundsInRoot()
        val viewportBounds = compose.onNodeWithTag(VIEWPORT).getUnclippedBoundsInRoot()
        val tolerance = 1.dp
        assertTrue("$tag must fit horizontally in the small viewport after scrolling",
            actionBounds.left >= viewportBounds.left - tolerance && actionBounds.right <= viewportBounds.right + tolerance)
        if (allowTallTile) {
            // Whole-tile actions can exceed one screen with very long text at 2x font size.
            // Verify the clipped, visible touch target; ordinary buttons must still fit in full.
            val visible = action.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must expose a visible 48px target in the 1dp/px fixture",
                visible.top >= viewport.top && visible.bottom <= viewport.bottom &&
                    visible.width >= 48 && visible.height >= 48)
        } else assertTrue("$tag must fit vertically in the small viewport after scrolling",
            actionBounds.top >= viewportBounds.top - tolerance && actionBounds.bottom <= viewportBounds.bottom + tolerance)
        action.performClick()
    }

    private fun assertRenderedViewport() {
        // Compose captureToImage's forceRedraw waits for a real window surface, which
        // times out on this Robolectric runtime (upstream issue 8071). Draw the actual
        // Android Compose host with native Canvas, cropped to the semantic viewport.
        // This is not a PixelCopy/window compositor or device-GPU screenshot.
        val bounds = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        val image = compose.runOnIdle {
            check(hostView.isLaidOut && hostView.width > 0 && hostView.height > 0)
            check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= hostView.width && bounds.bottom <= hostView.height) {
                "Capture viewport must be within the actual Android Compose root"
            }
            Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.translate(-bounds.left, -bounds.top)
                hostView.draw(canvas)
            }.asImageBitmap()
        }
        val output = File((System.getProperty("hostComposeOutputDir") ?: "build/reports/host-compose"))
        check(output.isDirectory || output.mkdirs()) { "Cannot create host screenshot output" }
        File(output, "${testName.methodName}-${captureIndex++}.png").outputStream().use {
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot save host capture" }
        }
        assertEquals("Controlled host capture width", 320, image.width)
        assertEquals("Controlled host capture height", 480, image.height)
        val pixels = image.toPixelMap()
        val first = pixels[0, 0]
        assertEquals("Captured background follows the requested light/dark theme", expectedDark, first.luminance() < 0.5f)
        assertTrue("Captured background must be opaque", first.alpha >= 0.99f)
        var visiblePixel = false
        var differentPixel = false
        for (y in 0 until image.height step 3) {
            for (x in 0 until image.width step 3) {
                val pixel = pixels[x, y]
                visiblePixel = visiblePixel || pixel.alpha > 0f
                differentPixel = differentPixel || pixel != first
            }
        }
        assertTrue("Native host capture must contain visible pixels", visiblePixel)
        assertTrue("Native host capture must contain more than a flat background", differentPixel)
    }

    private companion object {
        const val VIEWPORT = "host_compose_viewport"
        val LONG_ACCOUNT = "合成帳號狀態：這段文字只測試換行與可捲動性，不是實際登入結果。".repeat(6)
        val LONG_RIDE = "合成連線狀態：未啟動服務、未連接車輛，請以真實觀測結果為準。".repeat(6)
    }
}
