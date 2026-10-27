package org.openridesync.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.media.session.PlaybackState
import org.openridesync.app.media.*
import androidx.compose.runtime.mutableStateOf

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w430dp-h932dp-mdpi")
class HomeOverviewContentTest {
    @get:Rule val compose = createComposeRule()
    private var opened = ""
    private var dismissed = false
    private val signedOut = ShellStatus(false, "尚未登入", "尚未連線")
    @Test fun mediaControlsUseExactTargetAndReportedActionsWithoutOpeningMusic() {
        val item = MediaSessionChoice("target", "Player", "synthetic.player", "Song", MediaPlayback.PLAYING,
            artist = "Artist", album = "Album", actions = PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
        val state = mutableStateOf(MediaPageState(signedIn = true, observing = true,
            media = MediaState(MediaAccess.GRANTED, listOf(item))))
        val requests = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            HomeOverviewContent(signedOut.copy(signedIn = true), homeOverview(signedOut.copy(signedIn = true), media = state.value),
                { opened = it }, onMediaPrevious = { requests.add("previous:$it") },
                onMediaToggle = { requests.add("toggle:$it") }, onMediaNext = { requests.add("next:$it") })
        } }
        compose.onNodeWithText("Song").performScrollTo().assertExists()
        compose.onNodeWithText("Artist · Album · Player · 播放中").assertExists()
        compose.onNodeWithTag("home_media_previous").assertIsNotEnabled()
        compose.onNodeWithTag("home_media_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("home_media_next").performScrollTo().performClick()
        assertEquals(listOf("toggle:target", "next:target"), requests)
        assertEquals("", opened)
        compose.runOnIdle { state.value = state.value.copy(observing = false) }
        compose.onNodeWithTag("home_media_toggle").assertDoesNotExist()
    }
    private fun render(status: ShellStatus = signedOut, width: Int = 390, scale: Float = 1f, hint: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width.dp, 844.dp), propagateMinConstraints = true) {
                        HomeOverviewContent(status, homeOverview(status), { opened = it },
                            showFirstPairHint = hint, onDismissFirstPairHint = { dismissed = true })
                    }
                }
            }
        }
    }
    @Test fun signedOutFeatureActionsRouteToAccount() {
        render()
        listOf("weather", "vehicles", "media", "trips", "messages", "calls").forEach { route ->
            compose.onNodeWithTag("home_$route").performScrollTo().performClick()
            assertEquals("account", opened)
        }
    }
    @Test fun functionalEntriesRetainTheirRoutes() {
        render(signedOut.copy(signedIn = true))
        listOf("weather", "vehicles", "media", "trips", "messages", "calls", "ride", "setup", "settings", "diagnostics").forEach { route ->
            compose.onNodeWithTag("home_$route").performScrollTo().performClick()
            assertEquals(route, opened)
        }
    }
    @Test fun unknownGarageNeverClaimsThereIsNoPrimaryVehicle() {
        render()
        compose.onNodeWithText("主要車輛").assertExists()
        compose.onNodeWithText("尚無主要車輛").assertDoesNotExist()
        compose.onNodeWithText("登入後查看已配對車輛").assertDoesNotExist()
    }
    @Test fun tilesOnlyShowAccountNameWeatherAndRecentFacts() {
        compose.setContent { MaterialTheme {
            HomeOverviewContent(ShellStatus(true, "私人帳號說明", "已連線"), HomeOverview(
                weather = HomeFact("23°C", "不顯示的時間與說明"), weatherPlace = "臺北",
                weatherKind = org.openridesync.app.weather.WeatherKind.SUNNY,
                trip = HomeFact("12.3 km", "不顯示的騎乘說明"), tripDate = "2026-10-19",
                garageHasNoPrimary = true), { opened = it }, accountName = "測試帳號")
        } }
        compose.onNodeWithText("測試帳號").assertExists()
        compose.onNodeWithText("已登入").assertDoesNotExist()
        compose.onNodeWithText("私人帳號說明").assertDoesNotExist()
        compose.onNodeWithText("23°C").assertExists()
        compose.onNodeWithText("臺北").assertExists()
        val icon = compose.onNodeWithTag("home_weather_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val temperature = compose.onNodeWithText("23°C", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val place = compose.onNodeWithText("臺北", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(temperature.left >= icon.right)
        assertEquals(temperature.left, place.left)
        assertTrue(place.top >= temperature.bottom)
        compose.onNodeWithText("不顯示的時間與說明").assertDoesNotExist()
        compose.onNodeWithText("尚未設定").assertExists()
        compose.onNodeWithText("12.3 km").performScrollTo().assertExists()
        compose.onNodeWithText("不顯示的騎乘說明").assertDoesNotExist()
        compose.onNodeWithText("連線狀態不等同於實車測試就緒。").assertDoesNotExist()
        compose.onNodeWithTag("home_trips").performScrollTo().performClick()
        assertEquals("trips", opened)
    }
    @Test fun compactNormalTextUsesAsymmetricPairs() {
        render()
        compose.onAllNodesWithTag("home_tile_pair").assertCountEquals(2)
        compose.onNodeWithTag("home_vehicle_background", useUnmergedTree = true).assertExists()
    }
    @Test fun largeTextStacksTilesAndKeepsLastEntryReachable() {
        render(scale = 1.6f)
        compose.onAllNodesWithTag("home_tile_pair").assertCountEquals(0)
        compose.onNodeWithTag("home_diagnostics").performScrollTo().assertIsDisplayed()
    }
    @Test fun firstPairBubbleOnlyNavigatesOrDismisses() {
        render(signedOut.copy(signedIn = true), hint = true)
        compose.onNodeWithTag("home_first_pair_open").performScrollTo().performClick()
        assertEquals("vehicles", opened)
        opened = ""
        compose.onNodeWithTag("home_first_pair_dismiss").performScrollTo().performClick()
        assertTrue(dismissed)
        assertEquals("", opened)
    }
    @Test fun firstPairBubbleAbsentByDefault() {
        render()
        compose.onNodeWithTag("home_first_pair_hint").assertDoesNotExist()
    }
    @Test fun narrowScreenStacksTiles() {
        render(width = 320)
        compose.onAllNodesWithTag("home_tile_pair").assertCountEquals(0)
        compose.onNodeWithTag("home_settings").performScrollTo().assertIsDisplayed()
    }
}
