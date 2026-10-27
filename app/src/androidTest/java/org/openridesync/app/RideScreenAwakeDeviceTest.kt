package org.openridesync.app

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.runtime.RideScreenAwake
import org.openridesync.app.runtime.ScreenAwakeMode
import org.openridesync.app.runtime.readRideDeviceStatus
import org.openridesync.app.runtime.SignalAvailability
import org.openridesync.app.runtime.RideStatusBar
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/** Real Window flags and battery snapshot, with synthetic connection/lifecycle only. No vehicle/media. */
class RideScreenAwakeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readOnlyPhoneSnapshotUsesTheRealBatteryAndWifiWithoutNetworkIdentifiers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val observed = readRideDeviceStatus(context)
        val capacity = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity in 0..100) {
            assertNotNull(observed.batteryPercent)
            assertTrue(kotlin.math.abs(observed.batteryPercent!! - capacity) <= 1)
        }
        if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY))
            assertEquals(SignalAvailability.UNSUPPORTED, observed.mobile.availability)
        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
        if (wifi?.isWifiEnabled == true) assertNotEquals(SignalAvailability.UNAVAILABLE, observed.wifi.availability)
        compose.setContent { OpenRideSyncTheme { RideStatusBar(observed) } }
        observed.wifi.name?.let {
            compose.onNodeWithTag("ride_status_wifi").assertContentDescriptionContains(it, substring = true)
        }
        context.cacheDir.resolve("test-ride-phone-status.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        context.cacheDir.resolve("test-ride-phone-status.json").writeText(JSONObject().apply {
            put("batteryPercent", observed.batteryPercent); put("charging", observed.charging)
            put("mobile", observed.mobile.availability.name); put("mobileLevel", observed.mobile.level)
            put("wifi", observed.wifi.availability.name); put("wifiLevel", observed.wifi.level)
            put("internet", observed.internet)
            put("wifiNameAvailable", observed.wifi.name != null); put("wifiNameIssue", observed.wifi.nameIssue?.name)
            put("carrierNameAvailable", observed.mobile.name != null)
        }.toString())
    }

    @Test fun foregroundConnectedFlagClearsOnPauseAndUsesTheActualChargingStatus() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val connected = mutableStateOf(false)
        val mode = mutableStateOf(ScreenAwakeMode.ON)
        val visible = mutableStateOf(true)
        lateinit var window: Window
        var charging = false
        fun awake() = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            val context = LocalContext.current
            window = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().first().window
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) RideScreenAwake(window, connected.value, mode.value)
            }
        }
        compose.runOnIdle { assertFalse(awake()); connected.value = true }
        compose.runOnIdle {
            assertTrue(awake())
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertFalse(awake())
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertTrue(awake())
            val activity = window.context
            val status = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            mode.value = ScreenAwakeMode.ONLY_CHARGING
        }
        compose.runOnIdle { assertEquals(charging, awake()); mode.value = ScreenAwakeMode.OFF }
        compose.runOnIdle { assertFalse(awake()); mode.value = ScreenAwakeMode.ON }
        compose.runOnIdle { assertTrue(awake()); visible.value = false }
        compose.runOnIdle { assertFalse(awake()) }
    }
}
