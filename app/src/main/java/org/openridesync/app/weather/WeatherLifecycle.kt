package org.openridesync.app.weather

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Shared by the Compose owner and host lifecycle tests; activation never creates query intent. */
internal fun bindWeatherLifecycle(lifecycle: Lifecycle, model: WeatherViewModel): () -> Unit {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> model.onStart()
            Lifecycle.Event.ON_STOP -> model.onStop()
            Lifecycle.Event.ON_RESUME -> model.refreshAccess()
            else -> Unit
        }
    }
    lifecycle.addObserver(observer)
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onStart()
    return { lifecycle.removeObserver(observer); model.onStop() }
}
