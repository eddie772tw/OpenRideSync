package org.openridesync.app.vehicles

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.wire.HondaWire.Model

@OptIn(ExperimentalCoroutinesApi::class)
class GarageMutationTest {
    @get:Rule val temp = TemporaryFolder()
    private val mac = "02:AA:BB:CC:DD:01"
    private fun store(): PairedVehicleStore = PairedVehicleStore(temp.newFolder()).also {
        val model = Model.newBuilder().setModelLabel("fixture").setModelSeriesLabel("series").build()
        it.upsert(mac, "fixture", "series", "fw", "bike", model, 0, PairedVehicleStore.State.PAIRED)
    }
    @Test fun explicitWriteOnceAndPersistedRemoval() = runTest {
        val disk = store(); var successes = 0; var failures = 0; var busy = 0
        val mutation = GarageMutation(this, { GarageIdentity("a", 1) }, { disk }, StandardTestDispatcher(testScheduler))
        assertTrue(mutation.start(GarageChange.FORGET, mac, { true }, { busy++ }, { assertTrue(it.isEmpty()); successes++ }, { failures++ }))
        assertFalse(mutation.start(GarageChange.FORGET, mac, { true }, {}, {}, {}))
        runCurrent()
        assertEquals(1, busy); assertEquals(1, successes); assertEquals(0, failures); assertTrue(disk.read().isEmpty())
    }
    @Test fun identityLossBeforeDispatchDoesNotWriteOrPublish() = runTest {
        val disk = store(); var owner: GarageIdentity? = GarageIdentity("a", 1); var callbacks = 0
        val mutation = GarageMutation(this, { owner }, { disk }, StandardTestDispatcher(testScheduler))
        mutation.start(GarageChange.FORGET, mac, { true }, {}, { callbacks++ }, { callbacks++ })
        owner = GarageIdentity("b", 2); runCurrent()
        assertEquals(1, disk.read().size); assertEquals(0, callbacks)
        owner = null
        assertFalse(mutation.start(GarageChange.FORGET, mac, { true }, {}, {}, {}))
    }
    @Test fun cancelAndRideStateChangeBeforeCommitPreserveDisk() = runTest {
        val disk = store(); var allowed = true; var failures = 0
        val mutation = GarageMutation(this, { GarageIdentity("a", 1) }, { disk }, StandardTestDispatcher(testScheduler))
        mutation.start(GarageChange.FORGET, mac, { allowed }, {}, {}, { failures++ })
        mutation.cancel(); runCurrent(); assertEquals(1, disk.read().size); assertEquals(0, failures)
        mutation.start(GarageChange.FORGET, mac, { allowed }, {}, {}, { failures++ })
        allowed = false; runCurrent(); assertEquals(1, disk.read().size); assertEquals(1, failures)
        assertFalse(mutation.start(GarageChange.FORGET, mac, { allowed }, {}, {}, {}))
    }
    @Test fun missingTargetFailsAndCanExplicitlyRetry() = runTest {
        val disk = store(); var failures = 0; var successes = 0
        val mutation = GarageMutation(this, { GarageIdentity("a", 1) }, { disk }, StandardTestDispatcher(testScheduler))
        mutation.start(GarageChange.PRIMARY, "02:AA:BB:CC:DD:99", { true }, {}, {}, { failures++ })
        runCurrent(); assertEquals(1, failures); assertEquals(1, disk.read().size)
        assertTrue(mutation.start(GarageChange.PRIMARY, mac, { true }, {}, { successes++ }, { failures++ }))
        runCurrent(); assertEquals(1, successes); assertEquals(1, failures)
    }
    @Test fun parentScopeCancellationNeverPublishes() = runTest {
        val disk = store(); val parent = Job(); var callbacks = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mutation = GarageMutation(CoroutineScope(parent + dispatcher), { GarageIdentity("a", 1) }, { disk }, dispatcher)
        mutation.start(GarageChange.FORGET, mac, { true }, {}, { callbacks++ }, { callbacks++ })
        parent.cancel(); runCurrent()
        assertEquals(0, callbacks); assertEquals(1, disk.read().size)
    }

    @Test fun cancellationDuringSynchronousCommitDoesNotClaimRollbackOrPublish() = runTest {
        val disk = store(); val parent = Job(); var callbacks = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mutation = GarageMutation(CoroutineScope(parent + dispatcher), { GarageIdentity("a", 1) },
            { parent.cancel(); disk }, dispatcher)
        mutation.start(GarageChange.FORGET, mac, { true }, {}, { callbacks++ }, { callbacks++ })
        runCurrent()
        assertTrue(disk.read().isEmpty()) // A started atomic commit is not reversible by cancellation.
        assertEquals(0, callbacks)
    }

    @Test fun cancelledOwnerWithIoFailureNeverPublishes() = runTest {
        val parent = Job(); var callbacks = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mutation = GarageMutation(CoroutineScope(parent + dispatcher), { GarageIdentity("a", 1) },
            { parent.cancel(); throw java.io.IOException("fixture") }, dispatcher)
        mutation.start(GarageChange.FORGET, mac, { true }, {}, { callbacks++ }, { callbacks++ })
        runCurrent(); assertEquals(0, callbacks)
    }

}
