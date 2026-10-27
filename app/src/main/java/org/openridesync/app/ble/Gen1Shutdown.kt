package org.openridesync.app.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode
import org.openridesync.gen1.ScreenEncoder

/** Explicit user-disconnect only. Caller must stop/join the normal control worker first. */
internal object Gen1Shutdown {
    fun frames(capabilityBits: Int, nextRequestId: () -> Int, brand: String, off: String): List<ByteArray> {
        require(capabilityBits in 0..255)
        fun id(): Int = nextRequestId().also { require(it in 0..255) }
        fun text(value: String): ScreenEncoder.Text {
            val bytes = Gen1Projection.prepareText(value).toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty())
            return ScreenEncoder.Text(bytes, 0)
        }
        val brandText = text(brand)
        val offText = text(off)
        val blank = ScreenEncoder.Icon(0, -1)
        // Meter.f builds the menu first, consuming its ID even when bit 4 is absent.
        val menu = ScreenEncoder.menu(id(), 16, ScreenEncoder.Text(byteArrayOf(), -1), 16,
            blank, blank, blank, blank, blank, brandText, offText)
        require(menu.size <= 512)
        return buildList {
            if (capabilityBits and 1 != 0) add(Gen1ControlPoint.activeIndicators(id(), 0))
            if (capabilityBits and 2 != 0) add(byteArrayOf(2, id().toByte(), 0, 0, 0))
            if (capabilityBits and 4 != 0) add(menu)
        }
    }

    /** Reuse the queue's allocator, never a new ID counter. No retry or reconnection.
     * Caller must verify live authenticated identity and skip cleanup after unexpected disconnect.
     * Each whole write/receipt is bounded to 2s (at most three frames). Unlike OEM h3's
     * unfiltered zip/error-to-false, correlate IDs and fail fast on malformed/transport/timeout;
     * retain valid non-success status bytes and continue, without claiming the vehicle cleared. */
    suspend fun exchange(
        capabilityBits: Int, nextRequestId: () -> Int, brand: String, off: String,
        notifications: ReceiveChannel<ByteArray>,
        write: suspend (ByteArray) -> Unit,
    ): List<Gen1ControlPoint.Receipt> {
        try {
            val commands = frames(capabilityBits, nextRequestId, brand, off)
            return commands.map { frame ->
                currentCoroutineContext().ensureActive()
                val bytes = withTimeoutOrNull(2_000) {
                    exchangeGattReceipt(frame, notifications, write, Gen1ControlPoint::matches)
                } ?: throw GattFailure(ErrorCode.TIMEOUT)
                try { Gen1ControlPoint.decodeReceipt(bytes) }
                catch (_: IllegalArgumentException) { throw GattFailure(ErrorCode.DATA_LOSS) }
            }
        } catch (failure: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw GattFailure(ErrorCode.TIMEOUT)
        } catch (failure: CancellationException) {
            throw (failure.gattFailureCause() ?: failure)
        }
    }
}
