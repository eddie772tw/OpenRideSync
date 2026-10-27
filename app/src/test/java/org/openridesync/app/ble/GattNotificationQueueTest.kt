package org.openridesync.app.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode

/** Host queue evidence, not Android callback/CCCD or peripheral acceptance. */
class GattNotificationQueueTest {
    @Test fun boundedCopiesAndFailureDiscardsBufferedValues() = runBlocking {
        var closed = 0
        val queue = GattNotificationQueue { closed++ }
        val source = byteArrayOf(1, 2)
        assertTrue(queue.offer(source))
        source[0] = 9
        assertArrayEquals(byteArrayOf(1, 2), queue.values.receive())
        repeat(64) { assertTrue(queue.offer(byteArrayOf(it.toByte()))) }
        assertFalse(queue.offer(byteArrayOf(65)))
        val cause = GattFailure(ErrorCode.RESOURCE_EXHAUSTED)
        queue.cancel(cause)
        val result = queue.values.tryReceive()
        assertTrue(result.isClosed)
        assertSame(cause, (result.exceptionOrNull() as CancellationException).cause)
        assertEquals(1, closed)
    }

    @Test fun receiverCancellationClosesOnceAndRejectsOversizedPayload() {
        var closed = 0
        val queue = GattNotificationQueue { closed++ }
        assertFalse(queue.offer(ByteArray(513)))
        assertTrue(queue.offer(byteArrayOf()))
        queue.values.cancel()
        queue.cancel(null)
        assertEquals(1, closed)
        assertFalse(queue.offer(byteArrayOf(1)))
    }
}
