package org.openridesync.app.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FavoriteStartGateTest {
    @Test fun overlapIsRejectedAndSuccessCleanupAllowsANewAttempt() {
        val gate = FavoriteStartGate()
        val first = checkNotNull(gate.acquire())
        assertNull(gate.acquire())
        gate.release(first)
        assertNotNull(gate.acquire())
    }

    @Test fun oldCleanupCannotReleaseANewerOwner() {
        val gate = FavoriteStartGate()
        val first = checkNotNull(gate.acquire())
        gate.release(first)
        val second = checkNotNull(gate.acquire())
        gate.release(first)
        assertNull(gate.acquire())
        gate.release(second)
        assertNotNull(gate.acquire())
    }

    @Test fun anotherGatesTicketCannotReleaseThisOwner() {
        val gate = FavoriteStartGate()
        checkNotNull(gate.acquire())
        gate.release(checkNotNull(FavoriteStartGate().acquire()))
        assertNull(gate.acquire())
    }

    @Test fun concurrentEntrantsHaveExactlyOneWinnerWithoutQueuedWork() {
        val gate = FavoriteStartGate()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map {
                pool.submit<FavoriteStartGate.Ticket?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    gate.acquire()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val tickets = results.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, tickets.size)
            gate.release(tickets.single())
            assertNotNull(gate.acquire())
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
