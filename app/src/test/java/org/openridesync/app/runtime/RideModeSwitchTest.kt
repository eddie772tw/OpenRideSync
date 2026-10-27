package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.*
import org.openridesync.diagnostics.DiagnosticJournal
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class RideModeSwitchTest {
    private suspend fun fixture(scope: CoroutineScope, initialMode: Int? = 10, block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ride-mode-").toFile()
        try {
            DiagnosticJournal(directory).use { journal ->
                val f = Fixture(scope, journal, initialMode)
                try { f.block() } finally { f.close() }
            }
        } finally { directory.deleteRecursively() }
    }
    private class Fixture(scope: CoroutineScope, journal: DiagnosticJournal, initialMode: Int?) {
        val source = Channel<ByteArray>(Channel.UNLIMITED).also { source ->
            initialMode?.let { source.trySend(byteArrayOf(it.toByte())) }
        }
        val receipts = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = Channel<ByteArray>(Channel.UNLIMITED)
        val monitor = Gen1InputMonitor(journal, journal.newOperation())
        var current = true
        val exchange = Gen1ControlExchange(receipts, { writes.send(it.copyOf()) }, journal, journal.newOperation())
        val monitorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
        val exchangeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { exchange.run() }
        val mode = RideModeSwitch(scope, monitor, exchange) { current }
        suspend fun input(id: Int) {
            val revision = monitor.mode.value.revision
            source.send(byteArrayOf(id.toByte()))
            monitor.mode.first { it.revision != revision }
            yield()
        }
        suspend fun close() {
            mode.close(); exchange.close(); exchangeJob.cancelAndJoin(); monitorJob.cancelAndJoin()
            source.cancel(); receipts.cancel(); writes.cancel()
        }
    }

    @Test fun acceptedReceiptDoesNotChangeModeAndOnlyObservedSabReleasesWaiting() = runTest {
        fixture(this) {
            assertTrue(mode.request()); assertFalse(mode.request())
            val command = writes.receive()
            assertArrayEquals(byteArrayOf(10, command[1]), command)
            receipts.send(byteArrayOf(command[0], command[1], 1))
            mode.state.first { it == ModeRequestStage.WAITING_FOR_MODE }
            assertEquals(Gen1Input.Function.MODE_HU, monitor.mode.value.function)
            input(9)
            mode.state.first { it == ModeRequestStage.IDLE }
            assertEquals(Gen1Input.Function.MODE_SAB, monitor.mode.value.function)
            assertFalse(mode.request()); assertTrue(writes.tryReceive().isFailure)
        }
    }

    @Test fun rejectionRetainsHuAndAllowsOnlyAnExplicitRetryWithANewId() = runTest {
        fixture(this) {
            mode.request()
            val first = writes.receive(); receipts.send(byteArrayOf(first[0], first[1], 11))
            mode.state.first { it == ModeRequestStage.REJECTED }; runCurrent()
            assertEquals(Gen1Input.Function.MODE_HU, monitor.mode.value.function)
            assertTrue(writes.tryReceive().isFailure)
            assertTrue(mode.request())
            val retry = writes.receive()
            assertEquals(10, retry[0].toInt()); assertNotEquals(first[1], retry[1])
        }
    }

    @Test fun modeFeedbackBeforeReceiptCancelsRetriesWithoutFakingAnAck() = runTest {
        fixture(this) {
            mode.request(); writes.receive()
            input(9); runCurrent()
            assertEquals(ModeRequestStage.IDLE, mode.state.value)
            advanceTimeBy(12_000); runCurrent()
            assertTrue(writes.tryReceive().isFailure)
            assertEquals(Gen1Input.Function.MODE_SAB, monitor.mode.value.function)
        }
    }

    @Test fun acceptedButMissingModeTimesOutInHuAndLateRealFeedbackStillRecovers() = runTest {
        fixture(this) {
            mode.request()
            val command = writes.receive(); receipts.send(byteArrayOf(command[0], command[1], 1))
            mode.state.first { it == ModeRequestStage.WAITING_FOR_MODE }
            advanceTimeBy(10_000); runCurrent()
            assertEquals(ModeRequestStage.TIMED_OUT, mode.state.value)
            assertEquals(Gen1Input.Function.MODE_HU, monitor.mode.value.function)
            assertTrue(writes.tryReceive().isFailure)
            input(9); runCurrent()
            assertEquals(ModeRequestStage.IDLE, mode.state.value)
        }
    }

    @Test fun closeAndInvalidOwnerCannotWriteOrResurrectAPendingRequest() = runTest {
        fixture(this) {
            current = false; assertFalse(mode.request())
            assertEquals(ModeRequestStage.UNAVAILABLE, mode.state.value)
            current = true; assertTrue(mode.request())
            mode.close(); runCurrent()
            assertFalse(mode.request()); assertTrue(writes.tryReceive().isFailure)
        }
    }

    @Test fun unknownRequiresAnExplicitRequestAndHuFeedbackKeepsWaitingForRealSab() = runTest {
        fixture(this, initialMode = null) {
            source.send(byteArrayOf(0)) // An unrecognized notification is not an observed mode.
            runCurrent()
            assertEquals(ModeRequestStage.IDLE, mode.state.value)
            assertNull(monitor.mode.value.function)
            assertTrue(writes.tryReceive().isFailure)
            assertTrue(mode.request())
            val command = writes.receive()
            assertArrayEquals(byteArrayOf(10, command[1]), command)
            receipts.send(byteArrayOf(command[0], command[1], 1))
            mode.state.first { it == ModeRequestStage.WAITING_FOR_MODE }
            assertNull(monitor.mode.value.function)
            input(10)
            assertEquals(ModeRequestStage.WAITING_FOR_MODE, mode.state.value)
            assertEquals(Gen1Input.Function.MODE_HU, monitor.mode.value.function)
            assertFalse(mode.request())
            assertTrue(writes.tryReceive().isFailure)
            input(9)
            mode.state.first { it == ModeRequestStage.IDLE }
            assertFalse(mode.request())
        }
    }

    @Test fun unknownTimeoutAllowsExplicitRetryButAnOldReceiptCannotAcceptTheNewRequest() = runTest {
        fixture(this, initialMode = null) {
            assertTrue(mode.request())
            val first = writes.receive()
            receipts.send(byteArrayOf(first[0], first[1], 1))
            mode.state.first { it == ModeRequestStage.WAITING_FOR_MODE }
            advanceTimeBy(10_000); runCurrent()
            assertEquals(ModeRequestStage.TIMED_OUT, mode.state.value)
            assertNull(monitor.mode.value.function)
            assertTrue(writes.tryReceive().isFailure)
            assertTrue(mode.request())
            val retry = writes.receive()
            assertNotEquals(first[1], retry[1])
            receipts.send(byteArrayOf(first[0], first[1], 1)); runCurrent()
            assertEquals(ModeRequestStage.REQUESTING, mode.state.value)
            receipts.send(byteArrayOf(retry[0], retry[1], 1))
            mode.state.first { it == ModeRequestStage.WAITING_FOR_MODE }
            mode.close()
            receipts.send(byteArrayOf(retry[0], retry[1], 1))
            input(10); runCurrent()
            assertEquals(ModeRequestStage.IDLE, mode.state.value)
            assertFalse(mode.request())
            assertTrue(writes.tryReceive().isFailure)
        }
    }

    @Test fun unknownSabFeedbackBeforeReceiptAndInvalidOwnerCannotResurrectRequests() = runTest {
        fixture(this, initialMode = null) {
            current = false
            assertFalse(mode.request())
            assertEquals(ModeRequestStage.UNAVAILABLE, mode.state.value)
            current = true
            assertTrue(mode.request())
            val command = writes.receive()
            input(9); runCurrent()
            receipts.send(byteArrayOf(command[0], command[1], 1))
            advanceTimeBy(12_000); runCurrent()
            assertEquals(ModeRequestStage.IDLE, mode.state.value)
            assertEquals(Gen1Input.Function.MODE_SAB, monitor.mode.value.function)
            assertTrue(writes.tryReceive().isFailure)
        }
    }
}
