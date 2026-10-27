package org.openridesync.app.runtime

import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.*
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.media.*
import org.openridesync.app.messages.*
import org.openridesync.diagnostics.DiagnosticJournal

/** Two real page/monitor/exchange owners; observations and receipts are synthetic, no vehicle. */
@OptIn(ExperimentalCoroutinesApi::class)
class RidePagesRecoveryTest {
    private class Transport(private val scope: TestScope, journal: DiagnosticJournal,
        incoming: IncomingMessages, startupState: RideStartupState, initialMode: Int? = 9,
        startup: suspend () -> Unit = {},
        val messages: MutableStateFlow<MessageInboxState> = MutableStateFlow(MessageInboxState(MessageAccess.READY)),
        messageMode: IncomingMessageMode = IncomingMessageMode.IGNORE,
        prompt: suspend (String) -> Unit = {}, read: suspend (String) -> Unit = {}) {
        var current = true
        val source = Channel<ByteArray>(Channel.UNLIMITED).also { channel ->
            initialMode?.let { channel.trySend(byteArrayOf(it.toByte())) }
        }
        val receipts = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        val states = mutableListOf<RidePageState>()
        val musicActions = mutableListOf<Pair<Function, String>>()
        val volumeActions = mutableListOf<Function>()
        val intercepted = mutableListOf<Function>()
        val prompts = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val media = MutableStateFlow<MediaState?>(selected("player"))
        val monitor = Gen1InputMonitor(journal, journal.newOperation())
        val controls = Gen1ControlExchange(receipts, { frame ->
            writes.add(frame.copyOf())
            receipts.send(byteArrayOf(frame[0], frame[1], 1))
        }, journal, journal.newOperation())
        val page = RidePages(monitor, controls, media, MutableStateFlow(CallContext(notificationUnknown = false)),
            MutableStateFlow(RideVolume.State(RideVolume.Stream.MEDIA, 3, 5, 0, 10, 50, 3, false, 1)), 4,
            RidePageLabels("Unknown", "Music", "Playing", "Choose player", "Volume"), Locale.US, false,
            { current }, startup, { function, id -> musicActions.add(function to id) },
            { volumeActions.add(it) }, MutableStateFlow(false), messages = messages,
            readMessage = { reads.add(it); read(it) },
            interceptInput = { intercepted.add(it.function); false },
            messageMode = MutableStateFlow(messageMode),
            promptMessage = { id, _ -> prompts.add(id); prompt(id) },
            startupState = startupState, incoming = incoming)
        private val monitorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
        private val controlJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { controls.run() }
        private val stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { page.state.collect { states.add(it) } }
        val pageJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { page.run() }

        fun settle() {
            repeat(5) { scope.runCurrent(); scope.advanceTimeBy(10) }
            scope.runCurrent()
        }
        suspend fun input(id: Int) { source.send(byteArrayOf(id.toByte())); settle() }
        suspend fun close() {
            page.close(); pageJob.join()
            controls.close(); controlJob.join()
            monitorJob.cancelAndJoin(); stateJob.cancelAndJoin()
            source.cancel(); receipts.cancel()
        }
    }

