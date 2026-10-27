package org.openridesync.app.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode
import java.nio.file.Files
import java.util.UUID

class Gen1InputMonitorTest {
    private suspend fun fixture(check: suspend (Gen1InputMonitor) -> Unit) {
        val dir = Files.createTempDirectory("gen1-input-monitor-").toFile()
        try {
            DiagnosticJournal(dir).use { check(Gen1InputMonitor(it, UUID.randomUUID().toString())) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun drainsBeforeWatcherRetainsModeWithoutReplayingInputs() = runBlocking {
        fixture { monitor -> coroutineScope {
            val functions = Channel<ByteArray>(64)
            val driving = Channel<ByteArray>(64)
            val first = launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(functions) }
            val second = launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeDriving(driving) }
            repeat(100) { functions.send(byteArrayOf(9)) }
            driving.send(ByteArray(47).also { it[33] = 101 })
            yield()
            assertEquals(Gen1InputMonitor.Mode(Gen1Input.Function.MODE_SAB, 100), monitor.mode.value)
            assertEquals(101, monitor.driving.value!!.soc1!!)
            val received = async(start = CoroutineStart.UNDISPATCHED) { monitor.inputs.take(2).toList() }
            functions.send(byteArrayOf(127)) // Unknown IDs cannot become user actions.
            monitor.setRecoveryPending(true)
            functions.send(byteArrayOf(10)) // One recovery MODE_HU is suppressed, including state update.
            yield()
            assertEquals(Gen1InputMonitor.Mode(Gen1Input.Function.MODE_SAB, 100), monitor.mode.value)
            assertEquals(100L, monitor.sequence)
            functions.send(byteArrayOf(10))
            functions.send(byteArrayOf(4))
            assertEquals(listOf(Gen1InputMonitor.Input(Gen1Input.Function.MODE_HU, 101, 101),
                Gen1InputMonitor.Input(Gen1Input.Function.SELECT, 102, 101)), withTimeout(1000) { received.await() })
            assertEquals(Gen1InputMonitor.Mode(Gen1Input.Function.MODE_HU, 101), monitor.mode.value)
            first.cancelAndJoin(); second.cancelAndJoin()
        } }
    }

    @Test fun queuedSelectCannotCrossHuAndSabModeEpochs() = runBlocking {
        fixture { monitor -> coroutineScope {
            val release = CompletableDeferred<Unit>()
            val queued = mutableListOf<Gen1InputMonitor.Input>()
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                monitor.inputs.take(4).collect { input -> release.await(); queued.add(input) }
            }
            val source = Channel<ByteArray>(Channel.UNLIMITED)
            listOf(9, 4, 10, 9).forEach { source.send(byteArrayOf(it.toByte())) }
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
            // The subscriber is held while the actual monitor advances through the whole burst.
            assertEquals(Gen1InputMonitor.Mode(Gen1Input.Function.MODE_SAB, 3), monitor.mode.value)
            val enableFence = monitor.sequence
            assertEquals(4L, enableFence)
            release.complete(Unit)
            withTimeout(1000) { watcher.join() }
            val select = queued.single { it.function == Gen1Input.Function.SELECT }
            assertEquals(1L, select.modeRevision)
            assertNotEquals(monitor.mode.value.revision, select.modeRevision)
            assertTrue(select.sequence <= enableFence)
            assertEquals(listOf(1L, 2L, 3L, 4L), queued.map { it.sequence })
            consumer.cancelAndJoin(); source.cancel()
        } }
    }

    @Test fun transportFailureEscapesCancellationAndMalformedBytesFailClosed() = runBlocking {
        fixture { monitor -> supervisorScope {
            val source = Channel<ByteArray>(64)
            val consumer = async(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
            val cause = GattFailure(ErrorCode.RESOURCE_EXHAUSTED)
            source.cancel(CancellationException("recovered", CancellationException("closed", cause)))
            try { consumer.await(); fail("Expected transport failure") } catch (failure: GattFailure) { assertSame(cause, failure) }

            val malformed = Channel<ByteArray>(1)
            malformed.send(byteArrayOf())
            val invalid = async { monitor.consumeFunctions(malformed) }
            try { invalid.await(); fail("Expected malformed failure") } catch (failure: GattFailure) { assertEquals(ErrorCode.DATA_LOSS, failure.code) }
            malformed.cancel()

            val userSource = Channel<ByteArray>()
            val user = async(start = CoroutineStart.UNDISPATCHED) { monitor.consumeDriving(userSource) }
            userSource.cancel(CancellationException("user"))
            try { user.await(); fail("Expected cancellation") } catch (_: CancellationException) {}
        } }
    }

    @Test fun callerCancellationWinsEvenWhenItsCauseContainsTransportFailure() = runBlocking {
        val transport = GattFailure(ErrorCode.UNAVAILABLE)
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            val cancellation = CancellationException("caller", transport)
            currentCoroutineContext().cancel(cancellation)
            try {
                cancellation.gattFailureCause()
                fail("Cancelled caller must not become transport failure")
            } catch (_: CancellationException) { /* Expected even with a nested transport cause. */ }
        }
        caller.join()
        assertTrue(caller.isCancelled)
        val plain = CancellationException("plain")
        assertNull(plain.gattFailureCause())
        val cycle = CancellationException("cycle")
        val other = CancellationException("other", cycle)
        cycle.initCause(other)
        assertNull(cycle.gattFailureCause())
    }

    @Test fun slowInputWatcherFailsExplicitlyInsteadOfDroppingActions() = runBlocking {
        fixture { monitor -> supervisorScope {
            val held = CompletableDeferred<Unit>()
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) { monitor.inputs.collect { held.await() } }
            val source = Channel<ByteArray>(Channel.UNLIMITED)
            repeat(65) { source.send(byteArrayOf(4)) }
            val consumer = async(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
            try { consumer.await(); fail("Expected input overflow") } catch (failure: GattFailure) {
                assertEquals(ErrorCode.RESOURCE_EXHAUSTED, failure.code)
            }
            watcher.cancelAndJoin(); source.cancel()
        } }
    }

    @Test fun slowDiagnosticObserverDropsEvidenceWithoutBackpressuringActionQueue() = runBlocking {
        fixture { monitor -> coroutineScope {
            val held = CompletableDeferred<Unit>()
            val received = mutableListOf<Gen1InputMonitor.Observation>()
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                monitor.observations.collect { received.add(it); held.await() }
            }
            val source = Channel<ByteArray>(Channel.UNLIMITED)
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
            source.send(byteArrayOf(9))
            yield(); yield() // Let the observer receive the first row, then hold it.
            repeat(255) { source.send(byteArrayOf(4)) }
            yield()
            assertEquals(256L, monitor.sequence)
            assertTrue(consumer.isActive)
            held.complete(Unit)
            withTimeout(1000) { while (received.lastOrNull()?.input?.sequence != 256L) yield() }
            assertTrue(received.zipWithNext().any { (a, b) -> b.input.sequence - a.input.sequence > 1 })
            assertTrue(received.all { it.mode.function == Gen1Input.Function.MODE_SAB && it.input.modeRevision == 1L })
            watcher.cancelAndJoin(); consumer.cancelAndJoin(); source.cancel()
        } }
    }
}
