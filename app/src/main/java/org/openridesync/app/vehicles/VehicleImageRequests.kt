package org.openridesync.app.vehicles

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared in-flight work plus bounded successes; eviction never grants another network attempt. */
internal class VehicleImageRequests(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxRetainedBytes: Int = 16 * 1024 * 1024,
) {
    init { require(maxRetainedBytes >= 0) }
    internal data class Key(val uid: String, val revision: Long, val imageId: String)
    private val attempted = mutableSetOf<Key>()
    private val failed = mutableSetOf<Key>()
    private val inFlight = mutableMapOf<Key, Deferred<ByteArray?>>()
    private val successes = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private var retainedBytes = 0L
    private val mutableEpoch = MutableStateFlow(0L)
    private val mutableChanges = MutableStateFlow(0L)
    val epoch = mutableEpoch.asStateFlow()
    val changes = mutableChanges.asStateFlow()
    internal val retainedByteCount: Long @Synchronized get() = retainedBytes

    suspend fun load(key: Key, expectedEpoch: Long,
        readCached: suspend () -> ByteArray? = { null }, loader: suspend () -> ByteArray): ByteArray? {
        val shared = synchronized(this) {
            if (!isCurrent(expectedEpoch)) throw CancellationException("Image scope changed")
            successes[key]?.let { return it }
            if (key in failed) return null
            inFlight.getOrPut(key) {
                val mayFetch = attempted.add(key)
                // LAZY ensures the map owns the deferred before even an immediate loader completes.
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val bytes = try { if (mayFetch) loader() else readCached() }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        }
                        synchronized(this@VehicleImageRequests) {
                            if (!isCurrent(expectedEpoch)) throw CancellationException("Image scope changed")
                            if (bytes == null) failed.add(key) else retain(key, bytes)
                        }
                        bytes // Existing waiters keep this result even when too large for the LRU.
                    } finally {
                        synchronized(this@VehicleImageRequests) {
                            if (isCurrent(expectedEpoch)) inFlight.remove(key)
                        }
                    }
                }
            }
        }
        val result = shared.await()
        if (!isCurrent(expectedEpoch)) throw CancellationException("Image scope changed")
        return result
    }

    /** Called under this owner's monitor. Attempt metadata deliberately outlives byte eviction. */
    private fun retain(key: Key, bytes: ByteArray) {
        successes.put(key, bytes)?.let { retainedBytes -= it.size }
        retainedBytes += bytes.size
        val oldest = successes.entries.iterator()
        while (retainedBytes > maxRetainedBytes && oldest.hasNext()) {
            retainedBytes -= oldest.next().value.size
            oldest.remove()
        }
    }

    @Synchronized fun retry(key: Key, expectedEpoch: Long) {
        if (!isCurrent(expectedEpoch) || key in inFlight || !failed.remove(key)) return
        attempted.remove(key)
        mutableChanges.value++ // Every visible consumer joins the same explicit replacement attempt.
    }
    @Synchronized fun isCurrent(expectedEpoch: Long): Boolean = expectedEpoch == mutableEpoch.value
    @Synchronized fun reset() {
        mutableEpoch.value++
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        successes.clear()
        retainedBytes = 0
        attempted.clear()
        failed.clear()
    }
    fun close() { reset(); scope.cancel() }
}
