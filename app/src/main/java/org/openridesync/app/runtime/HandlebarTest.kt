package org.openridesync.app.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ble.Gen1InputMonitor
import java.util.UUID

internal enum class HandlebarGesture(val label: String) {
    UP_SHORT("上・短按"), UP_LONG("上・長按"), DOWN_SHORT("下・短按"), DOWN_LONG("下・長按"),
    LEFT_SHORT("左・短按"), LEFT_LONG("左・長按"), RIGHT_SHORT("右・短按"), RIGHT_LONG("右・長按")
}
internal enum class HandlebarEnd { ENDED, SKIPPED, CANCELLED, SCREEN_LEFT, CONNECTION_ENDED, FINISHED }
internal data class HandlebarContext(val mode: Function?, val modeRevision: Long, val page: RidePageStage)
internal data class HandlebarEvent(val function: Function, val sequence: Long, val elapsedMs: Long,
    val mode: Function?, val modeRevision: Long, val page: RidePageStage)
internal data class HandlebarRound(val gesture: HandlebarGesture, val startedMs: Long,
    val startContext: HandlebarContext, val baselineSequence: Long, val endedMs: Long? = null,
    val end: HandlebarEnd? = null, val events: List<HandlebarEvent> = emptyList(),
    val counts: Map<Function, Long> = emptyMap(), val lostEvents: Long = 0, val omittedRows: Long = 0)
internal data class HandlebarTestState(val id: String? = null, val operation: String? = null,
    val rounds: List<HandlebarRound> = emptyList(), val activeRound: Int? = null, val closed: HandlebarEnd? = null) {
    fun count(function: Function): Long = rounds.sumOf { it.counts[function] ?: 0L }
}

/** Main-thread only. The connection owner supplies real monitor observations after identity fences.
 * No UI gesture generates a Function. A gesture is solely the user's label for an observation window. */
internal class HandlebarTest(private val nanoTime: () -> Long = System::nanoTime) {
    private val mutableState = MutableStateFlow(HandlebarTestState())
    val state = mutableState.asStateFlow()
    private var origin = 0L
    private var lastSequence = 0L
    private fun elapsed(nanos: Long = nanoTime()) = ((nanos - origin) / 1_000_000).coerceAtLeast(0)

    fun create(operation: String) {
        require(runCatching { UUID.fromString(operation) }.isSuccess)
        origin = nanoTime()
        mutableState.value = HandlebarTestState(UUID.randomUUID().toString(), operation)
    }

    fun start(gesture: HandlebarGesture, sequence: Long, context: HandlebarContext): Boolean {
        val current = state.value
        if (current.id == null || current.closed != null || current.activeRound != null ||
            current.rounds.count { it.gesture == gesture } >= MAX_ROUNDS_PER_GESTURE) return false
        lastSequence = sequence
        mutableState.value = current.copy(activeRound = current.rounds.size,
            rounds = current.rounds + HandlebarRound(gesture, elapsed(), context, sequence))
        return true
    }

    fun accept(testId: String, operation: String, observation: Gen1InputMonitor.Observation, page: RidePageStage) {
        val current = state.value
        if (current.id != testId || current.operation != operation) return
        val index = current.activeRound ?: return
        val input = observation.input
        if (current.closed != null || input.sequence <= lastSequence) return
        val round = current.rounds[index]
        val missing = input.sequence - lastSequence - 1
        lastSequence = input.sequence
        if (input.function !in FUNCTIONS && input.function !in MODES) return
        val row = HandlebarEvent(input.function, input.sequence, elapsed(observation.receivedNanos),
            observation.mode.function, input.modeRevision, page)
        replaceRound(index, round.copy(
            events = if (round.events.size < MAX_ROWS) round.events + row else round.events,
            counts = round.counts + (input.function to ((round.counts[input.function] ?: 0L) + 1)),
            lostEvents = round.lostEvents + missing,
            omittedRows = round.omittedRows + if (round.events.size >= MAX_ROWS) 1 else 0))
    }

    fun end(reason: HandlebarEnd, finalSequence: Long? = null) {
        val index = state.value.activeRound ?: return
        val round = state.value.rounds[index]
        // Cancelled buffered observations are missing evidence too, including a dropped tail.
        replaceRound(index, round.copy(endedMs = elapsed(), end = reason,
            lostEvents = round.lostEvents + ((finalSequence ?: lastSequence) - lastSequence).coerceAtLeast(0)))
        mutableState.value = state.value.copy(activeRound = null)
    }

