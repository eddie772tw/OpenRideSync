package org.openridesync.app.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode

class Gen1AssignmentExchangeTest {
    @Test fun serialRepliesIgnoreEchoAndWrongIdsAndRetainRetryId() = runBlocking {
        val replies = Channel<ByteArray>(64)
        val writes = mutableListOf<ByteArray>()
        var retries = 0
        Gen1AssignmentExchange.exchange(65535, false, replies, write = { frame ->
            writes.add(frame.copyOf())
            if (frame.size > 1) {
                replies.send(frame.copyOf())
                replies.send(byteArrayOf(1, (frame[1] + 1).toByte(), frame[2], 1))
                replies.send(if (writes.size == 1) byteArrayOf(1, frame[1], frame[2])
                    else byteArrayOf(1, frame[1], frame[2], 13))
                yield() // Notification is received before this simulated ATT completion.
            }
        }, retry = { assertEquals(ErrorCode.DATA_LOSS, it); retries++ })
        assertEquals(1, retries)
        assertEquals(12, writes.size)
        assertArrayEquals(writes[0], writes[1])
        assertArrayEquals(byteArrayOf(3), writes.last())
        assertTrue(replies.tryReceive().isFailure)
        replies.cancel()
    }

    @Test fun rejectedStatusCompletesTableButSuppressesEndAndTransportFailureDoesNotRetry() = runBlocking {
        val replies = Channel<ByteArray>(64)
        var writes = 0
        try {
            Gen1AssignmentExchange.exchange(1, false, replies, write = { frame ->
                writes++
                assertTrue(frame.size > 1)
                replies.send(byteArrayOf(1, frame[1], frame[2], if (writes == 1) 12 else 1))
            }, retry = { fail("Valid rejected status must not retry") })
            fail("Rejected table must fail")
        } catch (failure: GattFailure) { assertEquals(ErrorCode.FAILED_PRECONDITION, failure.code) }
        assertEquals(10, writes)
        try {
            Gen1AssignmentExchange.exchange(1, false, replies,
                write = { throw GattFailure(ErrorCode.IO) }, retry = { fail("ATT failure must not retry") })
            fail("ATT failure must propagate")
        } catch (failure: GattFailure) { assertEquals(ErrorCode.IO, failure.code) }
        replies.cancel()
    }

    @Test fun receiptTimeoutRetriesSameFrameAndCallerCancellationStopsWaiting() = runBlocking {
        val replies = Channel<ByteArray>(64)
        var first: ByteArray? = null
        var writes = 0
        var retries = 0
        Gen1AssignmentExchange.exchange(7, false, replies, write = { frame ->
            writes++
            if (writes == 1) first = frame.copyOf()
            else if (frame.size > 1) {
                if (writes == 2) assertArrayEquals(first, frame)
                replies.send(byteArrayOf(1, frame[1], frame[2], 1))
            }
        }, retry = { assertEquals(ErrorCode.TIMEOUT, it); retries++ })
        assertEquals(1, retries)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            Gen1AssignmentExchange.exchange(1, false, replies,
                write = { started.complete(Unit) }, retry = { fail("Cancelled caller must stop") })
            fail("Cancelled exchange must not complete")
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        replies.cancel()
    }

    @Test fun malformedReplyExhaustionFailsInsteadOfCompletingRegistration() = runBlocking {
        val replies = Channel<ByteArray>(1)
        var writes = 0
        var retries = 0
        try {
            Gen1AssignmentExchange.exchange(7, false, replies, write = { frame ->
                writes++
                assertEquals(7, frame[1].toInt())
                replies.send(byteArrayOf(1, 7, 0))
            }, retry = { retries++ })
            fail("Exhaustion must never reach completion")
        } catch (failure: GattFailure) { assertEquals(ErrorCode.DATA_LOSS, failure.code) }
        assertEquals(10_000, writes)
        assertEquals(9_999, retries)
        replies.cancel()
    }
}
