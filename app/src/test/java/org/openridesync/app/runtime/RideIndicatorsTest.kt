package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openridesync.app.ble.*
import org.openridesync.diagnostics.DiagnosticJournal

@OptIn(ExperimentalCoroutinesApi::class)
class RideIndicatorsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun masksAndSignedWireBytesMatchV11Registry() {
        assertEquals(63, rideIndicatorMask(true, true, true, true, true, false))
        assertEquals(0, rideIndicatorMask(false, false, false, false, false, null))
        assertEquals(0, rideIndicatorMask(false, false, false, false, false, true))
        for (mask in 0..255) assertArrayEquals(
            byteArrayOf(3, -1, if (mask < 128) 0 else -1, mask.toByte()),
            Gen1ControlPoint.activeIndicators(255, mask))
        assertThrows(IllegalArgumentException::class.java) { Gen1ControlPoint.activeIndicators(256, 0) }
        assertThrows(IllegalArgumentException::class.java) { Gen1ControlPoint.activeIndicators(0, -1) }
    }

    @Test fun capabilityReadyModeAndOwnerFenceTheSharedQueue() = runTest {
        for (capabilities in listOf(null, 0, 2, 4, 128, 1, 7)) {
            DiagnosticJournal(temporary.newFolder()).use { journal ->
                val functions = Channel<ByteArray>(Channel.UNLIMITED)
                val replies = Channel<ByteArray>(Channel.UNLIMITED)
                val writes = mutableListOf<ByteArray>()
                val monitor = Gen1InputMonitor(journal, journal.newOperation())
                val controls = Gen1ControlExchange(replies, { frame ->
                    writes.add(frame.copyOf())
                    replies.send(byteArrayOf(3, frame[1], 1))
                }, journal, journal.newOperation())
                val masks = MutableStateFlow(4)
                val ready = MutableStateFlow(false)
                var current = true
                val reader = backgroundScope.launch { monitor.consumeFunctions(functions) }
                val worker = backgroundScope.launch { controls.run() }
                val indicators = backgroundScope.launch {
                    runRideIndicators(monitor, controls, capabilities, masks, ready) { current }
                }
                functions.send(byteArrayOf(9)); runCurrent()
                assertTrue(writes.isEmpty()) // SAB alone is insufficient before startup/security owner.
                ready.value = true; runCurrent()
                val supported = capabilities != null && capabilities and 1 != 0
                assertEquals(if (supported) 1 else 0, writes.size)
                if (supported) assertArrayEquals(byteArrayOf(3, 0, 0, 4), writes.single())
                functions.send(byteArrayOf(10)); masks.value = 8; runCurrent()
                assertEquals(if (supported) 1 else 0, writes.size)
                functions.send(byteArrayOf(9)); runCurrent()
                if (supported) assertArrayEquals(byteArrayOf(3, 1, 0, 8), writes.last())
                current = false; masks.value = 16; runCurrent()
                assertEquals(if (supported) 2 else 0, writes.size)
                indicators.cancelAndJoin()
                controls.stopGracefully()
                assertEquals(if (supported) 2 else 0, controls.nextRequestIdForShutdown())
                worker.cancelAndJoin(); reader.cancelAndJoin()
                functions.cancel(); replies.cancel()
            }
        }
    }
}
