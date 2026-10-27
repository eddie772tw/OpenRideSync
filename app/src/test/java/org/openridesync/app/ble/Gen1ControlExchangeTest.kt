package org.openridesync.app.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode

class Gen1ControlExchangeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun replacesOnlyQueuedOpcodeAndPreservesNonSuccessReceipt() = runBlocking {
        DiagnosticJournal(temporary.newFolder()).use { journal ->
            val replies = Channel<ByteArray>(64)
            val sent = mutableListOf<ByteArray>()
            val exchange = Gen1ControlExchange(replies, { frame ->
                sent.add(frame.copyOf())
                replies.send(frame.copyOf())
                replies.send(byteArrayOf(1, (frame[1] + 1).toByte(), 1))
                replies.send(byteArrayOf(11, frame[1], if (frame[0].toInt() == 1) 12 else 1))
                yield()
            }, journal, journal.newOperation())
            val obsolete = exchange.submit { byteArrayOf(1, it.toByte()) }
            val popup = exchange.submit { byteArrayOf(2, it.toByte()) }
            val pageBytes = byteArrayOf(1, 2, 99)
            val page = exchange.submit { pageBytes }
            pageBytes[2] = 0
            val mode = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
            assertNull(obsolete.await())
            val worker = launch { exchange.run() }
            assertEquals(1, popup.await()!!.status())
            assertEquals(12, page.await()!!.status())
            assertEquals(11, page.await()!!.command())
            assertEquals(1, mode.await()!!.status())
            assertEquals(listOf(2, 1, 10), sent.map { it[0].toInt() })
            assertEquals(99, sent[1][2].toInt())
            exchange.close(); worker.join(); replies.cancel()
        }
    }

    @Test fun inFlightIsNotReplacedMalformedReplyRetriesAndIdsWrap() = runBlocking {
        DiagnosticJournal(temporary.newFolder()).use { journal ->
            val replies = Channel<ByteArray>(64)
            val firstWrite = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<ByteArray>()
            val exchange = Gen1ControlExchange(replies, { frame ->
                sent.add(frame.copyOf())
                if (sent.size == 1) {
                    firstWrite.complete(Unit); release.await()
                    replies.send(byteArrayOf(99, frame[1], 1)) // Correlated but malformed command.
                } else replies.send(byteArrayOf(frame[0], frame[1], 1))
            }, journal, journal.newOperation())
            val first = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
            val worker = launch { exchange.run() }
            firstWrite.await()
            val replaced = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
            val latest = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
            assertNull(replaced.await())
            release.complete(Unit)
            assertEquals(0, first.await()!!.requestId())
            assertEquals(2, latest.await()!!.requestId())
            assertArrayEquals(sent[0], sent[1])
            repeat(254) { exchange.submit(encode = Gen1ControlPoint::requestSabMode).await() }
            assertEquals(0, sent.last()[1].toInt() and 255)
            exchange.close(); worker.join(); replies.cancel()
        }
    }

    @Test fun cancellingActiveRequestDrainsMalformedReceiptWithoutRetryAndRunsNext() = runBlocking {
        withTimeout(5_000) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                val replies = Channel<ByteArray>(1)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val sent = mutableListOf<ByteArray>()
                val exchange = Gen1ControlExchange(replies, { frame ->
                    sent.add(frame.copyOf())
                    if (frame[1].toInt() == 0) {
                        started.complete(Unit)
                        release.await()
                        replies.send(byteArrayOf(99, frame[1], 1)) // Correlated, malformed receipt.
                    } else replies.send(byteArrayOf(frame[0], frame[1], 1))
                }, journal, journal.newOperation())
                val obsolete = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
                val worker = launch { exchange.run() }
                try {
                    started.await()
                    obsolete.cancel()
                    val next = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
                    assertEquals(1, sent.size) // Cancellation cannot retract the first write.
                    release.complete(Unit)
                    assertEquals(1, next.await()!!.status())
                    assertEquals(listOf(0, 1), sent.map { it[1].toInt() and 255 })
                    assertTrue(obsolete.isCancelled)
                } finally {
                    exchange.close(); worker.join(); replies.cancel()
                }
            }
        }
    }

    @Test fun freshnessGuardsSkipQueuedCommandAndStopActiveRetryOnWorker() = runBlocking {
        withTimeout(5_000) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                val replies = Channel<ByteArray>(1)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val sent = mutableListOf<Int>()
                var activeAllowed = true
                var activeChecks = 0
                var queuedChecks = 0
                val exchange = Gen1ControlExchange(replies, { frame ->
                    sent.add(frame[1].toInt() and 255)
                    if (frame[1].toInt() == 0) {
                        started.complete(Unit)
                        release.await()
                        replies.send(byteArrayOf(99, frame[1], 1))
                    } else replies.send(byteArrayOf(frame[0], frame[1], 1))
                }, journal, journal.newOperation())
                val active = exchange.submit(allowed = { activeChecks++; activeAllowed },
                    encode = Gen1ControlPoint::requestSabMode)
                assertEquals(0, activeChecks) // Guard is evaluated by run(), never by submit().
                val worker = launch { exchange.run() }
                try {
                    started.await()
                    activeAllowed = false
                    val stale = exchange.submit(allowed = { queuedChecks++; false }) { byteArrayOf(2, it.toByte()) }
                    val next = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
                    assertEquals(0, queuedChecks)
                    release.complete(Unit)
                    assertNull(active.await())
                    assertNull(stale.await())
                    assertEquals(1, next.await()!!.status())
                    assertEquals(listOf(0, 2), sent)
                    assertEquals(2, activeChecks)
                    assertEquals(1, queuedChecks)
                } finally {
                    exchange.close(); worker.join(); replies.cancel()
                }
            }
        }
    }

    @Test fun transportFailureFailsPendingAndReturnedCancellationRemovesUnsent() = runBlocking {
        DiagnosticJournal(temporary.newFolder()).use { journal ->
            val replies = Channel<ByteArray>(1)
            val original = GattFailure(ErrorCode.IO, GattFailure.Origin.OPERATION_STATUS, 133)
            val exchange = Gen1ControlExchange(replies, { throw original }, journal, journal.newOperation())
            val cancelled = exchange.submit { byteArrayOf(2, it.toByte()) }
            cancelled.cancel()
            val active = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
            val queued = exchange.submit { byteArrayOf(1, it.toByte()) }
            val worker = launch {
                try { exchange.run() }
                catch (failure: GattFailure) { assertSame(original, failure) }
            }
            worker.join()
            for (result in listOf(active, queued)) {
                try { result.await(); fail("Failed transport must fail all pending results") }
                catch (failure: GattFailure) { assertSame(original, failure) }
            }
            assertTrue(cancelled.isCancelled)
            assertThrows(IllegalStateException::class.java) { exchange.submit(encode = Gen1ControlPoint::requestSabMode) }
            exchange.close(); replies.cancel()
        }
    }

    @Test fun closeCancelsActiveReceiptWaitAndNeverSendsFirmwareUpdate() = runBlocking {
        DiagnosticJournal(temporary.newFolder()).use { journal ->
            val replies = Channel<ByteArray>(1)
            val started = CompletableDeferred<Unit>()
            val exchange = Gen1ControlExchange(replies, { started.complete(Unit) }, journal, journal.newOperation())
            assertThrows(IllegalArgumentException::class.java) { exchange.submit { byteArrayOf(11, it.toByte()) } }
            val result = exchange.submit(encode = Gen1ControlPoint::requestSabMode)
            assertThrows(IllegalStateException::class.java) { exchange.nextRequestIdForShutdown() }
            val worker = launch { exchange.run() }
            started.await()
            exchange.close(); worker.join()
            assertThrows(IllegalStateException::class.java) { exchange.nextRequestIdForShutdown() }
            try { result.await(); fail("Closed exchange must not complete") }
            catch (failure: GattFailure) { assertEquals(ErrorCode.CANCELLED, failure.code) }
            assertTrue(worker.isCancelled)
            replies.cancel()
        }
    }

    @Test fun gracefulStopPreservesPendingAttWriteDiscardsQueueAndContinuesShutdownIds() = runBlocking {
        withTimeout(5_000) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                val replies = Channel<ByteArray>(8)
                val writing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var transportValid = true
                val sent = mutableListOf<ByteArray>()
                // Like GattSession: cancelling an outstanding ATT write invalidates transport.
                val write: suspend (ByteArray) -> Unit = { frame ->
                    check(transportValid)
                    sent.add(frame.copyOf())
                    if (sent.size == 1) {
                        writing.complete(Unit)
                        try { release.await() }
                        catch (cancelled: CancellationException) { transportValid = false; throw cancelled }
                    }
                    replies.send(byteArrayOf(frame[0], frame[1], 1))
                }
                val exchange = Gen1ControlExchange(replies, write, journal, journal.newOperation())
                val active = exchange.submit { byteArrayOf(1, it.toByte()) }
                val worker = launch { exchange.run() }
                writing.await()
                val queued = exchange.submit { byteArrayOf(2, it.toByte()) }
                val stop = async(start = CoroutineStart.UNDISPATCHED) { exchange.stopGracefully() }
                assertNull(queued.await())
                assertFalse(stop.isCompleted)
                assertTrue(transportValid)
                assertThrows(IllegalStateException::class.java) { exchange.submit { byteArrayOf(1, it.toByte()) } }
                assertThrows(IllegalStateException::class.java) { exchange.nextRequestIdForShutdown() }
                active.cancel() // Cancels the result, not the ATT write being drained.
                release.complete(Unit)
                stop.await(); worker.join()
                assertTrue(transportValid)
                assertEquals(1, sent.size)
                val receipts = Gen1Shutdown.exchange(2, exchange::nextRequestIdForShutdown,
                    "Honda", "Off", replies, write)
                assertEquals(1, receipts.single().status())
                // IDs 0/1 were allocated before stop; shutdown's untransmitted menu consumes 2.
                assertEquals(listOf(0, 3), sent.map { it[1].toInt() and 255 })
                exchange.close(); replies.cancel()
            }
        }
    }

    @Test fun gracefulStopEndsIdleWorkerAndCanPrecedeWorkerStart() = runBlocking {
        withTimeout(5_000) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                repeat(2) { beforeStart ->
                    val replies = Channel<ByteArray>(1)
                    val exchange = Gen1ControlExchange(replies, { fail("Idle stop must not write") }, journal, journal.newOperation())
                    if (beforeStart == 0) exchange.stopGracefully()
                    val worker = launch(start = CoroutineStart.UNDISPATCHED) { exchange.run() }
                    exchange.stopGracefully(); worker.join()
                    assertFalse(worker.isCancelled)
                    assertEquals(0, exchange.nextRequestIdForShutdown())
                    exchange.close(); replies.cancel()
                }
            }
        }
    }

    @Test fun gracefulStopDoesNotRetryMalformedActiveReceipt() = runBlocking {
        withTimeout(5_000) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                val replies = Channel<ByteArray>(1)
                val writing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var writes = 0
                val exchange = Gen1ControlExchange(replies, { frame ->
                    writes++; writing.complete(Unit); release.await()
                    replies.send(byteArrayOf(99, frame[1], 1))
                }, journal, journal.newOperation())
                val result = exchange.submit { byteArrayOf(1, it.toByte()) }
                val worker = launch { exchange.run() }
                writing.await()
                val stop = async(start = CoroutineStart.UNDISPATCHED) { exchange.stopGracefully() }
                release.complete(Unit)
                stop.await(); worker.join()
                assertNull(result.await())
                assertEquals(1, writes)
                assertEquals(1, exchange.nextRequestIdForShutdown())
                exchange.close(); replies.cancel()
            }
        }
    }

    @Test fun gracefulStopKeepsReceiptDeadlineWithoutRetry() = runBlocking {
        withTimeout(5_000) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                val replies = Channel<ByteArray>(1)
                val written = CompletableDeferred<Unit>()
                var writes = 0
                val exchange = Gen1ControlExchange(replies, {
                    writes++
                    written.complete(Unit) // ATT completes, but the vehicle never supplies a receipt.
                }, journal, journal.newOperation())
                val result = exchange.submit { byteArrayOf(1, it.toByte()) }
                val worker = launch { exchange.run() }
                try {
                    written.await()
                    val stop = async(start = CoroutineStart.UNDISPATCHED) { exchange.stopGracefully() }
                    assertFalse(stop.isCompleted)
                    stop.await(); worker.join()
                    assertNull(result.await())
                    assertEquals(1, writes)
                    assertFalse(worker.isCancelled)
                    assertEquals(1, exchange.nextRequestIdForShutdown())
                } finally {
                    exchange.close(); worker.join(); replies.cancel()
                }
            }
        }
    }

    @Test fun queuedCompletionCanForceCloseBeforeWorkerStartsWithoutAuthorizingFinalClear() = runBlocking {
        DiagnosticJournal(temporary.newFolder()).use { journal ->
            val replies = Channel<ByteArray>(1)
            val exchange = Gen1ControlExchange(replies, { fail("Stopped queue must not write") }, journal, journal.newOperation())
            val queued = exchange.submit { byteArrayOf(1, it.toByte()) }
            queued.invokeOnCompletion { exchange.close() }
            try { exchange.stopGracefully(); fail("Reentrant close must win over graceful stop") }
            catch (failure: GattFailure) { assertEquals(ErrorCode.CANCELLED, failure.code) }
            assertNull(queued.await())
            assertThrows(IllegalStateException::class.java) { exchange.nextRequestIdForShutdown() }
            replies.cancel()
        }
    }

    @Test fun transportFailureDuringDrainNeverAuthorizesFinalClear() = runBlocking {
        withTimeout(5_000) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                val replies = Channel<ByteArray>(1)
                val writing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val exchange = Gen1ControlExchange(replies, {
                    writing.complete(Unit); release.await(); throw GattFailure(ErrorCode.IO)
                }, journal, journal.newOperation())
                val result = exchange.submit { byteArrayOf(1, it.toByte()) }
                val worker = launch {
                    try { exchange.run(); fail("Transport failure must escape") }
                    catch (failure: GattFailure) { assertEquals(ErrorCode.IO, failure.code) }
                }
                writing.await()
                val stop = async(start = CoroutineStart.UNDISPATCHED) {
                    try { exchange.stopGracefully(); fail("Failed transport must not drain") }
                    catch (failure: GattFailure) { assertEquals(ErrorCode.IO, failure.code) }
                }
                release.complete(Unit)
                stop.await(); worker.join()
                try { result.await(); fail("Active result must fail") }
                catch (failure: GattFailure) { assertEquals(ErrorCode.IO, failure.code) }
                assertThrows(IllegalStateException::class.java) { exchange.nextRequestIdForShutdown() }
                exchange.close(); replies.cancel()
            }
        }
    }

    @Test fun forcedCloseWorkerCancellationAndWaiterCancellationInterruptDrain() = runBlocking {
        withTimeout(5_000) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                repeat(3) { cause ->
                    val replies = Channel<ByteArray>(1)
                    val writing = CompletableDeferred<Unit>()
                    var transportValid = true
                    val exchange = Gen1ControlExchange(replies, {
                        writing.complete(Unit)
                        try { awaitCancellation() }
                        finally { transportValid = false }
                    }, journal, journal.newOperation())
                    val result = exchange.submit { byteArrayOf(1, it.toByte()) }
                    val worker = launch { exchange.run() }
                    writing.await()
                    val stop = async(start = CoroutineStart.UNDISPATCHED) {
                        try { exchange.stopGracefully(); fail("Forced cancellation must not drain") }
                        catch (failure: GattFailure) { assertEquals(ErrorCode.CANCELLED, failure.code) }
                    }
                    when (cause) {
                        0 -> exchange.close()
                        1 -> worker.cancel()
                        else -> stop.cancel()
                    }
                    if (cause == 2) stop.join() else stop.await()
                    worker.join()
                    assertFalse(transportValid)
                    assertTrue(worker.isCancelled)
                    try { result.await(); fail("Active result must fail") }
                    catch (failure: GattFailure) { assertEquals(ErrorCode.CANCELLED, failure.code) }
                    assertThrows(IllegalStateException::class.java) { exchange.nextRequestIdForShutdown() }
                    assertThrows(IllegalStateException::class.java) { exchange.submit { byteArrayOf(1, it.toByte()) } }
                    exchange.close(); replies.cancel()
                }
            }
        }
    }

    @Test fun exhaustionFailsAndReplacementCompletionCanReenterQueue() = runBlocking {
        DiagnosticJournal(temporary.newFolder()).use { journal ->
            val replies = Channel<ByteArray>(1)
            var writes = 0
            val exchange = Gen1ControlExchange(replies, { frame ->
                writes++
                replies.send(byteArrayOf(99, frame[1], 1))
            }, journal, journal.newOperation())
            val replaced = exchange.submit { byteArrayOf(1, it.toByte()) }
            var reentrant: Deferred<Gen1ControlPoint.Receipt?>? = null
            replaced.invokeOnCompletion { reentrant = exchange.submit(encode = Gen1ControlPoint::requestSabMode) }
            val active = exchange.submit { byteArrayOf(1, it.toByte()) }
            assertNull(replaced.await())
            val worker = launch {
                try { exchange.run() }
                catch (failure: GattFailure) { assertEquals(ErrorCode.DATA_LOSS, failure.code) }
            }
            worker.join()
            assertEquals(10_000, writes)
            for (result in listOf(active, requireNotNull(reentrant))) {
                try { result.await(); fail("Exhaustion must fail active and queued commands") }
                catch (failure: GattFailure) { assertEquals(ErrorCode.DATA_LOSS, failure.code) }
            }
            exchange.close(); replies.cancel()
        }
    }
}