    private suspend fun TestScope.withTransports(block: suspend (DiagnosticJournal, MutableList<Transport>) -> Unit) {
        val directory = Files.createTempDirectory("ride-page-recovery-").toFile()
        try {
            DiagnosticJournal(directory).use { journal ->
                val transports = mutableListOf<Transport>()
                try { block(journal, transports) }
                finally { withContext(NonCancellable) { transports.forEach { it.close() } } }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun readyRecoverySkipsStartupAndSplashButWaitsForRealSabBeforeAllBusinessKeys() = runTest {
        withTransports { journal, owners ->
            var startups = 0
            val incoming = IncomingMessages(emptySet()) { true }
            val first = Transport(this, journal, incoming, RideStartupState.NEW, startup = { startups++ }).also(owners::add)
            first.settle()
            assertEquals(RidePageStage.MENU, first.page.state.value.stage)
            assertTrue(first.page.open()); first.settle()
            first.input(4)
            assertEquals(listOf(Function.SELECT to "player"), first.musicActions)
            first.current = false
            first.close()
            val oldWrites = first.writes.size

            val restored = Transport(this, journal, incoming, RideStartupState.READY,
                initialMode = null, startup = { startups++ }).also(owners::add)
            restored.settle()
            assertEquals(1, startups)
            assertEquals(RidePageStage.MENU, restored.page.state.value.stage)
            assertFalse(restored.states.any { it.stage == RidePageStage.SPLASH })
            for (key in 1..8) restored.input(key)
            assertTrue(restored.musicActions.isEmpty()); assertTrue(restored.volumeActions.isEmpty())
            assertTrue(restored.intercepted.isEmpty())
            restored.input(10)
            for (key in 1..8) restored.input(key)
            assertEquals(Function.MODE_HU, restored.monitor.mode.value.function)
            assertTrue(restored.musicActions.isEmpty()); assertTrue(restored.volumeActions.isEmpty())
            assertTrue(restored.intercepted.isEmpty())
            restored.input(9); restored.input(4); restored.input(4)
            assertEquals(listOf(Function.SELECT to "player"), restored.musicActions)
            assertTrue(restored.writes.isNotEmpty())
            first.page.close()
            first.media.value = selected("old-replacement")
            restored.settle()
            assertFalse(first.page.open())
            assertEquals(RidePageStage.DISABLED, first.page.state.value.stage)
            assertEquals(oldWrites, first.writes.size)
            assertEquals(RidePageStage.MUSIC, restored.page.state.value.stage)
        }
    }

    @Test fun runningAndFailedSnapshotsNeverReplayStartupOrAcceptItsLateCompletion() = runTest {
        withTransports { journal, owners ->
            var startups = 0
            val release = CompletableDeferred<Unit>()
            val incoming = IncomingMessages(emptySet()) { true }
            val first = Transport(this, journal, incoming, RideStartupState.NEW,
                startup = { startups++; release.await() }).also(owners::add)
            first.settle()
            assertEquals(RidePageStage.SPLASH, first.page.state.value.stage)
            first.close()
            for (snapshot in listOf(RideStartupState.RUNNING, RideStartupState.FAILED)) {
                val restored = Transport(this, journal, incoming, snapshot, startup = { startups++ }).also(owners::add)
                restored.settle()
                release.complete(Unit)
                restored.input(4)
                assertEquals(1, startups)
                assertEquals(RidePageStage.STARTUP_FAILED, restored.page.state.value.stage)
                assertFalse(restored.states.any { it.stage in setOf(RidePageStage.SPLASH, RidePageStage.MENU) })
                assertFalse(restored.page.open()); assertTrue(restored.musicActions.isEmpty())
                assertEquals(RidePageStage.DISABLED, first.page.state.value.stage)
                restored.close()
            }
        }
    }

    @Test fun sharedInterruptedSpeechStaysFailedAndRepeatedOldCloseCannotSuspendNewSpeech() = runTest {
        withTransports { journal, owners ->
            for (phase in IncomingMessages.Phase.entries) {
                val saved = mutableListOf<Set<String>>()
                val incoming = IncomingMessages(emptySet()) { saved.add(it); true }
                val oldSpeech = CompletableDeferred<Unit>()
                val first = Transport(this, journal, incoming, RideStartupState.NEW,
                    messageMode = IncomingMessageMode.AUTO_READ,
                    prompt = { if (phase == IncomingMessages.Phase.PROMPT) oldSpeech.await() },
                    read = { oldSpeech.await() }).also(owners::add)
                first.settle() // The empty first snapshot establishes the baseline.
                first.messages.value = inbox(message("old-source", "pending"))
                first.settle()
                assertEquals(MessageReadStatus.READING, first.page.state.value.messageReadStatus)
                assertEquals(phase, incoming.front!!.phase)
                assertEquals(listOf("old-source"), first.prompts)
                assertEquals(if (phase == IncomingMessages.Phase.READ) listOf("old-source") else emptyList<String>(), first.reads)
                first.current = false
                first.close()
                assertNull(incoming.front)

                val newSpeech = CompletableDeferred<Unit>()
                val restored = Transport(this, journal, incoming, RideStartupState.READY, initialMode = null,
                    messages = MutableStateFlow(inbox(message("fresh-source", "pending"))),
                    messageMode = IncomingMessageMode.AUTO_READ, read = { newSpeech.await() }).also(owners::add)
                restored.settle(); restored.input(10)
                assertTrue(restored.prompts.isEmpty()); assertTrue(restored.reads.isEmpty())
                restored.input(9)
                assertEquals(MessageReadStatus.FAILED, restored.page.state.value.messageReadStatus)
                assertEquals("fresh-source", incoming.front!!.item.id)
                assertEquals(phase, incoming.front!!.phase)
                assertTrue(restored.prompts.isEmpty()); assertTrue(restored.reads.isEmpty())
                assertEquals(setOf("pending"), saved.last())

                restored.input(8) // Explicitly dismiss recovered pending messages before a new arrival.
                restored.messages.value = inbox(message("fresh-source", "pending"), message("new-arrival", "new"))
                restored.settle()
                assertEquals(listOf("new-arrival"), restored.prompts)
                assertEquals(listOf("new-arrival"), restored.reads)
                assertEquals(MessageReadStatus.READING, incoming.front!!.status)
                first.page.close() // A late old owner must not suspend the successor's shared queue.
                oldSpeech.complete(Unit)
                first.messages.value = inbox(message("late-old-source", "pending"))
                restored.settle()
                assertEquals("new-arrival", incoming.front!!.item.id)
                assertEquals(MessageReadStatus.READING, incoming.front!!.status)
                assertEquals(MessageReadStatus.READING, restored.page.state.value.messageReadStatus)
                newSpeech.complete(Unit); restored.settle()
                assertEquals(MessageReadStatus.COMPLETE, restored.page.state.value.messageReadStatus)
                restored.close()
            }
        }
    }

    private companion object {
        fun selected(id: String) = MediaState(MediaAccess.GRANTED,
            listOf(MediaSessionChoice(id, "Synthetic player", "synthetic.player")), id, playback = MediaPlayback.PAUSED)
        fun message(id: String, recoveryId: String) = MessageItem(id, "Chat", "synthetic.chat", "Label $id",
            "Synthetic body", 999, false, recoveryId = recoveryId)
        fun inbox(vararg values: MessageItem) = MessageInboxState(MessageAccess.READY, values.toList())
    }
}
