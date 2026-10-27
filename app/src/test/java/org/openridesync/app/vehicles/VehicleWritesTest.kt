package org.openridesync.app.vehicles

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VehicleWritesTest {
    @Test fun cancelledOldCommitCannotOverwriteNewCommitAndCancelledWaiterCannotWrite() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newest = AtomicInteger(0)
        val old = launch(Dispatchers.IO) {
            VehicleWrites.commit {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                newest.set(1)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            old.cancel()
            val waiterEntered = CountDownLatch(1)
            val cancelled = launch(Dispatchers.IO) {
                waiterEntered.countDown()
                VehicleWrites.commit { newest.set(99) }
            }
            assertTrue(waiterEntered.await(5, TimeUnit.SECONDS))
            cancelled.cancelAndJoin()
            val next = launch(Dispatchers.IO) { VehicleWrites.commit { newest.set(2) } }
            release.countDown()
            withTimeout(5_000) { joinAll(old, next) }
            assertEquals(2, newest.get())
        } finally {
            release.countDown()
            old.cancelAndJoin()
        }
    }
}
