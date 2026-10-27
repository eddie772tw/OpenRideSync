package org.openridesync.app.runtime

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.openridesync.app.weather.*
import org.openridesync.honda.wire.WeatherWire
import org.openridesync.app.ble.Gen1Input
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class RideStatusBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun weatherFocusOnlySelectsItsMetricAndNeverDispatchesWithoutAnExplicitClick() {
        val selected = mutableStateOf(false)
        var weatherOpens = 0
        var permissionOpens = 0
        compose.setContent { OpenRideSyncTheme {
            RideStatusLayout(RideDeviceStatus(), onWeather = { weatherOpens++ },
                onPermissions = { permissionOpens++ }, weatherSelected = selected.value) {}
        } }
        compose.onNodeWithTag("ride_status_weather").assertIsNotSelected().assertIsEnabled()
        for (focus in listOf(true, false)) {
            compose.runOnIdle { selected.value = focus }
            val weather = compose.onNodeWithTag("ride_status_weather")
            if (focus) weather.assertIsSelected() else weather.assertIsNotSelected()
            listOf("battery", "mobile", "wifi").forEach {
                compose.onNodeWithTag("ride_status_$it").assertIsNotSelected()
            }
            val icon = compose.onNodeWithTag("ride_status_weather_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(48.dp, icon.right - icon.left)
            assertEquals(48.dp, icon.bottom - icon.top)
            compose.runOnIdle { assertEquals(0, weatherOpens); assertEquals(0, permissionOpens) }
        }
        compose.onNodeWithTag("ride_status_weather").performClick()
        compose.runOnIdle { assertEquals(1, weatherOpens); assertEquals(0, permissionOpens) }
    }

    @Test fun observedWeatherAndPhoneMetricsAreReadableAndHuWeatherCannotDispatch() {
        val interactive = mutableStateOf(true)
        var opens = 0
        var permissions = 0
        val status = mutableStateOf(RideDeviceStatus(73, true, RideSignal(SignalAvailability.READY, 3, "合成電信"),
            RideSignal(SignalAvailability.READY, 4, "Synthetic-Network-Name-Long"), true))
        val weather = HomeWeatherState(signedIn = true, locationGranted = true,
            current = CurrentWeather(WeatherWire.Weather.getDefaultInstance(), WeatherReading(28f, 803, "Place", "多雲", "Clouds", true)),
            fetchedAtMillis = 1_760_000_000_000)
        compose.setContent { OpenRideSyncTheme { RideStatusBar(status.value, weather,
            onWeather = if (interactive.value) ({ opens++ }) else null,
            onPermissions = if (interactive.value) ({ permissions++ }) else null) } }
        compose.onNodeWithTag("ride_status_battery").assertContentDescriptionEquals("手機・充電中，73%")
        compose.onNodeWithTag("ride_status_mobile").assertContentDescriptionEquals("行動訊號，合成電信，3/4")
        compose.onNodeWithTag("ride_status_wifi").assertContentDescriptionEquals("Wi‑Fi，Synthetic-Network-Name-Long，4/4")
        compose.onNodeWithText("合成電信").assertExists()
        compose.onNodeWithText("Synthetic-Network-Name-Long").assertExists()
        compose.onNodeWithText("28°C").assertExists()
        compose.onNodeWithText("手機電量").assertDoesNotExist()
        compose.onNodeWithText("行動訊號").assertDoesNotExist()
        compose.onNodeWithText("3/4").assertDoesNotExist()
        compose.onNodeWithText("4/4").assertDoesNotExist()
        compose.onNodeWithText("多雲").assertDoesNotExist()
        for (tag in listOf("weather", "battery", "mobile", "wifi")) {
            val icon = compose.onNodeWithTag("ride_status_${tag}_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(48.dp, icon.right - icon.left)
            assertEquals(48.dp, icon.bottom - icon.top)
            val value = when (tag) { "weather" -> "28°C"; "battery" -> "73%"; "mobile" -> "合成電信"; else -> "Synthetic-Network-Name-Long" }
            val text = compose.onNodeWithText(value, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(text.left >= icon.right)
        }
        compose.onNodeWithTag("ride_status_weather").assertContentDescriptionContains("上次更新", substring = true)
        compose.onNodeWithTag("ride_status_weather").performClick()
        compose.runOnIdle { assertEquals(1, opens); interactive.value = false }
        compose.onNodeWithTag("ride_status_weather").assertIsNotEnabled()
        compose.runOnIdle { status.value = RideDeviceStatus(12, false, RideSignal(SignalAvailability.NO_SIM), RideSignal(SignalAvailability.OFF), false) }
        compose.onNodeWithTag("ride_status_battery").assertContentDescriptionEquals("手機・低電量，12%")
        compose.onNodeWithTag("ride_status_offline").assertExists()
        compose.onNodeWithTag("ride_status_mobile").assertContentDescriptionEquals("行動訊號，無 SIM")
        compose.onNodeWithTag("ride_status_wifi").assertContentDescriptionEquals("Wi‑Fi，已關閉")
        compose.runOnIdle { status.value = RideDeviceStatus(mobile = RideSignal(SignalAvailability.READY),
            wifi = RideSignal(SignalAvailability.READY, 5)) }
        compose.onNodeWithTag("ride_status_mobile").assertContentDescriptionEquals("行動訊號，強度未知")
        compose.onNodeWithText("強度未知").assertDoesNotExist()
        compose.runOnIdle { status.value = status.value.copy(wifi = RideSignal(SignalAvailability.READY, 4,
            nameIssue = SignalNameIssue.PERMISSION_REQUIRED)) }
        compose.onNodeWithTag("ride_status_wifi").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, permissions); interactive.value = true }
        compose.onNodeWithTag("ride_status_wifi").performClick()
        compose.runOnIdle { assertEquals(1, permissions) }
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun largeTextWrapsAllMetricsAndUnknownSourcesDoNotClaimZeroOrSunnyWeather() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            OpenRideSyncTheme { RideStatusBar(RideDeviceStatus(-1), HomeWeatherState(signedIn = true, locationGranted = true)) }
        } }
        for (tag in listOf("weather", "battery", "mobile", "wifi"))
            compose.onNodeWithTag("ride_status_$tag").assertIsDisplayed()
        compose.onNodeWithTag("ride_status_battery").assertContentDescriptionEquals("手機電量，未知")
        compose.onNodeWithTag("ride_status_mobile").assertContentDescriptionEquals("行動訊號，未知")
        compose.onNodeWithTag("ride_status_wifi").assertContentDescriptionEquals("Wi‑Fi，未知")
        compose.onNodeWithText("未更新").assertExists()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithText("0°C").assertDoesNotExist()
    }

    @Config(qualifiers = "w320dp-h320dp-mdpi")
    @Test fun weatherFocusReturnsScrolledSmallStatusViewportToTheTopWithoutDispatching() {
        val selected = mutableStateOf(false)
        var weatherOpens = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            OpenRideSyncTheme {
                RideStatusLayout(RideDeviceStatus(), onWeather = { weatherOpens++ }, weatherSelected = selected.value) {
                    Box(Modifier.fillMaxSize().testTag("weather_focus_body"), contentAlignment = Alignment.Center) {
                        Button(onClick = {}, modifier = Modifier.testTag("weather_focus_body_action")) { Text("下方操作") }
                    }
                }
            }
        } }
        compose.onNodeWithTag("ride_status_wifi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ride_status_weather").assertIsNotDisplayed()
        compose.runOnIdle { selected.value = true }
        compose.onNodeWithTag("ride_status_weather").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("weather_focus_body_action").assertIsDisplayed().assertIsEnabled()
        val viewport = compose.onNodeWithTag("ride_status_viewport").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("weather_focus_body").getUnclippedBoundsInRoot()
        assertTrue(body.bottom - body.top > viewport.bottom - viewport.top)
        compose.runOnIdle { assertEquals(0, weatherOpens) }
    }

    @Config(qualifiers = "w320dp-h320dp-mdpi")
    @Test fun shortLargeTextWindowKeepsHuRecoveryReachableBelowTheStatusRow() {
        var requests = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            OpenRideSyncTheme { RideStatusLayout(RideDeviceStatus()) {
                RidePhoneContent(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_HU),
                    onRequestSab = { requests++ }) { error("HU must stay locked") }
            } }
        } }
        val status = compose.onNodeWithTag("ride_status_viewport").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("ride_phone_locked").getUnclippedBoundsInRoot()
        assertTrue(body.bottom - body.top > status.bottom - status.top)
        compose.onNodeWithTag("ride_phone_request_sab").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("ride_status_wifi").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
    }
}