    fun close(reason: HandlebarEnd, finalSequence: Long? = null) {
        end(reason, finalSequence)
        if (state.value.id != null && state.value.closed == null) mutableState.value = state.value.copy(closed = reason)
    }

    private fun replaceRound(index: Int, round: HandlebarRound) {
        mutableState.value = state.value.copy(rounds = state.value.rounds.mapIndexed { i, old -> if (i == index) round else old })
    }

    companion object {
        const val MAX_ROUNDS_PER_GESTURE = 4
        const val MAX_ROWS = 64
        val FUNCTIONS = listOf(Function.PREVIOUS, Function.NEXT, Function.BACK, Function.SELECT,
            Function.VOLUME_UP, Function.VOLUME_DOWN, Function.VOLUME_MUTE, Function.MENU)
        val MODES = listOf(Function.MODE_SAB, Function.MODE_HU)
        fun wireId(function: Function): Int = when (function) {
            Function.PREVIOUS -> 1; Function.NEXT -> 2; Function.BACK -> 3; Function.SELECT -> 4
            Function.VOLUME_UP -> 5; Function.VOLUME_DOWN -> 6; Function.VOLUME_MUTE -> 7
            Function.MENU -> 8; Function.MODE_SAB -> 9; Function.MODE_HU -> 10
            Function.UNKNOWN -> 0
        }
        fun label(function: Function) = "${function.name} 0x${wireId(function).toString(16).padStart(2, '0')}"
    }
}

/** Closed enums, generated UUIDs and numbers only: no account/device/media/user text is accepted. */
internal fun HandlebarTestState.toJson(sourceCommit: String = "unknown", versionCode: Int = 0,
    versionName: String = "unknown"): String = buildString {
    require(sourceCommit.matches(Regex("[0-9a-f]{7,40}|unknown")))
    require(versionCode >= 0 && versionName.matches(Regex("[A-Za-z0-9_.+\\-]{1,80}")))
    fun function(value: Function?) = value?.let { "\"${it.name}\"" } ?: "null"
    append("{\"schema\":\"openridesync-handlebar-v1\",\"source\":\"live_gen1_function_monitor\",")
    append("\"build\":{\"source_commit\":\"$sourceCommit\",\"version_code\":$versionCode,\"version_name\":\"$versionName\"},")
    append("\"test_id\":\"$id\",\"connection_operation\":\"$operation\",")
    append("\"closed\":${closed?.let { "\"${it.name}\"" } ?: "null"},")
    append("\"limits\":{\"rounds_per_gesture\":${HandlebarTest.MAX_ROUNDS_PER_GESTURE},\"rows_per_round\":${HandlebarTest.MAX_ROWS}},")
    append("\"context\":\"mode_at_monitor_acceptance_page_at_observer_delivery\",")
    append("\"coverage_means\":\"observed_function_only_not_vehicle_or_action_success\",")
    append("\"hid_not_observed\":true,\"recovery_mode_hu_may_be_suppressed\":true,\"rounds\":[")
    rounds.forEachIndexed { index, round ->
        if (index > 0) append(',')
        append("{\"gesture\":\"${round.gesture.name}\",\"started_ms\":${round.startedMs},\"baseline_sequence\":${round.baselineSequence},")
        append("\"start_mode\":${function(round.startContext.mode)},\"start_mode_revision\":${round.startContext.modeRevision},")
        append("\"start_page\":\"${round.startContext.page.name}\",\"ended_ms\":${round.endedMs},")
        append("\"end\":${round.end?.let { "\"${it.name}\"" } ?: "null"},\"lost_events\":${round.lostEvents},\"omitted_rows\":${round.omittedRows},\"counts\":{")
        round.counts.entries.forEachIndexed { i, entry ->
            if (i > 0) append(',')
            append("\"${entry.key.name}\":${entry.value}")
        }
        append("},\"events\":[")
        round.events.forEachIndexed { i, event ->
            if (i > 0) append(',')
            append("{\"function\":${function(event.function)},\"wire_id\":${HandlebarTest.wireId(event.function)},")
            append("\"sequence\":${event.sequence},\"elapsed_ms\":${event.elapsedMs},\"mode\":${function(event.mode)},")
            append("\"mode_revision\":${event.modeRevision},\"page\":\"${event.page.name}\"}")
        }
        append("]}")
    }
    append("]}")
}
