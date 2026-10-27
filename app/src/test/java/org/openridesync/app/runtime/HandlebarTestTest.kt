package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ble.Gen1InputMonitor
import java.util.UUID

/** Synthetic model checks only. These tests never produce a real vehicle acceptance report. */
class HandlebarTestTest {
    private class Fixture {
        var now = 1_000_000_000L
        val recorder = HandlebarTest { now }
        var operation = UUID.randomUUID().toString()
        val context = HandlebarContext(Function.MODE_SAB, 3, RidePageStage.MENU)
        init { recorder.create(operation) }
        fun start(sequence: Long = 0, gesture: HandlebarGesture = HandlebarGesture.UP_SHORT) =
            recorder.start(gesture, sequence, context)
        fun input(sequence: Long, function: Function, testId: String = recorder.state.value.id!!,
            operation: String = this.operation) {
            now += 5_000_000
            recorder.accept(testId, operation, Gen1InputMonitor.Observation(
                Gen1InputMonitor.Input(function, sequence, 3), Gen1InputMonitor.Mode(Function.MODE_SAB, 3), now), RidePageStage.MUSIC)
        }
    }

    @Test fun gesturesDoNotPredictFunctionsAndModesDoNotCompleteFunctionCoverage() {
        val f = Fixture()
        assertTrue(f.start())
        f.input(1, Function.MODE_SAB)
        f.input(2, Function.MODE_HU)
        assertEquals(0, HandlebarTest.FUNCTIONS.count { f.recorder.state.value.count(it) > 0 })
        assertEquals(1L, f.recorder.state.value.count(Function.MODE_HU))
        f.input(3, Function.NEXT)
        f.input(4, Function.NEXT)
        f.recorder.end(HandlebarEnd.ENDED)
        assertTrue(f.start(4, HandlebarGesture.LEFT_LONG))
        f.input(5, Function.NEXT) // Same function under a different user-labelled physical gesture.
        assertEquals(3L, f.recorder.state.value.count(Function.NEXT))
        assertEquals(1, HandlebarTest.FUNCTIONS.count { f.recorder.state.value.count(it) > 0 })
        val row = f.recorder.state.value.rounds.first().events.last()
        assertEquals(20L, row.elapsedMs)
        assertEquals(Function.MODE_SAB, row.mode)
        assertEquals(RidePageStage.MUSIC, row.page)
        assertEquals(3L, row.modeRevision)
    }

    @Test fun sequenceAndSessionFencesDiscardPriorBufferedInputsAndOldConnectionObservers() {
        val f = Fixture()
        assertTrue(f.start(20))
        f.input(19, Function.SELECT)
        f.input(20, Function.SELECT)
        f.input(21, Function.BACK)
        f.input(21, Function.BACK)
        f.input(22, Function.MENU, operation = UUID.randomUUID().toString())
        assertEquals(1, f.recorder.state.value.rounds.single().events.size)
        val oldId = f.recorder.state.value.id!!
        val oldOperation = f.operation
        f.recorder.close(HandlebarEnd.CONNECTION_ENDED, 22)
        f.operation = UUID.randomUUID().toString()
        f.recorder.create(f.operation)
        assertTrue(f.start())
        f.input(100, Function.SELECT, testId = oldId, operation = oldOperation)
        f.input(101, Function.SELECT, operation = oldOperation)
        f.input(102, Function.SELECT, testId = oldId)
        f.input(1, Function.PREVIOUS)
        assertEquals(listOf(Function.PREVIOUS), f.recorder.state.value.rounds.single().events.map { it.function })
        assertEquals(0L, f.recorder.state.value.rounds.single().lostEvents)
    }

    @Test fun cancellationLeavingAccountOrConnectionEndAndFinishRetainButStopEvidence() {
        listOf(HandlebarEnd.CANCELLED, HandlebarEnd.SCREEN_LEFT, HandlebarEnd.CONNECTION_ENDED, HandlebarEnd.FINISHED).forEach { reason ->
            val f = Fixture()
            assertTrue(f.start())
            f.input(1, Function.SELECT)
            f.recorder.close(reason, 1)
            f.input(2, Function.NEXT)
            assertFalse(f.start(2))
            assertNull(f.recorder.state.value.activeRound)
            assertEquals(reason, f.recorder.state.value.closed)
            assertEquals(reason, f.recorder.state.value.rounds.single().end)
            assertEquals(1L, f.recorder.state.value.count(Function.SELECT))
            assertEquals(0L, f.recorder.state.value.count(Function.NEXT))
        }
    }

    @Test fun missingAndTruncatedEvidenceIsExplicitWhileRetainedCountsStayAccurate() {
        val f = Fixture()
        assertTrue(f.start())
        repeat(100) { index -> f.input(if (index == 0) 1L else index + 2L, Function.VOLUME_DOWN) }
        f.recorder.end(HandlebarEnd.ENDED, 104)
        val round = f.recorder.state.value.rounds.single()
        assertEquals(HandlebarTest.MAX_ROWS, round.events.size)
        assertEquals(36L, round.omittedRows)
        assertEquals(4L, round.lostEvents) // One interior gap plus three uncollected tail events.
        assertEquals(100L, round.counts[Function.VOLUME_DOWN])
        assertEquals(0L, f.recorder.state.value.count(Function.VOLUME_UP))
    }

    @Test fun stepWindowsDoNotObserveBetweenRoundsAndRetriesAreBounded() {
        val f = Fixture()
        f.input(1, Function.MENU)
        HandlebarGesture.entries.forEach { gesture ->
            repeat(HandlebarTest.MAX_ROUNDS_PER_GESTURE) {
                assertTrue(f.start(1, gesture))
                assertFalse(f.start(1, gesture))
                f.recorder.end(HandlebarEnd.SKIPPED)
                f.input(2, Function.MENU)
            }
            assertFalse(f.start(1, gesture))
        }
        assertEquals(32, f.recorder.state.value.rounds.size)
        assertEquals(0L, f.recorder.state.value.count(Function.MENU))
        assertTrue(f.recorder.state.value.rounds.all { it.end == HandlebarEnd.SKIPPED && it.events.isEmpty() })
    }

    @Test fun exportIncludesStepEvidenceBuildAndLimitsWithClosedSemanticValues() {
        val f = Fixture()
        f.start(10)
        f.input(12, Function.VOLUME_UP)
        f.recorder.close(HandlebarEnd.FINISHED, 12)
        val json = f.recorder.state.value.toJson("abcdef123", 1, "0.1.0-dev")
        listOf("\"source_commit\":\"abcdef123\"", "\"version_code\":1", "\"version_name\":\"0.1.0-dev\"",
            "\"gesture\":\"UP_SHORT\"", "\"wire_id\":5", "\"sequence\":12", "\"elapsed_ms\":5",
            "\"start_page\":\"MENU\"", "\"page\":\"MUSIC\"", "\"mode_revision\":3", "\"lost_events\":1",
            "\"VOLUME_UP\":1", "\"hid_not_observed\":true", "\"rows_per_round\":64").forEach {
            assertTrue("Missing export evidence $it", json.contains(it))
        }
        assertFalse(json.contains("uid"))
        assertFalse(json.contains("token"))
        assertFalse(json.contains("mac"))
        assertEquals((1..10).toList(), (HandlebarTest.FUNCTIONS + HandlebarTest.MODES).map { HandlebarTest.wireId(it) })
    }
}
