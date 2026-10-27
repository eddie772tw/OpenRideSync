package org.openridesync.app.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** One bounded stream. Cancellation discards buffered data from an invalidated session. */
internal class GattNotificationQueue(onClosed: () -> Unit) {
    private val channel = Channel<ByteArray>(64)
    val values: ReceiveChannel<ByteArray> = channel
    init { channel.invokeOnClose { onClosed() } }
    fun offer(bytes: ByteArray): Boolean = bytes.size <= 512 && channel.trySend(bytes.copyOf()).isSuccess
    fun cancel(cause: Throwable?) { channel.cancel(CancellationException("GATT notification stream closed", cause)) }
}
