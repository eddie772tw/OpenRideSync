package org.openridesync.app.runtime

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class RidePreviewLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reconnectPreviewHidesVehicleActionsAndCanRestoreSyntheticSabWithoutHostActions() {
        var actions = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { actions++ }, { actions++ }) } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_page_MUSIC").performScrollTo().performClick()
        compose.onNodeWithTag("preview_stage_RECONNECTING").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_status").assertTextEquals("正在重新連接車輛…")
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_music_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_request_sab").assertDoesNotExist()
        compose.onNodeWithTag("ride_disconnect").assertIsEnabled()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_stage_CONNECTED").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.onNodeWithTag("ride_current_function").assertTextEquals("音樂")
        compose.onNodeWithTag("ride_music_state").assertExists()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun sidecardStartsCollapsedAndNeverResizesOrCoversTheWholePreview() {
        var actions = 0
        compose.setContent { OpenRideSyncTheme { RidePreviewScreen({}, { actions++ }, { actions++ }) } }
        compose.onNodeWithTag("preview_controls_panel").assertDoesNotExist()
        val display = compose.onNodeWithTag("preview_display").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        val frame = compose.onNodeWithTag("preview_frame").getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("preview_controls_panel").getUnclippedBoundsInRoot()
        assertEquals(display, compose.onNodeWithTag("preview_display").getUnclippedBoundsInRoot())
        assertTrue(panel.right - panel.left < (frame.right - frame.left) * .80f &&
            panel.bottom - panel.top < (frame.bottom - frame.top) * .85f)
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_controls_panel").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_switch_hint").assertIsDisplayed()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Config(qualifiers = "w640dp-h360dp-mdpi")
    @Test fun largeTextLandscapeKeepsTheCloseControlVisibleAndOptionsScrollable() {
        var actions = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            OpenRideSyncTheme { Box(Modifier.requiredSize(640.dp, 360.dp)) {
                RidePreviewScreen({}, { actions++ }, { actions++ })
            } }
        } }
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_start_weather_test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("preview_controls_toggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("preview_controls_panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }
}
