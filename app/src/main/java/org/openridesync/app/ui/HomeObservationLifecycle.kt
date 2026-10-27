package org.openridesync.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Binds home readers to foreground lifetime, including cancellation when leaving home. */
@Composable
internal fun HomeObservationLifecycle(
    onCheckAccess: () -> Unit,
    onStartMedia: () -> Unit,
    onStopMedia: () -> Unit,
    restartKey: Any? = null,
) {
    val owner = LocalLifecycleOwner.current
    val checkAccess by rememberUpdatedState(onCheckAccess)
    val startMedia by rememberUpdatedState(onStartMedia)
    val stopMedia by rememberUpdatedState(onStopMedia)
    DisposableEffect(owner, restartKey) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { checkAccess(); startMedia() }
                Lifecycle.Event.ON_STOP -> stopMedia()
                else -> Unit
            }
        }
        // A started owner replays ON_START on registration; a manual start would duplicate it.
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            // Stops are intentionally idempotent: disposal can follow an Activity ON_STOP.
            stopMedia()
        }
    }
}
