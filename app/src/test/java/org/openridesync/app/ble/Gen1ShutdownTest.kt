package org.openridesync.app.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode

class Gen1ShutdownTest {
    @Test fun capabilityOrderAndSharedAllocatorMatchOemConstructionOrder() {
        var next = 255
        val frames = Gen1Shutdown.frames(255, { next.also { next = (next + 1) and 255 } }, "RoadSync", "關閉")
        assertArrayEquals(byteArrayOf(3,0,0,0), frames[0])
        assertArrayEquals(byteArrayOf(2,1,0,0,0), frames[1])
        val expected = byteArrayOf(1,-1,16,0,1,16,0,0,0,0,0,8) +
            "RoadSync".toByteArray() + byteArrayOf(0,6) + "關閉".toByteArray() + byteArrayOf(0)
        assertArrayEquals(expected, frames[2])
        assertEquals(2, next)
        var allocated = 0
        assertTrue(Gen1Shutdown.frames(128, { allocated++ }, "brand", "off").isEmpty())
        assertEquals(1, allocated) // Unknown bits do not enable a new capability; menu ID still consumed.
        assertEquals(listOf(3), Gen1Shutdown.frames(1, { 7 }, "brand", "off").map { it[0].toInt() })
        assertArrayEquals(
            Gen1Shutdown.frames(4, { 0 }, "車".repeat(10), "😀".repeat(7)).single(),
            Gen1Shutdown.frames(4, { 0 }, "車".repeat(100), "😀".repeat(100)).single())
    }

    @Test fun directExchangeCorrelatesRetainsNonSuccessAndStopsOnDisconnect() = runBlocking {
        val replies = Channel<ByteArray>(16)
        var next = 0
        var writes = 0
        val receipts = Gen1Shutdown.exchange(3, { next++ }, "brand", "off", replies) { frame ->
            writes++
            replies.send(frame.copyOf())
            replies.send(byteArrayOf(11, (frame[1] + 1).toByte(), 1))
            replies.send(byteArrayOf(11, frame[1], 12))
        }
        assertEquals(2, writes)
        assertEquals(listOf(12,12), receipts.map { it.status() })
        var live = true
        writes = 0
        try {
            Gen1Shutdown.exchange(3, { next++ }, "brand", "off", replies) { frame ->
                if (!live) throw GattFailure(ErrorCode.UNAVAILABLE)
                writes++; live = false; replies.send(byteArrayOf(11, frame[1], 1))
            }
            fail("Disconnect must stop remaining writes")
        } catch (failure: GattFailure) { assertEquals(ErrorCode.UNAVAILABLE, failure.code) }
        assertEquals(1, writes)
        try {
            Gen1Shutdown.exchange(1, { next++ }, "brand", "off", replies) { frame ->
                replies.send(byteArrayOf(99, frame[1], 1))
            }
            fail("Malformed correlated receipt must fail")
        } catch (failure: GattFailure) { assertEquals(ErrorCode.DATA_LOSS, failure.code) }
        replies.cancel()
    }

    @Test fun deadlineBoundsWriteAndCallerCancellationDoesNotBecomeSuccess() = runBlocking {
        val replies = Channel<ByteArray>()
        try {
            withTimeout(3_000) {
                Gen1Shutdown.exchange(1, { 0 }, "brand", "off", replies) { awaitCancellation() }
            }
            fail("Expected bounded write timeout")
        } catch (failure: GattFailure) { assertEquals(ErrorCode.TIMEOUT, failure.code) }
        val entered = CompletableDeferred<Unit>()
        val caller = async {
            Gen1Shutdown.exchange(1, { 0 }, "brand", "off", replies) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await(); caller.cancel()
        try { caller.await(); fail("Expected caller cancellation") } catch (_: CancellationException) {}
        replies.cancel()
    }
}
