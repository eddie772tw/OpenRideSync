package org.openridesync.app.runtime

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.media.MediaState
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/** Synthetic production rendering only: callbacks are counters, with no BLE, account or media owner. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideRidingMenuTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View
    private val customOrder = listOf(MainMenuSelection.Item.MUSIC, MainMenuSelection.Item.MESSAGE,
        MainMenuSelection.Item.VOLUME, MainMenuSelection.Item.NAVIGATION, MainMenuSelection.Item.CALL)
    private val menu = RidePageState(RidePageStage.MENU, receiptStatus = 1,
        menuItems = customOrder, menuSelected = MainMenuSelection.Item.MESSAGE)

    @Test fun theRightHalfOfTheCoverRetainsItsOriginalColor() {
        val artwork = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        compose.setContent { OpenRideSyncTheme {
            val view = LocalView.current
            SideEffect { hostView = view }
            DisposableEffect(artwork) { onDispose { artwork.recycle() } }
            RideRidingMenu(menu.copy(menuItems = listOf(MainMenuSelection.Item.MUSIC), menuSelected = MainMenuSelection.Item.MUSIC),
                MediaState(artwork = artwork), {})
        } }
        compose.waitForIdle()
        val cover = compose.onNodeWithTag("ride_music_artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val image = compose.runOnIdle { Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also {
            hostView.draw(Canvas(it))
        } }
        try {
            listOf(.55f, .75f, .9f).forEach { fraction ->
                assertEquals("Right half must have no gradient tint", android.graphics.Color.WHITE,
                    image.getPixel((cover.left + cover.width * fraction).toInt(), (cover.bottom - 10).toInt()))
            }
        } finally { image.recycle() }
    }

    @Test fun observedHandlebarFocusScrollsTheSelectedFunctionIntoViewWithoutDispatching() {
        val page = mutableStateOf(menu.copy(menuItems = MainMenuSelection.Item.entries,
            menuSelected = MainMenuSelection.Item.NAVIGATION))
        var actions = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
            OpenRideSyncTheme { Box(Modifier.requiredSize(320.dp, 600.dp), propagateMinConstraints = true) {
                RideContent(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB),
                    false, null, page.value, { actions++ }, {}, {}, {}, {}, {}, onFunction = { actions++ })
            } }
        } }
        listOf(MainMenuSelection.Item.VOLUME, MainMenuSelection.Item.MUSIC, MainMenuSelection.Item.NAVIGATION).forEach { item ->
            compose.runOnIdle { page.value = page.value.copy(menuSelected = item) }
            compose.waitForIdle()
            compose.onNodeWithTag("ride_function_${item.name}").assertIsSelected().assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun musicCoverPersistsWhileFocusExpandsDetailsAndVolumeOnlyShowsARealFocusedValue() {
        val artwork = syntheticRideArtwork()
        val media = mutableStateOf(MediaState(title = "合成曲目", album = "合成專輯", artist = "合成演出者", artwork = artwork))
        val page = mutableStateOf(menu.copy(menuSelected = MainMenuSelection.Item.VOLUME, volumePercentage = 60))
        var actions = 0
        compose.setContent { OpenRideSyncTheme {
            DisposableEffect(artwork) { onDispose { artwork.recycle() } }
            RideRidingMenu(page.value, media.value, { actions++ })
        } }
        val normal = compose.onNodeWithTag("ride_function_MUSIC").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("ride_music_artwork", useUnmergedTree = true).assertExists()
        listOf("title", "album", "artist").forEach {
            compose.onNodeWithTag("ride_music_$it", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("ride_volume_percentage", useUnmergedTree = true).assertTextEquals("60%")
        compose.runOnIdle { page.value = page.value.copy(menuSelected = MainMenuSelection.Item.MUSIC) }
        val focused = compose.onNodeWithTag("ride_function_MUSIC").getUnclippedBoundsInRoot()
        assertTrue(focused.bottom - focused.top >= 240.dp)
        assertTrue(focused.bottom - focused.top >= (normal.bottom - normal.top) * 2)
        compose.onNodeWithTag("ride_music_artwork", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ride_music_title", useUnmergedTree = true).assertTextEquals("合成曲目")
        compose.onNodeWithTag("ride_music_album", useUnmergedTree = true).assertTextEquals("專輯：合成專輯")
        compose.onNodeWithTag("ride_music_artist", useUnmergedTree = true).assertTextEquals("演出者：合成演出者")
        compose.onNodeWithTag("ride_volume_percentage", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            page.value = page.value.copy(menuSelected = MainMenuSelection.Item.VOLUME, volumePercentage = null)
            media.value = media.value.copy(artwork = null)
        }
        val restored = compose.onNodeWithTag("ride_function_MUSIC").getUnclippedBoundsInRoot()
        assertEquals(normal.bottom - normal.top, restored.bottom - restored.top)
        compose.onNodeWithTag("ride_music_artwork", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("ride_volume_percentage", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun previewWeatherAndSidecardSelectionsKeepTheirMainMenuFocusWithoutRealActions() {
        var actions = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { actions++ }, { actions++ }) } }
        compose.onNodeWithTag("ride_status_weather").performScrollTo().performClick()
        compose.onNodeWithTag("ride_main_menu").performScrollTo().performClick()
        compose.onNodeWithTag("ride_status_weather").assertIsSelected()
        compose.onNodeWithTag("ride_function_WEATHER").assertDoesNotExist()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_page_MUSIC").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_MENU").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_function_MUSIC").assertIsSelected()
        compose.onNodeWithTag("ride_music_artwork", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ride_status_weather").assertIsNotSelected()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_page_WEATHER").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_MENU").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_status_weather").assertIsSelected()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun focusedMusicKeepsItsWidthAndCanGrowForLargeTextOnANarrowScreen() {
        val artwork = syntheticRideArtwork()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            OpenRideSyncTheme {
                DisposableEffect(artwork) { onDispose { artwork.recycle() } }
                Box(Modifier.requiredSize(320.dp, 640.dp).verticalScroll(rememberScrollState())) {
                    RideRidingMenu(menu.copy(menuItems = listOf(MainMenuSelection.Item.MUSIC),
                        menuSelected = MainMenuSelection.Item.MUSIC),
                        MediaState(title = "合成長曲名測試文字", album = "合成專輯測試文字", artist = "合成演出者測試文字",
                            artwork = artwork), {})
                }
            }
        } }
        val row = compose.onNodeWithTag("ride_function_MUSIC").getUnclippedBoundsInRoot()
        assertEquals(320.dp, row.right - row.left)
        assertTrue(row.bottom - row.top >= 240.dp)
        val cover = compose.onNodeWithTag("ride_music_artwork", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(cover.right - cover.left <= 240.dp)
        listOf("title", "album", "artist").forEach {
            val text = compose.onNodeWithTag("ride_music_$it", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(text.left >= row.left && text.right <= row.right && text.bottom <= row.bottom)
        }
    }

    @Composable private fun MenuFixture(state: RideState, onFunction: (MainMenuSelection.Item) -> Unit,
        unexpectedAction: () -> Unit = {}) {
        RideContent(state, false, null, menu, unexpectedAction, unexpectedAction, unexpectedAction,
            unexpectedAction, unexpectedAction, unexpectedAction, onPermissions = unexpectedAction,
            onFunction = onFunction)
    }

    @Test fun liveSabMenuUsesProviderOrderFocusAndOnlyDispatchesTheTappedItem() {
        val requested = mutableListOf<MainMenuSelection.Item>()
        var unexpected = 0
        compose.setContent { OpenRideSyncTheme {
            MenuFixture(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB),
                { requested += it }, { unexpected++ })
        } }
        val bounds = customOrder.map {
            val node = compose.onNodeWithTag("ride_function_${it.name}")
            node.assertIsEnabled().assertHasClickAction()
            if (it == menu.menuSelected) node.assertIsSelected() else node.assertIsNotSelected()
            node.getUnclippedBoundsInRoot().also { rect -> assertTrue(rect.bottom - rect.top >= 120.dp) }
        }
        bounds.zipWithNext().forEach { (first, second) -> assertTrue(first.top < second.top) }
        compose.onNodeWithTag("ride_function_WEATHER").assertDoesNotExist()
        compose.runOnIdle { assertTrue(requested.isEmpty()); assertEquals(0, unexpected) }
        customOrder.forEach { item ->
            compose.onNodeWithTag("ride_function_${item.name}").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(customOrder.take(requested.size), requested); assertEquals(0, unexpected) }
        }
        assertEquals(customOrder, requested)
    }

    @Test fun huAndUnknownModesDisableAllPhoneFunctionRows() {
        val state = mutableStateOf(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB))
        val requested = mutableListOf<MainMenuSelection.Item>()
        compose.setContent { OpenRideSyncTheme { MenuFixture(state.value, { requested += it }) } }
        for (mode in listOf(Gen1Input.Function.MODE_HU, null)) {
            compose.runOnIdle { state.value = state.value.copy(mode = mode) }
            customOrder.forEach { compose.onNodeWithTag("ride_function_${it.name}").assertIsNotEnabled() }
            compose.runOnIdle { assertTrue(requested.isEmpty()) }
        }
    }

    @Test fun menuHidesConnectionEngineeringDetailsUntilExplicitlyExpanded() {
        compose.setContent { OpenRideSyncTheme {
            MenuFixture(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB, rssi = -51), {})
        } }
        compose.onNodeWithText("藍牙訊號：-51 dBm").assertDoesNotExist()
        compose.onNodeWithText("車端已接受畫面指令，實際顯示請以儀表為準。").assertDoesNotExist()
        compose.onNodeWithTag("ride_status").assertDoesNotExist()
        compose.onNodeWithTag("ride_details_toggle").performScrollTo().performClick()
        compose.onNodeWithText("藍牙訊號：-51 dBm").assertExists()
        compose.onNodeWithText("車端已接受畫面指令，實際顯示請以儀表為準。").assertExists()
        compose.onNodeWithTag("ride_status").assertTextEquals("車輛模式：RoadSync")
    }

    @Test fun previewFunctionTapAndReturnKeepDeveloperControlsWithoutRealHostActions() {
        var realActions = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { realActions++ }, { realActions++ }) } }
        compose.runOnIdle { assertEquals(0, realActions) }
        compose.onNodeWithTag("ride_function_MUSIC").performScrollTo().performClick()
        compose.onNodeWithTag("ride_current_function").performScrollTo().assertTextEquals("音樂")
        compose.onNodeWithTag("ride_main_menu").performScrollTo().performClick()
        compose.onNodeWithTag("ride_function_menu").assertExists()
        compose.onNodeWithTag("ride_preview_banner").assertIsDisplayed()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_controls_panel").assertIsDisplayed()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("preview_controls_panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, realActions) }
    }

    @Test fun smallWidthKeepsTripsAndAccountEntriesReachableAndExplicit() {
        var trips = 0
        var accounts = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            OpenRideSyncTheme { Box(Modifier.requiredSize(320.dp, 640.dp), propagateMinConstraints = true) {
                RideContent(RideState(RideStage.CONNECTED, name = "合成車輛名稱", mode = Gen1Input.Function.MODE_SAB),
                    false, null, menu, {}, {}, {}, {}, {}, {}, onFunction = {},
                    onTrips = { trips++ }, onAccount = { accounts++ })
            } }
        } }
        compose.runOnIdle { assertEquals(0, trips); assertEquals(0, accounts) }
        compose.onNodeWithTag("ride_trips").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("ride_account").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, trips); assertEquals(1, accounts) }
    }

    @Test fun previewTripsAndAccountRemainLocalAndKeepTheSimulatedRoute() {
        var realActions = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({ realActions++ }, { realActions++ }, { realActions++ }) } }
        listOf("ride_trips", "ride_account").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNodeWithTag("ride_preview_banner").assertExists()
            compose.onNodeWithTag("ride_function_menu").assertExists()
            compose.runOnIdle { assertEquals(0, realActions) }
        }
    }

    @Config(qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
    @Test fun syntheticPhone390x844Screenshot() = previewScreenshot(390, 844, 1f)

    @Config(qualifiers = "zh-rTW-w932dp-h480dp-mdpi")
    @Test fun syntheticLandscape844x390LargeTextScreenshot() = previewScreenshot(844, 390, 1.5f)

    @Test fun syntheticFocusedMusicCoverScreenshot() = previewScreenshot(390, 844, 1f, "MUSIC")

    @Test fun syntheticFocusedVolumeScreenshot() = previewScreenshot(390, 844, 1f, "VOLUME")

    private fun previewScreenshot(width: Int, height: Int, fontScale: Float, focus: String? = null) {
        var realActions = 0
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                OpenRideSyncTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp).background(MaterialTheme.colorScheme.background)
                        .testTag("riding_menu_viewport"), propagateMinConstraints = true) {
                        RidePreviewScreen({}, { realActions++ }, { realActions++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("ride_preview_banner").assertExists()
        compose.onNodeWithTag("ride_function_menu").assertExists()
        compose.onNodeWithTag("preview_controls_toggle").assertIsDisplayed()
        if (focus != null) {
            compose.onNodeWithTag("preview_controls_toggle").performClick()
            compose.onNodeWithTag("preview_page_$focus").performScrollTo().performClick()
            compose.onNodeWithTag("preview_page_MENU").performScrollTo().performClick()
            compose.onNodeWithTag("preview_controls_close").performClick()
            compose.onNodeWithTag("ride_function_$focus").performScrollTo().assertIsSelected()
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("riding_menu_viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(width.toFloat(), bounds.width, .1f)
        assertEquals(height.toFloat(), bounds.height, .1f)
        val bitmap = compose.runOnIdle {
            assertEquals(0, realActions)
            assertTrue("Viewport must fit the host: $bounds in ${hostView.width}x${hostView.height}",
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= hostView.width && bounds.bottom <= hostView.height)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.translate(-bounds.left, -bounds.top)
                hostView.draw(canvas)
            }
        }
        try {
            val first = bitmap.getPixel(0, 0)
            var variation = false
            for (y in 0 until height step 3) for (x in 0 until width step 3) {
                val pixel = bitmap.getPixel(x, y)
                assertEquals("Clipped/transparent screenshot pixel $x,$y", 255, android.graphics.Color.alpha(pixel))
                variation = variation || pixel != first
            }
            assertTrue("Compose screenshot must contain rendered content", variation)
            val dir = File(System.getProperty("uiGalleryOutputDir") ?: "build/reports/ui-gallery")
            check(dir.isDirectory || dir.mkdirs())
            val name = "synthetic-riding-menu-${width}x${height}-font${(fontScale * 100).roundToInt()}" +
                (focus?.let { "-focus-${it.lowercase()}" } ?: "")
            File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(dir, "$name.fixture.txt").writeText("fixture=synthetic; shared production ride content in developer preview\n" +
                "profile=${width}x${height}; density=1; fontScale=$fontScale; focus=${focus ?: "NAVIGATION"}; artwork=synthetic-programmatic\n" +
                "testSource=app/src/test/java/org/openridesync/app/runtime/RideRidingMenuTest.kt\n" +
                "sourceVersion=${System.getProperty("uiGallerySourceVersion") ?: "not supplied"}\n")
        } finally { bitmap.recycle() }
    }
}
