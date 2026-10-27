package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.*
import org.openridesync.app.calls.*
import org.openridesync.app.media.*
import org.openridesync.diagnostics.DiagnosticJournal
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

/** Synthetic receipts exercise the actual shared queue. No physical meter/display acceptance. */
class RidePopupsTest {
    private class Fixture(scope: CoroutineScope, journal: DiagnosticJournal, capabilities: Int?,
                          immediateMonitor: Boolean) {
        val source = Channel<ByteArray>(Channel.UNLIMITED)
        val receipts = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = Channel<ByteArray>(Channel.UNLIMITED)
        val monitor = Gen1InputMonitor(journal, journal.newOperation())
        val media = MutableStateFlow<MediaState?>(selected())
        val calls = MutableStateFlow(CallContext(notificationUnknown = false))
        val ready = MutableStateFlow(false)
        val callInbox = MutableStateFlow(CallInboxState())
        val pages = MutableStateFlow(RidePageState())
        var current = true
        var hold: CompletableDeferred<Unit>? = null
        val controls = Gen1ControlExchange(receipts, { frame ->
            writes.send(frame.copyOf())
            val waiting = hold; waiting?.await()
            if (hold === waiting) hold = null
            receipts.send(byteArrayOf(frame[0], frame[1], 1))
        }, journal, journal.newOperation())
        val popup = RidePopups(monitor, controls, media, calls, ready, capabilities, { current },
            callInbox, "Incoming call", pages)
        val monitorJob = scope.launch(if (immediateMonitor) Dispatchers.Unconfined else EmptyCoroutineContext,
            start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
        // Resume the queue before flow collectors to reproduce stale-publication races.
        val controlJob = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { controls.run() }
        val popupJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { popup.run() }
        suspend fun mode(id: Int) {
            val revision = monitor.mode.value.revision
            source.send(byteArrayOf(id.toByte()))
            while (monitor.mode.value.revision == revision) yield()
            settle()
        }
        suspend fun input(id: Int): Boolean {
            val before = monitor.sequence
            source.send(byteArrayOf(id.toByte()))
            while (monitor.sequence == before) yield()
            settle()
            val function = Gen1Input.decodeFunction(byteArrayOf(id.toByte())).function
            return popup.handleInput(Gen1InputMonitor.Input(function, monitor.sequence,
                monitor.mode.value.revision))
        }
        suspend fun close() {
            popup.close(); popupJob.join(); controls.close(); controlJob.join()
            monitorJob.cancelAndJoin(); source.cancel(); receipts.cancel(); writes.cancel()
        }
    }
    private suspend fun fixture(capabilities: Int? = 2, immediateMonitor: Boolean = false,
                                block: suspend Fixture.() -> Unit) = withTimeout(9_000) {
        val directory = Files.createTempDirectory("music-popup-").toFile()
        try {
            DiagnosticJournal(directory).use { journal -> coroutineScope {
                val f = Fixture(this, journal, capabilities, immediateMonitor)
                try { f.block() } finally { withContext(NonCancellable) { withTimeout(1_000) { f.close() } } }
            } }
        } finally { directory.deleteRecursively() }
    }

    @Test fun startupModeCapabilityAndMetadataAreRealGatesWithoutAutoplayOrBlankFallback() = runBlocking {
        fixture(null) {
            ready.value = true; mode(9); settle()
            assertTrue(writes.tryReceive().isFailure)
        }
        fixture {
            mode(9); assertTrue(writes.tryReceive().isFailure)
            ready.value = true
            val frame = writes.receive()
            assertArrayEquals(Gen1Projection.encode(MusicProjection.popup("Track", "Artist")!!, frame[1].toInt() and 255), frame)
            mode(10); assertHide(writes.receive())
            mode(9); assertTrue(writes.tryReceive().isFailure) // Unchanged track is not replayed on SAB return.
            media.value = selected().copy(title = "\u202e", artist = "\n")
            settle(); assertTrue(writes.tryReceive().isFailure)
            media.value = selected().copy(title = null, artist = "Solo artist")
            val artistOnly = writes.receive()
            assertArrayEquals(Gen1Projection.encode(MusicProjection.popup(null, "Solo artist")!!,
                artistOnly[1].toInt() and 255), artistOnly)
            media.value = media.value!!.copy(access = MediaAccess.REQUIRED)
            assertHide(writes.receive())
        }
    }

    @Test fun callSuppressionPreservesRemainingWindowAndNeverReplaysExpiredMetadata() = runBlocking {
        fixture {
            ready.value = true; mode(9); writes.receive()
            calls.value = calls.value.copy(notificationIncoming = true)
            assertHide(writes.receive())
            delay(200)
            calls.value = calls.value.copy(notificationIncoming = false)
            assertTrue(writes.receive().size > 5)
            calls.value = calls.value.copy(cellularIdle = false)
            assertHide(writes.receive())
            delay(3_100)
            calls.value = calls.value.copy(cellularIdle = true)
            media.value = selected().copy(playback = MediaPlayback.PLAYING)
            settle(); assertTrue(writes.tryReceive().isFailure)
            media.value = selected().copy(title = "New track")
            assertTrue(writes.receive().size > 5)
            popup.close(); popupJob.join()
            media.value = selected().copy(title = "After close"); settle()
            assertTrue(writes.tryReceive().isFailure)
        }
    }

    @Test fun metadataPollingAndRepeatedModeDoNotExtendThreeSecondDeadline() = runBlocking {
        fixture {
            ready.value = true; mode(9); writes.receive()
            val started = System.nanoTime()
            delay(1_500)
            media.value = selected().copy(playback = MediaPlayback.PLAYING)
            mode(9)
            // Repeated mode refreshes a write fence, never the metadata window.
            var frame = writes.receive()
            if (frame.size > 5) frame = writes.receive()
            assertHide(frame)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("Early expiry: $elapsed", elapsed >= 2_850)
            assertTrue("Repeated mode extended expiry: $elapsed", elapsed < 4_000)
            media.value = selected(); settle(); assertTrue(writes.tryReceive().isFailure)
        }
    }

    @Test fun queuedOldTrackIsRejectedBeforeCollectorAndExpiryPreventsLatePublication() = runBlocking {
        fixture {
            val release = CompletableDeferred<Unit>(); hold = release
            val blocker = controls.submit { byteArrayOf(10, it.toByte()) }
            writes.receive()
            ready.value = true; mode(9); settle() // Popup waits behind the held write.
            media.value = selected().copy(title = "Replacement")
            release.complete(Unit); blocker.await()
            assertTrue(writes.tryReceive().isFailure) // Worker checked latest source before its collector ran.
            val replacement = writes.receive()
            assertArrayEquals(Gen1Projection.encode(MusicProjection.popup("Replacement", "Artist")!!,
                replacement[1].toInt() and 255), replacement)
            val release2 = CompletableDeferred<Unit>(); hold = release2
            val blocker2 = controls.submit { byteArrayOf(10, it.toByte()) }; writes.receive()
            media.value = selected().copy(title = "Expires in queue"); settle()
            delay(3_100)
            // The real exchange may time out/retry the held blocker; no popup may pass it.
            while (true) {
                val retry = writes.tryReceive().getOrNull() ?: break
                assertEquals(10, retry[0].toInt())
            }
            release2.complete(Unit); blocker2.await()
            // Receipt timeout does not cancel an in-flight ATT write. Its retry can begin
            // only after release; the expired music must still never reach the transport.
            var afterBlocker = writes.receive()
            while (afterBlocker[0].toInt() == 10) afterBlocker = writes.receive()
            assertHide(afterBlocker)
            assertTrue(writes.tryReceive().isFailure)
        }
    }

    @Test fun huIncomingIsPassiveAndNoneOfTheEightKeysAreHandled() = runBlocking {
        fixture {
            ready.value = true; mode(10)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("caller")))
            val frame = writes.receive()
            assertArrayEquals(Gen1Projection.encode(CallProjection.popup(
                incoming("caller").copy(actions = emptySet()), "Incoming call")!!,
                frame[1].toInt() and 255), frame)
            for (id in 1..8) assertFalse(input(id))
            assertTrue(writes.tryReceive().isFailure)
            callInbox.value = CallInboxState(CallAccess.READY,
                listOf(incoming("caller").copy(kind = CallKind.ONGOING)))
            assertHide(writes.receive())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun queuedMusicWriteCannotHideHuCallAfterModeSwitch() = runTest {
        fixture(immediateMonitor = true) {
            ready.value = true; mode(9)
            val music = writes.receive()
            assertTrue(music.size > 5)
            val release = CompletableDeferred<Unit>(); hold = release
            val blocker = controls.submit { byteArrayOf(10, it.toByte()) }; writes.receive()
            media.value = selected().copy(title = "Queued music")
            advanceTimeBy(10); runCurrent()
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("new")))
            val before = monitor.sequence
            source.send(byteArrayOf(4)) // Queued old Select, before the popup collector sees new CALL.
            assertEquals(before + 1, monitor.sequence)
            val oldInput = Gen1InputMonitor.Input(Gen1Input.Function.SELECT, monitor.sequence,
                monitor.mode.value.revision)
            mode(10)
            assertFalse(popup.handleInput(oldInput))
            release.complete(Unit); blocker.await()
            val frame = writes.receive()
            assertArrayEquals(Gen1Projection.encode(CallProjection.popup(incoming("new").copy(actions = emptySet()),
                "Incoming call")!!, frame[1].toInt() and 255), frame)
            assertTrue(writes.tryReceive().isFailure) // No queued music or hide follows CALL.
            assertFalse(input(4)); settle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun oldQueuedSelectCannotAnswerNewCallerInSameHuMode() = runTest {
        fixture(immediateMonitor = true) {
            ready.value = true; mode(10)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("old")))
            writes.receive()
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("new")))
            val before = monitor.sequence
            source.send(byteArrayOf(4))
            assertEquals(before + 1, monitor.sequence)
            val oldInput = Gen1InputMonitor.Input(Gen1Input.Function.SELECT, monitor.sequence,
                monitor.mode.value.revision)
            assertFalse(popup.handleInput(oldInput)) // A replaced source does not enable HU input.
            runCurrent()
            assertFalse(popup.handleInput(oldInput)) // HU keys stay inactive after the collector resumes.
            val frame = writes.receive()
            assertArrayEquals(Gen1Projection.encode(CallProjection.popup(incoming("new").copy(actions = emptySet()),
                "Incoming call")!!, frame[1].toInt() and 255), frame)
            assertFalse(input(4)); settle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun queuedHuKeyStaysInactiveThroughSourceLoss() = runTest {
        fixture(immediateMonitor = true) {
            ready.value = true; mode(10)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("old")))
            writes.receive()
            callInbox.value = CallInboxState(CallAccess.ACCESS_REQUIRED)
            val before = monitor.sequence
            source.send(byteArrayOf(4))
            assertEquals(before + 1, monitor.sequence)
            val oldInput = Gen1InputMonitor.Input(Gen1Input.Function.SELECT, monitor.sequence,
                monitor.mode.value.revision)
            assertFalse(popup.handleInput(oldInput)) // Already rejected before the collector can clear the source.
            runCurrent()
            assertFalse(popup.handleInput(oldInput)) // HU remains inactive after the hide.
            assertHide(writes.receive())
            val next = monitor.sequence
            source.send(byteArrayOf(4))
            assertEquals(next + 1, monitor.sequence)
            assertFalse(popup.handleInput(Gen1InputMonitor.Input(Gen1Input.Function.SELECT,
                monitor.sequence, monitor.mode.value.revision)))
        }
    }

    @Test fun huPopupGatesAndUniqueSourceLossClearOnlyCurrentEntry() = runBlocking {
        fixture(capabilities = 4) {
            ready.value = true; mode(10)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("one")))
            settle()
            assertTrue(writes.tryReceive().isFailure)
            assertFalse(input(4))
        }
        fixture {
            ready.value = true; mode(10)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("one")))
            writes.receive()
            callInbox.value = CallInboxState(CallAccess.READY,
                listOf(incoming("one"), incoming("two")))
            assertHide(writes.receive())
            assertFalse(input(4))
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("two")))
            writes.receive()
            media.value = null
            mode(9); assertHide(writes.receive())
            assertFalse(input(4))
            popup.close(); popupJob.join()
            callInbox.value = CallInboxState(CallAccess.READY, listOf(incoming("later")))
            settle(); assertTrue(writes.tryReceive().isFailure)
        }
    }

    @Test fun messageLivePageSuppressesMusicPopupWithoutExtendingItsDeadline() = runBlocking {
        fixture {
            mode(9); ready.value = true; settle(); writes.receive()
            pages.value = RidePageState(RidePageStage.MESSAGE_PROMPT)
            assertHide(writes.receive())
            media.value = selected().copy(title = "Next under message")
            settle(); assertTrue(writes.tryReceive().isFailure)
            pages.value = RidePageState(RidePageStage.MESSAGE_READ, messageReadStatus = MessageReadStatus.READING)
            settle(); assertTrue(writes.tryReceive().isFailure)
            delay(3_100)
            pages.value = RidePageState(RidePageStage.MENU)
            settle(); assertTrue(writes.tryReceive().isFailure)
        }
    }

    companion object {
        private fun selected() = MediaState(access = MediaAccess.GRANTED,
            sessions = listOf(MediaSessionChoice("selected", "Player", "synthetic.player")), selectedId = "selected",
            title = "Track", artist = "Artist", playback = MediaPlayback.PAUSED)
        private fun incoming(id: String) = CallItem(id, "Dialer", "synthetic.dialer", "Caller $id",
            CallKind.INCOMING, setOf(CallAction.ANSWER, CallAction.DECLINE), false)
        private suspend fun settle() { repeat(30) { yield() } }
        private fun assertHide(frame: ByteArray) { assertArrayEquals(byteArrayOf(2, frame[1], 0, 0, 0), frame) }
    }
}
