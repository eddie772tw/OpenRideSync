package org.openridesync.app.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

/** Listen before write; an expired receipt wait does not cancel an outstanding ATT operation. */
internal suspend fun exchangeGattReceipt(
    frame: ByteArray,
    notifications: ReceiveChannel<ByteArray>,
    write: suspend (ByteArray) -> Unit,
    matches: (ByteArray, ByteArray) -> Boolean,
): ByteArray? = supervisorScope {
    val receipt = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(2_000) {
            var bytes: ByteArray
            do { bytes = notifications.receive() } while (!matches(frame, bytes))
            bytes
        }
    }
    try {
        write(frame)
        receipt.await()
    } finally { receipt.cancel() }
}
