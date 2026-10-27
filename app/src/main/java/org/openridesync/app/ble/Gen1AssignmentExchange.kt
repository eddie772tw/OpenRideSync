package org.openridesync.app.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.UUID

/** Assignment stage only. Caller owns the connection and completes all runtime CCCD setup first. */
internal object Gen1AssignmentExchange {
    val SERVICE: UUID = UUID.fromString("f0746e98-61d6-4570-be7e-7635c65c21cb")
    val CONTROL: UUID = UUID.fromString("4f5fbe6b-474b-4820-833f-95bfbf525d9f")

    suspend fun register(
        session: GattSession,
        notifications: ReceiveChannel<ByteArray>,
        firstRequestId: Int,
        includeColors: Boolean,
        journal: DiagnosticJournal,
        operation: String,
    ) {
        journal.record(Component.BLE, operation, Event.BLE_ASSIGNMENT_REGISTER, Outcome.STARTED, ErrorCode.NONE)
        try {
            exchange(firstRequestId, includeColors, notifications,
                write = { session.write(SERVICE, CONTROL, it) },
                retry = { journal.record(Component.BLE, operation, Event.RETRY, Outcome.OBSERVED, it) })
            journal.record(Component.BLE, operation, Event.BLE_ASSIGNMENT_REGISTER, Outcome.SUCCEEDED, ErrorCode.NONE)
        } catch (failure: Exception) {
            val transportFailure = failure.gattFailureOrNull()
            val code = when {
                !currentCoroutineContext().isActive -> ErrorCode.CANCELLED
                transportFailure != null -> transportFailure.code
                failure is CancellationException -> ErrorCode.CANCELLED
                failure is IllegalArgumentException -> ErrorCode.INVALID_ARGUMENT
                else -> ErrorCode.UNAVAILABLE
            }
            journal.record(Component.BLE, operation, Event.BLE_ASSIGNMENT_REGISTER,
                if (code == ErrorCode.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, code)
            session.close()
            currentCoroutineContext().ensureActive()
            if (failure is CancellationException && code == ErrorCode.CANCELLED) throw failure
            throw transportFailure ?: GattFailure(code)
        }
    }

    /** Transport-independent exchange also exercises ordering without an Android/vehicle mock. */
    internal suspend fun exchange(
        firstRequestId: Int,
        includeColors: Boolean,
        notifications: ReceiveChannel<ByteArray>,
        write: suspend (ByteArray) -> Unit,
        retry: (ErrorCode) -> Unit,
    ) = supervisorScope {
        var accepted = true
        for (frame in Gen1Assignments.frames(firstRequestId, includeColors)) {
            var result: Gen1Assignments.Result? = null
            var failureCode = ErrorCode.TIMEOUT
            // OEM's nested retryWhen can complete on exhaustion. Native exhaustion is a failure;
            // retain its 10,000 scale as a finite attempt ceiling, with caller cancellation throughout.
            for (attempt in 1..10_000) {
                ensureActive()
                val bytes = exchangeGattReceipt(frame, notifications, write, Gen1Assignments::matches)
                if (bytes == null) failureCode = ErrorCode.TIMEOUT
                else try {
                    result = Gen1Assignments.decodeResult(bytes)
                } catch (_: IllegalArgumentException) { failureCode = ErrorCode.DATA_LOSS }
                if (result != null) break
                if (attempt < 10_000) retry(failureCode)
            }
            val completed = result ?: throw GattFailure(failureCode)
            // OEM completes all syntactically valid replies, then applies one final status gate.
            accepted = accepted && completed.accepted()
        }
        if (!accepted) throw GattFailure(ErrorCode.FAILED_PRECONDITION)
        ensureActive()
        write(Gen1Assignments.endFrame()) // Stronger than OEM: wait for this ATT callback too.
    }
}
