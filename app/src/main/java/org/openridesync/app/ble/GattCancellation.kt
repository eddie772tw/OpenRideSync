package org.openridesync.app.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.util.Collections
import java.util.IdentityHashMap

/** Channel cancellation can gain extra cancellation wrappers through coroutine stacktrace recovery. */
internal suspend fun CancellationException.gattFailureCause(): GattFailure? {
    currentCoroutineContext().ensureActive() // A cancelled caller must remain cancelled.
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current is CancellationException && current !is TimeoutCancellationException && visited.add(current)) {
        current = current.cause
    }
    return current as? GattFailure
}

/** Preserve a typed transport error through channel/coroutine wrappers without inferring from codes. */
internal suspend fun Exception.gattFailureOrNull(): GattFailure? = when (this) {
    is GattFailure -> this
    is CancellationException -> if (currentCoroutineContext().isActive) gattFailureCause() else null
    else -> null
}
