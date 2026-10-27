package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openridesync.adapter.MusicDisplayText
import org.openridesync.app.ble.Gen1ControlExchange
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ble.Gen1InputMonitor
import org.openridesync.app.ble.Gen1Projection
import org.openridesync.app.calls.*
import org.openridesync.app.media.MediaAccess
import org.openridesync.app.media.MediaState
import org.openridesync.app.media.MusicProjection

/** One opcode-2 owner. HU calls are passive; only SAB forwards physical input. */
internal class RidePopups(
    private val monitor: Gen1InputMonitor,
    private val controls: Gen1ControlExchange,
    private val media: StateFlow<MediaState?>,
    private val calls: StateFlow<CallContext>,
    private val ready: StateFlow<Boolean>,
    private val capabilities: Int?,
    private val isCurrent: () -> Boolean,
    private val callInbox: StateFlow<CallInboxState>,
    private val incomingLabel: String,
    private val pages: StateFlow<RidePageState>? = null,
) : AutoCloseable {
    private data class Track(val id: String, val title: String, val artist: String)
    private data class Window(val track: Track, val modeRevision: Long, val deadline: Long)
    private sealed interface Entry {
        data class Music(val window: Window) : Entry
        data class Call(val item: CallItem, val modeRevision: Long) : Entry
    }
    private var shown: Track? = null
    private var window: Window? = null
    private var submitted: Entry? = null
    private var mode = monitor.mode.value
    private var observedCall: CallItem? = null
    private var transitionFence = 0L
    private var pending: Deferred<*>? = null
    private var expiry: Job? = null
    private var worker: Job? = null
    private var closed = false

    private fun track(): Track? = media.value?.takeIf { it.access == MediaAccess.GRANTED &&
        it.selectedId != null && it.sessions.count { choice -> choice.id == it.selectedId } == 1 }
        ?.let { Track(checkNotNull(it.selectedId), MusicDisplayText.prepare(it.title), MusicDisplayText.prepare(it.artist)) }

    private fun enabled() = !closed && isCurrent() && ready.value && capabilities != null && capabilities and 2 != 0
    private fun incoming(): CallItem? = callInbox.value.takeIf { it.access == CallAccess.READY }
        ?.calls?.singleOrNull()?.takeIf {
            enabled() && monitor.mode.value.function == Function.MODE_HU && it.kind == CallKind.INCOMING
        }

    private fun musicWindow(): Window? = window?.takeIf {
        enabled() && monitor.mode.value.function == Function.MODE_SAB &&
            it.modeRevision == monitor.mode.value.revision && it.track == track() &&
            System.nanoTime() < it.deadline && !calls.value.musicPopupBlocked && !messageOwnsDisplay()
    }

    private fun messageOwnsDisplay() = pages?.value?.let {
        it.stage in setOf(RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ) ||
            it.stage == RidePageStage.MESSAGE && it.messageReadStatus == MessageReadStatus.READING
    } == true

    private fun desired(): Entry? = incoming()?.let { Entry.Call(it, monitor.mode.value.revision) }
        ?: musicWindow()?.let(Entry::Music)

    private fun publish() {
        val value = desired()
        if (value == submitted) return
        pending?.cancel()
        submitted = value
        if (!enabled()) return
        pending = controls.submit(allowed = { enabled() && submitted == value && desired() == value }) { id ->
            when (value) {
                null -> byteArrayOf(2, id.toByte(), 0, 0, 0)
                is Entry.Music -> Gen1Projection.encode(checkNotNull(MusicProjection.popup(
                    value.window.track.title, value.window.track.artist)), id)
                is Entry.Call -> Gen1Projection.encode(checkNotNull(CallProjection.popup(
                    value.item.copy(actions = emptySet()), incomingLabel)), id)
            }
        }
    }

    /** Synchronous invalidation before closing/replacing a MediaController, even with the same ID. */
    fun clearSelection() {
        expiry?.cancel(); expiry = null
        window = null
        publish()
    }

    /** The ordinary page owner calls this first for every accepted function input. */
    fun blocksInput(): Boolean = incoming() != null || submitted is Entry.Call

    fun handleInput(input: Gen1InputMonitor.Input): Boolean {
        if (monitor.mode.value.function != Function.MODE_SAB || input.modeRevision != monitor.mode.value.revision ||
            input.function in setOf(Function.MODE_HU, Function.MODE_SAB)) return false
        // A stale HU overlay must clear before a newly accepted SAB key can reach a page.
        return submitted is Entry.Call || input.sequence <= transitionFence
    }

    suspend fun run(): Nothing = coroutineScope {
        check(worker == null && !closed)
        worker = currentCoroutineContext()[Job]
        try {
            combine(combine(media, calls, monitor.mode, ready, callInbox) { _, _, _, _, _ -> Unit },
                pages ?: MutableStateFlow(RidePageState())) { _, _ -> Unit }.collect {
                val currentMode = monitor.mode.value
                val currentTrack = track()
                val currentCall = incoming()
                if (currentCall != observedCall || currentMode.revision != mode.revision) {
                    if (observedCall != null && currentCall == null)
                        transitionFence = monitor.sequence
                    observedCall = currentCall
                }
                if (!enabled() || currentMode.function != Function.MODE_SAB || currentTrack == null ||
                    (currentMode.revision != mode.revision &&
                        (mode.function != Function.MODE_SAB || currentMode.revision != mode.revision + 1))) {
                    expiry?.cancel(); expiry = null; window = null
                } else if (currentMode.revision != mode.revision) {
                    // Repeated SAB updates the write fence, never the three-second deadline.
                    window = window?.copy(modeRevision = currentMode.revision)
                }
                mode = currentMode
                if (enabled() && currentMode.function == Function.MODE_SAB && currentTrack != null && currentTrack != shown) {
                    shown = currentTrack // Compare full normalized fields, before the90-byte display cap.
                    expiry?.cancel(); expiry = null
                    window = if (currentTrack.title.isEmpty() && currentTrack.artist.isEmpty()) null
                        else Window(currentTrack, currentMode.revision, System.nanoTime() + 3_000_000_000L)
                    if (window != null) expiry = launch {
                        delay(3_000) // Starts with metadata, not with receipt or release of a call interruption.
                        window = null
                        publish()
                    }
                }
                publish()
            }
            awaitCancellation()
        } finally { close() }
    }

    override fun close() {
        closed = true
        expiry?.cancel(); pending?.cancel(); worker?.cancel()
        expiry = null; pending = null; worker = null
        window = null; submitted = null; shown = null; observedCall = null
    }
}
