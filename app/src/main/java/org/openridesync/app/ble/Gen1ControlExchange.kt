package org.openridesync.app.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.ArrayDeque
import java.util.UUID

/** One serial ControlPoint consumer. Caller owns its coroutine and the GATT session.
 * New queued commands replace older queued commands of that opcode; in-flight writes finish.
 * No firmware update command, automatic mode request, or success inferred from write completion. */
internal class Gen1ControlExchange(
    private val notifications: ReceiveChannel<ByteArray>,
    private val write: suspend (ByteArray) -> Unit,
    private val journal: DiagnosticJournal,
    private val operation: String,
) : AutoCloseable {
    private class Pending(val frame: ByteArray, val allowed: () -> Boolean) {
        val result = CompletableDeferred<Gen1ControlPoint.Receipt?>()
    }
    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var nextId = 0
    private var active: Pending? = null
    private var runner: Job? = null
    private var started = false
    private var closed = false
    private var stopping = false
    private var drained = false
    private var failure: GattFailure? = null
    private val stopped = CompletableDeferred<Unit>()

    /** The queue allocates the ID, then copies the encoded frame. Null result means replaced
     * before sending or rejected by the freshness guard before a write/retry.
     * Any parsed receipt (including non-success status) is returned unchanged.
     * Cancelling removes an unsent command or stops retries after an active exchange finishes;
     * it cannot undo an already started write. */
    // The guard runs on the serial worker's context, immediately before each exchange attempt.
    fun submit(allowed: () -> Boolean = { true }, encode: (Int) -> ByteArray): Deferred<Gen1ControlPoint.Receipt?> = synchronized(lock) {
        check(!closed && !stopping) { "Control exchange is closed" }
        val frame = encode(nextId).copyOf()
        require(frame.size in 2..512 && frame[0].toInt() in listOf(1, 2, 3, 10) &&
            (frame[1].toInt() and 255) == nextId) { "Invalid control command" }
        nextId = (nextId + 1) and 255
        val replaced = mutableListOf<Pending>()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val previous = iterator.next()
            if (previous.frame[0] == frame[0]) {
                iterator.remove()
                replaced.add(previous)
            }
        }
        val request = Pending(frame, allowed)
        queue.addLast(request) // At most one queued frame per supported opcode (four), plus in-flight.
        request.result.invokeOnCompletion {
            if (request.result.isCancelled) synchronized(lock) { queue.remove(request) }
        }
        changed.trySend(Unit)
        // Deferred completion can invoke caller callbacks reentrantly; finish queue edits first.
        replaced.forEach {
            it.result.complete(null)
            journal.record(Component.BLE, operation, Event.BLE_CONTROL_COMMAND, Outcome.CANCELLED, ErrorCode.ABORTED)
        }
        request.result
    }

    suspend fun run() {
        val owner = currentCoroutineContext()
        synchronized(lock) {
            check(!started) { "Control exchange cannot restart" }
            started = true
            if (closed) {
                failure?.let { throw it }
                return // Gracefully stopped before the scheduled worker began.
            }
            runner = owner[Job]
        }
        try {
            while (true) {
                owner.ensureActive()
                val request = synchronized(lock) {
                    if (closed) throw GattFailure(ErrorCode.CANCELLED)
                    if (stopping) {
                        closed = true
                        drained = true
                        stopped.complete(Unit)
                        return
                    }
                    queue.pollFirst()?.also { active = it }
                }
                if (request == null) { changed.receive(); continue }
                journal.record(Component.BLE, operation, Event.BLE_CONTROL_COMMAND, Outcome.STARTED, ErrorCode.NONE)
                val receipt = exchange(request)
                owner.ensureActive()
                synchronized(lock) {
                    if (closed) throw GattFailure(ErrorCode.CANCELLED)
                    val completed = receipt != null && request.result.complete(receipt)
                    active = null
                    if (receipt == null) request.result.complete(null)
                    if (receipt == null || !completed) {
                        journal.record(Component.BLE, operation, Event.BLE_CONTROL_COMMAND,
                            Outcome.CANCELLED, ErrorCode.CANCELLED)
                    } else {
                        journal.record(Component.BLE, operation, Event.BLE_CONTROL_RECEIPT,
                            if (receipt.status() == 1) Outcome.SUCCEEDED else Outcome.FAILED,
                            if (receipt.status() == 1) ErrorCode.NONE else ErrorCode.FAILED_PRECONDITION)
                    }
                }
            }
        } catch (failure: Exception) {
            val transportFailure = failure.gattFailureOrNull()
            val code = when {
                !owner.isActive -> ErrorCode.CANCELLED
                transportFailure != null -> transportFailure.code
                failure is CancellationException -> ErrorCode.CANCELLED
                else -> ErrorCode.UNAVAILABLE
            }
            val error = transportFailure ?: GattFailure(code)
            shutdown(error)
            journal.record(Component.BLE, operation, Event.BLE_CONTROL_COMMAND,
                if (code == ErrorCode.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, code)
            owner.ensureActive()
            if (failure is CancellationException && code == ErrorCode.CANCELLED) throw failure
            throw error
        }
    }

    private suspend fun exchange(request: Pending): Gen1ControlPoint.Receipt? {
        var failureCode = ErrorCode.TIMEOUT
        for (attempt in 1..10_000) {
            currentCoroutineContext().ensureActive()
            if (synchronized(lock) { stopping } || request.result.isCancelled || !request.allowed()) return null
            val bytes = exchangeGattReceipt(request.frame, notifications, write, Gen1ControlPoint::matches)
            // Finish the single active exchange, including transport failures, before dropping it.
            if (request.result.isCancelled) return null
            if (bytes == null) failureCode = ErrorCode.TIMEOUT
            else try { return Gen1ControlPoint.decodeReceipt(bytes) }
            catch (_: IllegalArgumentException) { failureCode = ErrorCode.DATA_LOSS }
            if (synchronized(lock) { stopping }) return null
            if (attempt < 10_000)
                journal.record(Component.BLE, operation, Event.RETRY, Outcome.OBSERVED, failureCode)
        }
        // Explicit failure instead of OEM retryWhen exhaustion completing its stream.
        throw GattFailure(failureCode)
    }

    private fun shutdown(code: ErrorCode) = shutdown(GattFailure(code))

    private fun shutdown(error: GattFailure): Job? = synchronized(lock) {
        if (failure != null) return@synchronized runner
        closed = true
        drained = false
        failure = error
        val outstanding = queue.toMutableList()
        active?.let(outstanding::add)
        active = null
        queue.clear()
        // Completion handlers may remove cancelled entries; detach before completing them.
        outstanding.forEach { it.result.completeExceptionally(error) }
        changed.cancel()
        stopped.completeExceptionally(error)
        runner
    }

    override fun close() { shutdown(ErrorCode.CANCELLED)?.cancel() }

    /** Stop accepting commands/retries without cancelling an already started ATT write.
     * GattSession bounds the ATT operation to 15s after obtaining its serial lock;
     * the parallel receipt wait is bounded to 2s. No new retry begins while stopping.
     * Cancellation of this waiter forces immediate close, as do transport/worker failures. */
    suspend fun stopGracefully() {
        val worker = synchronized(lock) {
            if (!closed && !stopping) {
                stopping = true
                val discarded = queue.toList()
                queue.clear()
                discarded.forEach {
                    it.result.complete(null)
                    journal.record(Component.BLE, operation, Event.BLE_CONTROL_COMMAND, Outcome.CANCELLED, ErrorCode.CANCELLED)
                }
                if (!closed && !started) {
                    closed = true
                    drained = true
                    stopped.complete(Unit)
                } else if (!closed) changed.trySend(Unit)
            }
            runner
        }
        try {
            stopped.await()
            worker?.join()
            currentCoroutineContext().ensureActive()
            if (worker?.isCancelled == true) shutdown(ErrorCode.CANCELLED)
            synchronized(lock) { failure?.let { throw it } }
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }

    /** Only a successfully drained receiver may hand its allocator to final clear. */
    fun nextRequestIdForShutdown(): Int = synchronized(lock) {
        check(drained && failure == null && runner?.isCompleted != false && runner?.isCancelled != true) {
            "Control receiver must drain before shutdown"
        }
        nextId.also { nextId = (nextId + 1) and 255 }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("8536e103-6771-4d4b-9702-287cb5e1340f")
        val CONTROL: UUID = UUID.fromString("d2e23443-f1ad-4293-a70b-a9fd163f7b00")
    }
}
