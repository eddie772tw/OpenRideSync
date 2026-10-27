package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.GattFailure
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode

/** Production retry/window orchestration with synthetic failures/motion, never vehicle evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideRecoveryTest {
    private fun disconnected() = GattFailure(ErrorCode.UNAVAILABLE, GattFailure.Origin.DISCONNECTED)
    private fun deadline() = GattFailure(ErrorCode.TIMEOUT, GattFailure.Origin.CONNECT_DEADLINE)
    private fun setup() = GattFailure(ErrorCode.IO, GattFailure.Origin.OPERATION_STATUS, 133,
        boundary = GattFailure.Boundary.INITIALIZATION)

    @Test fun mixedFailuresUseOneOrdinalAndDownstreamFailuresNeverRetry() = runTest {
        val failures = listOf(disconnected(), deadline(), setup(), setup())
        val retried = mutableListOf<Int>()
        var attempts = 0
        val error = runCatching {
            reconnectRide({ _, ordinal -> retried.add(ordinal) }) { throw failures[attempts++] }
        }.exceptionOrNull()
        assertSame(failures.last(), error)
        assertEquals(listOf(1, 2, 3), retried)
        assertEquals(4, attempts)
        for (boundary in GattFailure.Boundary.entries) {
            for (origin in GattFailure.Origin.entries) {
                val failure = GattFailure(ErrorCode.IO, origin, boundary = boundary)
                var called = 0
                var retry = 0
                val stopped = runCatching {
                    reconnectRide({ _, _ -> retry++ }) { if (called++ == 0) throw failure else "ok" }
                }.exceptionOrNull()
                val allowed = boundary == GattFailure.Boundary.TRANSPORT && origin in listOf(
                    GattFailure.Origin.DISCONNECTED, GattFailure.Origin.CONNECTION_STATUS, GattFailure.Origin.CONNECT_DEADLINE) ||
                    boundary == GattFailure.Boundary.INITIALIZATION && origin in listOf(
                        GattFailure.Origin.OPERATION_STATUS, GattFailure.Origin.OPERATION_REJECTED, GattFailure.Origin.OPERATION_TIMEOUT)
                assertEquals("$boundary $origin", if (allowed) 1 else 0, retry)
                if (allowed) assertNull(stopped) else assertSame(failure, stopped)
            }
        }
        for (code in listOf(ErrorCode.ALREADY_EXISTS, ErrorCode.TIMEOUT, ErrorCode.IO, ErrorCode.UNAVAILABLE)) {
            val local = GattFailure(code)
            assertSame(local, runCatching {
                reconnectRide({ _, _ -> fail("Do not infer Rx exceptions from a local code") }) { throw local }
            }.exceptionOrNull())
        }
    }

    @Test fun retryWaitsForStructuredCleanupAndCancellationStopsAcquisition() = runTest {
        var attempts = 0
        var cleaned = 0
        val waiting = async {
            reconnectRide({ _, _ -> assertEquals(attempts, cleaned) }) {
                attempts++
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) { delay(50); cleaned++ } }
                    }
                    throw disconnected()
                }
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(2, attempts)
        waiting.cancelAndJoin()
        val count = attempts
        advanceTimeBy(60_001)
        assertEquals(count, attempts)
        assertEquals(attempts, cleaned)
    }

    @Test fun nativeFiniteExhaustionIsExplicitAndCannotSpinForever() = runTest {
        var attempts = 0
        var retries = 0
        val failure = runCatching {
            reconnectRide({ _, _ -> retries++ }) { attempts++; throw deadline() }
        }.exceptionOrNull() as RideFailure
        assertEquals("RECONNECT_LIMIT", failure.code)
        assertEquals(10_000, attempts)
        assertEquals(9_999, retries)
    }

    private fun CoroutineScope.owner(sample: suspend () -> RideMotion, pending: () -> Unit = {}):
        Pair<Deferred<Result<Nothing>>, Deferred<RideRecovery>> {
        val ready = CompletableDeferred<RideRecovery>()
        val result = async {
            runCatching {
                coroutineScope {
                    val recovery = RideRecovery(this, sample, pending)
                    ready.complete(recovery)
                    try { awaitCancellation() } finally { recovery.close() }
                }
            }
        }
        return result to ready
    }

    @Test fun rawRecoveryCancelsWindowBeforeReadinessAndNewLossGetsNewWindow() = runTest {
        var reads = 0
        var windows = 0
        val (result, ready) = owner({ reads++; awaitCancellation() }, { windows++ })
        val recovery = ready.await()
        runCurrent()
        assertEquals(0, reads) // No initial disconnected snapshot.
        recovery.rawState(false)
        recovery.rawState(false)
        recovery.retrying()
        runCurrent()
        assertEquals(1, reads)
        assertEquals(1, windows)
        recovery.rawState(true)
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertFalse(result.isCompleted) // Raw connect cancels both timeout and in-flight fix.
        recovery.rawState(false)
        recovery.retrying()
        runCurrent()
        assertEquals(2, windows)
        recovery.close()
        recovery.rawState(false)
        recovery.rawState(true)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(2, reads)
        assertFalse(result.isCompleted)
        result.cancelAndJoin()
    }

    @Test fun movingRecoveryUsesThreeOverlappingTicksThenTheOriginalSixtySecondWindow() = runTest {
        val times = mutableListOf<Long>()
        val (result, ready) = owner({
            times.add(testScheduler.currentTime)
            if (times.size == 1) RideMotion(30f, 3.5f)
            else { delay(1_200); RideMotion(10f, 3.5f) }
        })
        ready.await().apply { rawState(false); retrying() }
        runCurrent()
        advanceTimeBy(4_201)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), times)
        assertFalse(result.isCompleted)
        advanceTimeBy(55_799)
        runCurrent()
        assertEquals("RECONNECT_WINDOW_EXPIRED", (result.await().exceptionOrNull() as RideFailure).code)
    }

    @Test fun motionBoundariesAndProbeErrorsEndRecoveryWithoutReplayingCommands() = runTest {
        for (sample in listOf(RideMotion(31f, 10f), RideMotion(10f, 3.4f), RideMotion(Float.NaN, 10f),
            RideMotion(10f, Float.POSITIVE_INFINITY), RideMotion(-1f, 10f), RideMotion(30f, 3.5f))) {
            val (result, ready) = owner({ sample })
            ready.await().apply { rawState(false); retrying() }
            advanceUntilIdle()
            assertEquals("RECONNECT_STOPPED", (result.await().exceptionOrNull() as RideFailure).code)
        }
        for (hangOn in listOf(1, 2)) {
            var reads = 0
            val (result, ready) = owner({
                if (++reads == hangOn) awaitCancellation()
                RideMotion(10f, 10f)
            })
            ready.await().apply { rawState(false); retrying() }
            advanceUntilIdle()
            assertEquals("RECONNECT_STOPPED", (result.await().exceptionOrNull() as RideFailure).code)
            assertTrue(testScheduler.currentTime < 60_000)
        }
        val (result, ready) = owner({ throw RideFailure("RECONNECT_LOCATION_REQUIRED") })
        ready.await().apply { rawState(false); retrying() }
        runCurrent()
        assertEquals("RECONNECT_LOCATION_REQUIRED", (result.await().exceptionOrNull() as RideFailure).code)
    }

    @Test fun queuedRawConnectWinsSynchronousProbeFailureAndNonretryableTeardownKeepsItsCause() = runTest {
        val (result, ready) = owner({ throw RideFailure("RECONNECT_LOCATION_REQUIRED") })
        ready.await().apply { rawState(false); retrying(); rawState(true) }
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertFalse(result.isCompleted)
        result.cancelAndJoin()

        val terminal = GattFailure(ErrorCode.IO, GattFailure.Origin.OPERATION_STATUS,
            boundary = GattFailure.Boundary.CONTROL)
        var probes = 0
        val error = runCatching {
            coroutineScope {
                val recovery = RideRecovery(this, { probes++; throw RideFailure("RECONNECT_LOCATION_REQUIRED") }, {})
                try {
                    reconnectRide({ _, _ -> recovery.retrying() }) {
                        recovery.rawState(false) // Real teardown of a nonretryable operation.
                        yield()
                        throw terminal
                    }
                } finally { recovery.close() }
            }
        }.exceptionOrNull()
        assertSame(terminal, error)
        assertEquals(0, probes)
    }
}
