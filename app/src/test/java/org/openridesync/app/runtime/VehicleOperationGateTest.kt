package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VehicleOperationGateTest {
    @Test fun contentionAndStaleReleasePreserveSingleOwner() {
        val gate = VehicleOperationGate()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val attempts = VehicleOperationGate.Kind.entries.map { kind -> pool.submit<VehicleOperationGate.Ticket?> {
                ready.countDown()
                check(start.await(2, TimeUnit.SECONDS))
                gate.tryAcquire(kind)
            } }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            val winners = attempts.mapNotNull { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            val old = winners.single()
            assertNull(gate.tryAcquire(VehicleOperationGate.Kind.RUNTIME))
            gate.release(VehicleOperationGate.Ticket(old.kind)) // Same kind is not ownership.
            assertNull(gate.tryAcquire(VehicleOperationGate.Kind.PAIRING))
            gate.release(old)
            val next = gate.tryAcquire(VehicleOperationGate.Kind.RUNTIME)!!
            gate.release(old) // Late duplicate completion must not release this newer ticket.
            assertNull(gate.tryAcquire(VehicleOperationGate.Kind.PAIRING))
            gate.release(next)
            assertNotNull(gate.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        } finally { pool.shutdownNow() }
    }
}
