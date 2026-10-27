package org.openridesync.app.vehicles

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VehicleImageRequestsTest {
    private val key = VehicleImageRequests.Key("synthetic-user", 1L, "image")

    @Test fun concurrentColdConsumersReceiveSameSingleResult() = runBlocking {
        val requests = VehicleImageRequests()
        try {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val consumers = (1..12).map { async {
                requests.load(key, requests.epoch.value) { calls.incrementAndGet(); gate.await(); byteArrayOf(1, 2, 3) }
            } }
            yield(); gate.complete(Unit)
            consumers.awaitAll().forEach { assertArrayEquals(byteArrayOf(1, 2, 3), it) }
            assertEquals(1, calls.get())
        } finally { requests.close() }
    }

    @Test fun failedResultSurvivesRecompositionUntilExplicitRetry() = runBlocking {
        val requests = VehicleImageRequests()
        try {
            var calls = 0
            repeat(5) { assertNull(requests.load(key, requests.epoch.value) { calls++; error("offline") }) }
            assertEquals(1, calls)
            val before = requests.changes.value
            requests.retry(key, requests.epoch.value)
            assertEquals(before + 1, requests.changes.value)
            assertArrayEquals(byteArrayOf(7), requests.load(key, requests.epoch.value) { calls++; byteArrayOf(7) })
            assertEquals(2, calls)
            // A stale retry click cannot restart a successful result.
            requests.retry(key, requests.epoch.value)
            requests.load(key, requests.epoch.value) { error("must share success") }
            assertEquals(before + 1, requests.changes.value)
        } finally { requests.close() }
    }

    @Test fun consumerCancellationDoesNotCancelOtherTiles() = runBlocking {
        val requests = VehicleImageRequests()
        try {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val first = async { requests.load(key, requests.epoch.value) { started.complete(Unit); gate.await(); byteArrayOf(4) } }
            started.await()
            first.cancelAndJoin()
            val other = async { requests.load(key, requests.epoch.value) { error("duplicate") } }
            gate.complete(Unit)
            assertArrayEquals(byteArrayOf(4), other.await())
        } finally { requests.close() }
    }

    @Test fun resetCancelsOldWorkAndStaleRetryCannotTouchNewIdentity() = runBlocking {
        val requests = VehicleImageRequests()
        try {
            val old = requests.epoch.value
            val started = CompletableDeferred<Unit>()
            val oldConsumer = async { requests.load(key, old) { started.complete(Unit); awaitCancellation() } }
            started.await()
            requests.reset()
            try { oldConsumer.await(); fail("reset must cancel") } catch (_: CancellationException) { }
            assertFalse(requests.isCurrent(old))
            requests.retry(key, old)
            assertArrayEquals(byteArrayOf(9), requests.load(key, requests.epoch.value) { byteArrayOf(9) })
            try { requests.load(key, old) { error("stale loader") }; fail("stale epoch must cancel") }
            catch (_: CancellationException) { }
        } finally { requests.close() }
    }

    @Test fun imageResultIsPartitionedByAccountAndRevision() = runBlocking {
        val requests = VehicleImageRequests()
        try {
            assertArrayEquals(byteArrayOf(1), requests.load(key, 0) { byteArrayOf(1) })
            assertArrayEquals(byteArrayOf(2), requests.load(key.copy(uid = "other"), 0) { byteArrayOf(2) })
            assertArrayEquals(byteArrayOf(3), requests.load(key.copy(revision = 2), 0) { byteArrayOf(3) })
        } finally { requests.close() }
    }

    @Test fun retryDuringPendingRequestDoesNotDuplicateFetch() = runBlocking {
        val requests = VehicleImageRequests()
        try {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val first = async { requests.load(key, 0) { started.complete(Unit); gate.await(); byteArrayOf(5) } }
            started.await(); requests.retry(key, 0)
            val second = async { requests.load(key, 0) { error("duplicate") } }
            gate.complete(Unit)
            assertArrayEquals(first.await(), second.await())
        } finally { requests.close() }
    }

    @Test fun completedBytesUseLruBudgetAndEvictionOnlyReadsDisk() = runBlocking {
        val requests = VehicleImageRequests(maxRetainedBytes = 4)
        try {
            val a = key.copy(imageId = "a")
            val b = key.copy(imageId = "b")
            val c = key.copy(imageId = "c")
            var network = 0
            var disk = 0
            requests.load(a, 0) { network++; byteArrayOf(1, 1) }
            requests.load(b, 0) { network++; byteArrayOf(2, 2) }
            requests.load(a, 0) { error("a should still be retained") } // a becomes most recent.
            requests.load(c, 0) { network++; byteArrayOf(3, 3) } // b is evicted.
            assertEquals(4L, requests.retainedByteCount)
            assertArrayEquals(byteArrayOf(2, 2), requests.load(b, 0,
                readCached = { disk++; byteArrayOf(2, 2) }) { network++; error("eviction must not fetch") })
            assertEquals(3, network)
            assertEquals(1, disk)
            assertEquals(4L, requests.retainedByteCount)
        } finally { requests.close() }
    }

    @Test fun evictedSuccessWithMissingDiskRequiresExplicitRetry() = runBlocking {
        val requests = VehicleImageRequests(maxRetainedBytes = 1)
        try {
            var network = 0
            assertArrayEquals(byteArrayOf(1, 2), requests.load(key, 0) { network++; byteArrayOf(1, 2) })
            assertEquals(0L, requests.retainedByteCount)
            repeat(3) {
                assertNull(requests.load(key, 0, readCached = { null }) { network++; byteArrayOf(3) })
            }
            assertEquals(1, network)
            requests.retry(key, 0)
            assertArrayEquals(byteArrayOf(3), requests.load(key, 0) { network++; byteArrayOf(3) })
            assertEquals(2, network)
            assertEquals(1L, requests.retainedByteCount)
        } finally { requests.close() }
    }

    @Test fun evictionDoesNotInvalidateConsumersAlreadyAwaitingSharedBytes() = runBlocking {
        val requests = VehicleImageRequests(maxRetainedBytes = 0)
        try {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val first = async { requests.load(key, 0) { started.complete(Unit); gate.await(); byteArrayOf(8, 9) } }
            started.await()
            val second = async { requests.load(key, 0) { error("must join first") } }
            yield()
            gate.complete(Unit)
            assertArrayEquals(byteArrayOf(8, 9), first.await())
            assertArrayEquals(byteArrayOf(8, 9), second.await())
            assertEquals(0L, requests.retainedByteCount)
        } finally { requests.close() }
    }

}
