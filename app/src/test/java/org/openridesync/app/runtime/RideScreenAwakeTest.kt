package org.openridesync.app.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.BatteryManager
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RideScreenAwakeTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun Window.awake() = attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test fun onlyAResumedConnectedOwnerCanHoldTheFlagAndDisposalReleasesIt() {
        val owner = Owner()
        val connected = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val mode = mutableStateOf(ScreenAwakeMode.ON)
        lateinit var window: Window
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            window = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().first().window
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) RideScreenAwake(window, connected.value, mode.value)
            }
        }
        compose.runOnIdle { assertFalse(window.awake()); connected.value = true }
        compose.runOnIdle {
            assertTrue(window.awake())
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertFalse(window.awake())
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertTrue(window.awake())
            connected.value = false
        }
        compose.runOnIdle { assertFalse(window.awake()); connected.value = true; mode.value = ScreenAwakeMode.OFF }
        compose.runOnIdle { assertFalse(window.awake()); mode.value = ScreenAwakeMode.ON }
        compose.runOnIdle { assertTrue(window.awake()); visible.value = false }
        compose.runOnIdle {
            assertFalse(window.awake())
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertFalse(window.awake())
        }
    }

    @Test fun chargingUsesInitialStatusAndPowerEventsAndOldReceiversCannotReviveIt() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL))
        val mode = mutableStateOf(ScreenAwakeMode.ONLY_CHARGING)
        val connected = mutableStateOf(true)
        lateinit var window: Window
        compose.setContent {
            window = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().first().window
            RideScreenAwake(window, connected.value, mode.value)
        }
        compose.runOnIdle { assertTrue(window.awake()); context.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED)) }
        compose.runOnIdle { assertFalse(window.awake()); context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED)) }
        compose.runOnIdle { assertTrue(window.awake()); connected.value = false }
        compose.runOnIdle { context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED)) }
        compose.runOnIdle {
            assertFalse(window.awake())
            context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_STATUS, -1))
            connected.value = true
        }
        compose.runOnIdle { assertFalse(window.awake()); mode.value = ScreenAwakeMode.ON }
        compose.runOnIdle { assertTrue(window.awake()); mode.value = ScreenAwakeMode.OFF }
        compose.runOnIdle { context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED)) }
        compose.runOnIdle { assertFalse(window.awake()) }
    }
}
