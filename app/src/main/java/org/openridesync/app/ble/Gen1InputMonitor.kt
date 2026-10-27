package org.openridesync.app.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*

/** Drain subscriptions immediately; a runtime owns these consumers and cancels them on session end. */
internal class Gen1InputMonitor(private val journal: DiagnosticJournal, private val operation: String) {
    data class Mode(val function: Gen1Input.Function? = null, val revision: Long = 0)
    data class Input(val function: Gen1Input.Function, val sequence: Long, val modeRevision: Long)
    data class Observation(val input: Input, val mode: Mode, val receivedNanos: Long)
    private val inputLock = Any()
    private var recoveryPending = false
    private var acceptedSequence = 0L
    val sequence: Long get() = synchronized(inputLock) { acceptedSequence }
    private val mutableMode = MutableStateFlow(Mode())
    private val mutableDriving = MutableStateFlow<Gen1Input.DrivingInfo?>(null)
    private val mutableActions = MutableSharedFlow<Input>(replay = 0, extraBufferCapacity = 64)
    val mode: StateFlow<Mode> = mutableMode.asStateFlow()
    val driving: StateFlow<Gen1Input.DrivingInfo?> = mutableDriving.asStateFlow()
    val inputs: SharedFlow<Input> = mutableActions.asSharedFlow()
    // Diagnostics never participate in the lossless action queue: a slow UI may lose evidence,
    // but must not fail the vehicle connection. Sequence gaps make that loss explicit.
    private val mutableObservations = MutableSharedFlow<Observation>(replay = 0,
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val observations: SharedFlow<Observation> = mutableObservations.asSharedFlow()

    /** Caller supplies the recovered advanced-connection recovery state; never infer from auth. */
    fun setRecoveryPending(pending: Boolean) = synchronized(inputLock) { recoveryPending = pending }

    suspend fun consumeFunctions(channel: ReceiveChannel<ByteArray>) = consume(channel) { bytes ->
        val event = try { Gen1Input.decodeFunction(bytes) } catch (_: IllegalArgumentException) {
            record(Event.BLE_FUNCTION_NOTIFICATION, ErrorCode.DATA_LOSS)
            throw GattFailure(ErrorCode.DATA_LOSS)
        }
        synchronized(inputLock) {
            if (event.function == Gen1Input.Function.MODE_HU && recoveryPending) {
                recoveryPending = false
                record(Event.BLE_FUNCTION_NOTIFICATION, ErrorCode.NONE)
            } else if (event.function == Gen1Input.Function.UNKNOWN) {
                record(Event.BLE_FUNCTION_NOTIFICATION, ErrorCode.UNSUPPORTED)
            } else {
                if (event.function == Gen1Input.Function.MODE_SAB || event.function == Gen1Input.Function.MODE_HU)
                    mutableMode.value = Mode(event.function, mutableMode.value.revision + 1)
                acceptedSequence++
                val input = Input(event.function, acceptedSequence, mutableMode.value.revision)
                if (!mutableActions.tryEmit(input)) {
                    record(Event.BLE_FUNCTION_NOTIFICATION, ErrorCode.RESOURCE_EXHAUSTED)
                    throw GattFailure(ErrorCode.RESOURCE_EXHAUSTED)
                }
                mutableObservations.tryEmit(Observation(input, mutableMode.value, System.nanoTime()))
                record(Event.BLE_FUNCTION_NOTIFICATION, ErrorCode.NONE)
            }
        }
    }

    suspend fun consumeDriving(channel: ReceiveChannel<ByteArray>) = consume(channel) { bytes ->
        val info = Gen1Input.decodeDrivingInfo(bytes)
        mutableDriving.value = info
        record(Event.BLE_DRIVING_NOTIFICATION, when {
            !info.expectedLength -> ErrorCode.DATA_LOSS
            info.isError == true -> ErrorCode.FAILED_PRECONDITION
            else -> ErrorCode.NONE
        })
    }

    private suspend fun consume(channel: ReceiveChannel<ByteArray>, accept: (ByteArray) -> Unit) {
        try {
            for (bytes in channel) accept(bytes)
        } catch (cancelled: CancellationException) {
            // GattNotificationQueue uses cancellation to discard buffered data after transport failure.
            val transport = cancelled.gattFailureCause()
            if (transport != null) throw transport
            throw cancelled
        }
        // A runtime stream is expected to end by cancellation; normal EOF is transport loss too.
        throw GattFailure(ErrorCode.UNAVAILABLE)
    }

    private fun record(event: Event, error: ErrorCode) {
        journal.record(Component.BLE, operation, event, Outcome.OBSERVED, error)
    }
}
