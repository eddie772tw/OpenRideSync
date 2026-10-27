package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openridesync.app.ble.Gen1ControlExchange
import org.openridesync.app.ble.Gen1ControlPoint
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ble.Gen1InputMonitor

internal enum class ModeRequestStage { IDLE, REQUESTING, WAITING_FOR_MODE, REJECTED, TIMED_OUT, UNAVAILABLE }

/** Explicit unknown/HU -> SAB request. Only real mode feedback enables SAB behavior. */
internal class RideModeSwitch(private val scope: CoroutineScope, private val monitor: Gen1InputMonitor,
    private val controls: Gen1ControlExchange, private val isCurrent: () -> Boolean) : AutoCloseable {
    private val mutableState = MutableStateFlow(ModeRequestStage.IDLE)
    val state = mutableState.asStateFlow()
    private var closed = false
    private var job: Job? = null
    private val modes = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        monitor.mode.collect { mode ->
            if (mode.function == Function.MODE_SAB) {
                job?.cancel()
                mutableState.value = ModeRequestStage.IDLE
            }
        }
    }

    private fun valid() = !closed && isCurrent() && monitor.mode.value.function != Function.MODE_SAB

    fun request(): Boolean {
        if (!valid()) {
            if (!closed && monitor.mode.value.function != Function.MODE_SAB) mutableState.value = ModeRequestStage.UNAVAILABLE
            return false
        }
        if (job?.isActive == true) return false
        mutableState.value = ModeRequestStage.REQUESTING
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var command: Deferred<Gen1ControlPoint.Receipt?>? = null
            try {
                withTimeout(10_000) {
                    command = controls.submit(allowed = ::valid, encode = Gen1ControlPoint::requestSabMode)
                    val receipt = command!!.await()
                    if (!valid()) return@withTimeout
                    if (receipt?.status() != 1) {
                        mutableState.value = ModeRequestStage.REJECTED
                        return@withTimeout
                    }
                    mutableState.value = ModeRequestStage.WAITING_FOR_MODE
                    // The independent mode collector also handles feedback before the receipt.
                    monitor.mode.first { it.function == Function.MODE_SAB }
                }
            } catch (_: TimeoutCancellationException) {
                if (valid()) mutableState.value = ModeRequestStage.TIMED_OUT
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (valid()) mutableState.value = ModeRequestStage.UNAVAILABLE }
            finally {
                // The serial owner drains a started ATT exchange; cancellation only stops retries.
                command?.cancel()
                if (job === currentCoroutineContext().job) job = null
            }
        }
        job = task
        task.start()
        return true
    }

    override fun close() {
        closed = true
        job?.cancel(); modes.cancel()
        mutableState.value = ModeRequestStage.IDLE
    }
}
