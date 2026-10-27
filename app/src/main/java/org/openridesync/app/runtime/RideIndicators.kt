package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.openridesync.app.ble.Gen1ControlExchange
import org.openridesync.app.ble.Gen1ControlPoint
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ble.Gen1InputMonitor

/** Only observed app state selects named bits. Unknown observations never assert an active flag. */
internal fun rideIndicatorMask(phone: Boolean, navigation: Boolean, music: Boolean, message: Boolean,
    muted: Boolean, internet: Boolean?): Int =
    (if (phone) Gen1ControlPoint.PHONE else 0) or
        (if (navigation) Gen1ControlPoint.NAVIGATION else 0) or
        (if (music) Gen1ControlPoint.MUSIC else 0) or
        (if (message) Gen1ControlPoint.MESSAGE else 0) or
        (if (muted) Gen1ControlPoint.MUTE else 0) or
        (if (internet == false) Gen1ControlPoint.NO_NETWORK else 0)

/** Uses the existing serial queue/allocator and leaves final clear to Gen1Shutdown.
 * The caller starts this only after authentication, and cancels it before draining control. */
internal suspend fun runRideIndicators(
    monitor: Gen1InputMonitor,
    controls: Gen1ControlExchange,
    capabilityBits: Int?,
    masks: StateFlow<Int>,
    ready: StateFlow<Boolean>,
    isCurrent: () -> Boolean,
): Nothing = coroutineScope {
    if (capabilityBits == null || capabilityBits and 1 == 0) awaitCancellation()
    var pending: Deferred<*>? = null
    try {
        // Revision changes invalidate a queued frame, including HU -> SAB with the same mask.
        kotlinx.coroutines.flow.combine(masks, monitor.mode, ready) { mask, mode, enabled ->
            Triple(mask, mode, enabled)
        }.collect { (mask, mode, enabled) ->
            pending?.cancel()
            if (enabled && isCurrent() && mode.function == Function.MODE_SAB) {
                pending = controls.submit(allowed = {
                    ready.value && isCurrent() && monitor.mode.value == mode && masks.value == mask
                }) { id -> Gen1ControlPoint.activeIndicators(id, mask) }
            }
        }
        awaitCancellation()
    } finally { pending?.cancel() }
}
