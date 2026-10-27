package org.openridesync.app.pairing

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.navigation.MainTab
import org.openridesync.app.ui.MainTabShell
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production lifecycle binding and tab shell, with callbacks instead of Bluetooth or auth. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PairingTabLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    @Test fun resumedOwnerStartsOnceAndDepartureStopsWithLatestCallback() {
        val owner = Owner()
        val visible = mutableStateOf(true)
        val replacement = mutableStateOf(false)
        var starts = 0
        var stops = 0
        compose.runOnIdle { owner.resume() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) {
                    val current = replacement.value
                    PairingLifecycle({ starts++ }, { stops += if (current) 10 else 1 })
                }
            }
        }
        compose.runOnIdle {
            // Adding an observer to a resumed LifecycleRegistry replays ON_START itself.
            assertEquals(1, starts)
            replacement.value = true
        }
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(0, stops)
            visible.value = false
        }
        compose.runOnIdle {
            assertEquals(10, stops)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(10, stops) // Disposal removed the observer before the Activity stopped.
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            visible.value = true
        }
        compose.runOnIdle {
            assertEquals(2, starts)
            assertEquals(10, stops)
        }
    }

    @Test fun ownerReplacementStopsOldBindingAndStartsNewBindingWithoutNavigation() {
        val original = Owner()
        val replacement = Owner()
        val owner = mutableStateOf<LifecycleOwner>(original)
        var starts = 0
        var stops = 0
        compose.runOnIdle { original.resume(); replacement.resume() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner.value) {
                PairingLifecycle({ starts++ }, { stops++ })
            }
        }
        compose.runOnIdle {
            assertEquals(1, starts)
            original.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(0, stops) // A chooser or permission prompt may only pause the Activity.
            original.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(1, stops)
            owner.value = replacement
        }
        compose.runOnIdle {
            // Stop and disposal can both stop observation. Neither is a navigation clear.
            assertEquals(2, stops)
            assertEquals(2, starts)
            original.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            assertEquals(2, stops)
        }
    }

    @Test fun activityStopDoesNotSelectTabButExplicitTabDepartureDisposesPairing() {
        val owner = Owner()
        val routes = mutableStateOf(listOf("home", "vehicles"))
        var starts = 0
        var stops = 0
        var selections = 0
        compose.runOnIdle { owner.resume() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    MainTabShell(routes.value, onSelect = {
                        selections++
                        routes.value = MainTab.select(routes.value, it)
                    }) {
                        if (routes.value.last() == "vehicles") PairingLifecycle({ starts++ }, { stops++ })
                    }
                }
            }
        }
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(0, selections)
            assertEquals(1, stops)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(2, starts)
        }
        compose.onNodeWithTag("main_tab_settings").performClick()
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(2, stops)
        }
        compose.onNodeWithTag("main_tab_vehicles").performClick()
        compose.runOnIdle {
            assertEquals(2, selections)
            assertEquals(3, starts)
            assertEquals(2, stops)
        }
    }
}
