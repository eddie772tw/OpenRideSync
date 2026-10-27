package org.openridesync.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production binding with callback records, not Android media services or RPCs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HomeObservationLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeHistoryReadStartsOnceInForegroundAndCancelsOnLeaving() {
        val owner = Owner()
        val visible = mutableStateOf(true)
        val reads = mutableListOf<String>()
        compose.runOnIdle { owner.resume() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) HomeObservationLifecycle({}, { reads += "refresh-history" },
                    { reads += "cancel-history" })
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("refresh-history"), reads)
            visible.value = false
        }
        compose.runOnIdle {
            assertEquals(listOf("refresh-history", "cancel-history"), reads)
            owner.stop(); owner.resume()
            assertEquals(listOf("refresh-history", "cancel-history"), reads)
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun stop() {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    @Test fun resumedOwnerReplaysOnceAndRecompositionUsesLatestCallbacks() {
        val owner = Owner()
        val version = mutableStateOf(0)
        val calls = mutableListOf<String>()
        compose.runOnIdle { owner.resume() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val current = version.value
                HomeObservationLifecycle({ calls += "access$current" },
                    { calls += "start$current" }, { calls += "stop$current" })
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("access0", "start0"), calls)
            version.value = 1
        }
        compose.runOnIdle { version.value = 2 }
        compose.runOnIdle {
            assertEquals(listOf("access0", "start0"), calls)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(listOf("access0", "start0"), calls)
            owner.stop()
            owner.resume()
            assertEquals(listOf("access0", "start0", "stop2", "access2", "start2"), calls)
        }
    }

    @Test fun stoppedOwnerWaitsForForegroundAndDisposalRemovesObserverBeforeReentry() {
        val owner = Owner()
        val visible = mutableStateOf(true)
        val calls = mutableListOf<String>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) HomeObservationLifecycle({ calls += "access" },
                    { calls += "start" }, { calls += "stop" })
            }
        }
        compose.runOnIdle {
            assertEquals(emptyList<String>(), calls)
            owner.resume()
            assertEquals(listOf("access", "start"), calls)
            visible.value = false
        }
        compose.runOnIdle {
            assertEquals(listOf("access", "start", "stop"), calls)
            owner.stop()
            owner.resume()
            assertEquals(listOf("access", "start", "stop"), calls)
            visible.value = true
        }
        compose.runOnIdle {
            assertEquals(listOf("access", "start", "stop", "access", "start"), calls)
            owner.stop()
            visible.value = false
        }
        compose.runOnIdle {
            // Both the background event and later removal perform an idempotent stop.
            assertEquals(listOf("access", "start", "stop", "access", "start", "stop", "stop"), calls)
        }
    }

    @Test fun restartKeyRebindsOnceAndWaitsWhenBackgrounded() {
        val owner = Owner()
        val signedIn = mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.runOnIdle { owner.resume() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HomeObservationLifecycle({ calls += "access" }, { calls += "start" },
                    { calls += "stop" }, restartKey = signedIn.value)
            }
        }
        compose.runOnIdle { signedIn.value = true }
        compose.runOnIdle {
            assertEquals(listOf("access", "start", "stop", "access", "start"), calls)
            owner.stop()
            calls.clear()
            signedIn.value = false
        }
        compose.runOnIdle {
            assertEquals(listOf("stop"), calls)
            owner.resume()
            assertEquals(listOf("stop", "access", "start"), calls)
        }
    }

    @Test fun replacementOwnerDetachesOldObserverAndReplaysNewOwner() {
        val original = Owner()
        val replacement = Owner()
        val owner = mutableStateOf<LifecycleOwner>(original)
        val calls = mutableListOf<String>()
        compose.runOnIdle { original.resume(); replacement.resume() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner.value) {
                HomeObservationLifecycle({ calls += "access" }, { calls += "start" }, { calls += "stop" })
            }
        }
        compose.runOnIdle { owner.value = replacement }
        compose.runOnIdle {
            assertEquals(listOf("access", "start", "stop", "access", "start"), calls)
            calls.clear()
            original.stop()
            original.resume()
            assertEquals(emptyList<String>(), calls)
            replacement.stop()
            assertEquals(listOf("stop"), calls)
        }
    }
}
