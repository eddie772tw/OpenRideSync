package org.openridesync.app.runtime

import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.*
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.media.MediaState
import org.openridesync.app.messages.*
import org.openridesync.app.calls.*
import org.openridesync.diagnostics.DiagnosticJournal

/** Synthetic source/recognizer/transport checks. No phone speech, RemoteInput or vehicle acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideMessageReplyTest {
    private class Fixture(val scope: TestScope, journal: DiagnosticJournal, incoming: Boolean, immediate: Boolean = false) {
        val source = Channel<ByteArray>(Channel.UNLIMITED)
        val receipts = Channel<ByteArray>(Channel.UNLIMITED)
        val monitor = Gen1InputMonitor(journal, journal.newOperation())
        val inbox = MutableStateFlow(MessageInboxState(MessageAccess.READY))
        val callInbox = MutableStateFlow(CallInboxState())
        val calls = MutableStateFlow(CallContext(notificationUnknown = false))
        val media = MutableStateFlow<MediaState?>(null)
        val order = MutableStateFlow(MainMenuSelection.Item.entries.toList())
        val autoReturn = MutableStateFlow(false)
        val quickReplies = MutableStateFlow(List(5) { "Fixture quick reply ${it + 1}" })
        var authoritativeQuickReplies: List<String>? = null
        val mode = MutableStateFlow(if (incoming) IncomingMessageMode.ASK_TO_LISTEN else IncomingMessageMode.IGNORE)
        var current = true
        var availability = RideReplyAvailability.READY
        var saved = emptySet<String>()
        val read = mutableListOf<String>()
        var readAction: suspend () -> Unit = {}
        var recognition: suspend () -> String = { "Recognized reply" }
        var confirmation: suspend () -> Unit = {}
        var delivery: suspend () -> MessageReplyResult = { MessageReplyResult.DISPATCHED }
        var report: suspend () -> Unit = {}
        val reported = mutableListOf<Pair<String, MessageReplyResult>>()
        val recognized = mutableListOf<String>()
        val confirmed = mutableListOf<Pair<String, String>>()
        val sent = mutableListOf<Pair<String, String>>()
        val controls = Gen1ControlExchange(receipts, { frame ->
            receipts.send(byteArrayOf(frame[0], frame[1], 1))
        }, journal, journal.newOperation())
        val pages = RidePages(monitor, controls, media, calls, MutableStateFlow(null), 4,
            RidePageLabels("Unknown", "Music", "Playing", "Choose", "Volume"), Locale.US, false,
            { current }, {}, { _, _ -> }, {}, autoReturn, messages = inbox,
            readMessage = { read.add(it); readAction() }, callInbox = callInbox,
            functionOrder = order, messageMode = mode, savePendingMessages = { saved = it; true },
            replyAvailability = { availability }, recognizeReply = { recognized.add(it); recognition() },
            confirmReply = { id, text -> confirmed.add(id to text); confirmation() },
            dispatchReply = { id, text -> sent.add(id to text); delivery() },
            reportReplyResult = { id, result -> reported.add(id to result); report() }, quickReplies = quickReplies,
            quickRepliesCurrent = { it == (authoritativeQuickReplies ?: quickReplies.value) })
        val monitorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
        val controlsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { controls.run() }
        val pagesJob = scope.launch(if (immediate) UnconfinedTestDispatcher(scope.testScheduler)
            else StandardTestDispatcher(scope.testScheduler), start = CoroutineStart.UNDISPATCHED) { pages.run() }
        fun settle() { repeat(4) { scope.runCurrent(); scope.advanceTimeBy(20) }; scope.runCurrent() }
        suspend fun key(value: Int) { source.send(byteArrayOf(value.toByte())); settle() }
        fun action(value: Function) { assertTrue(pages.performAction(value)); settle() }
        fun state(stage: RidePageStage) { assertEquals(stage, pages.state.value.stage) }
        suspend fun manual() {
            key(9)
            inbox.value = MessageInboxState(MessageAccess.READY, listOf(item("one"))); settle()
            assertTrue(pages.openFunction(MainMenuSelection.Item.MESSAGE)); settle(); state(RidePageStage.MESSAGE)
            action(Function.SELECT)
            assertEquals(MessageReadStatus.COMPLETE, pages.state.value.messageReadStatus)
            action(Function.SELECT); state(RidePageStage.MESSAGE_CATEGORY)
        }
        suspend fun close() {
            pages.close(); pagesJob.join(); controls.close(); controlsJob.join(); monitorJob.cancelAndJoin()
            source.cancel(); receipts.cancel()
        }
    }
    private suspend fun TestScope.fixture(incoming: Boolean = false, immediate: Boolean = false, block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ride-reply-").toFile()
        try { DiagnosticJournal(directory).use { journal ->
            val f = Fixture(this, journal, incoming, immediate)
            try { f.settle(); f.block() } finally { withContext(NonCancellable) { f.close() } }
        } } finally { directory.deleteRecursively() }
    }

    @Test fun voiceAndReadbackNeverSendUntilANewSelectAndConsumeOnlyOnce() = runTest {
        fixture {
            manual()
            val recording = CompletableDeferred<String>()
            recognition = { recording.await() }
            val readback = CompletableDeferred<Unit>()
            confirmation = { readback.await() }
            action(Function.SELECT); state(RidePageStage.MESSAGE_LISTENING)
            assertTrue(sent.isEmpty()); recording.complete("  exact transcript  "); settle()
            state(RidePageStage.MESSAGE_CONFIRMATION)
            assertEquals("exact transcript", pages.state.value.replyText)
            assertEquals(listOf("one" to "exact transcript"), confirmed)
            assertTrue(sent.isEmpty())
            readback.complete(Unit); settle(); assertTrue(sent.isEmpty())
            val result = CompletableDeferred<MessageReplyResult>()
            delivery = {
                inbox.value = inbox.value.copy(messages = inbox.value.messages.map { it.copy(canReply = false) })
                result.await()
            }
            key(4); state(RidePageStage.MESSAGE_SENDING)
            key(4); action(Function.SELECT)
            assertEquals(listOf("one" to "exact transcript"), sent)
            scope.advanceTimeBy(1_100); settle(); state(RidePageStage.MESSAGE_SENDING)
            result.complete(MessageReplyResult.DISPATCHED); settle(); state(RidePageStage.MESSAGE_SENT)
            assertNull(pages.state.value.replyText)
            key(4); assertEquals(1, sent.size)
        }
    }

    @Test fun rerecordClearsTranscriptAndLateRecognitionCannotRestoreCancelledPage() = runTest {
        fixture {
            manual(); action(Function.SELECT); state(RidePageStage.MESSAGE_CONFIRMATION)
            val late = CompletableDeferred<String>()
            recognition = { withContext(NonCancellable) { late.await() } }
            action(Function.BACK); state(RidePageStage.MESSAGE_LISTENING)
            assertNull(pages.state.value.replyText)
            action(Function.BACK); state(RidePageStage.MESSAGE)
            late.complete("must disappear"); settle()
            state(RidePageStage.MESSAGE); assertNull(pages.state.value.replyText); assertTrue(sent.isEmpty())
            action(Function.SELECT); action(Function.NEXT); action(Function.SELECT)
            state(RidePageStage.MESSAGE_QUICK_LIST)
            assertEquals(quickReplies.value, pages.state.value.replyChoices)
            assertNull(pages.state.value.replyText)
            action(Function.MENU); state(RidePageStage.MENU); assertNull(pages.state.value.replyText)
        }
    }

    @Test fun fiveQuickRepliesUseASubmenuAndOnlyAFreshSelectDirectlySendsTheSelectedBody() = runTest {
        for (phone in listOf(true, false)) for (index in 0..4) fixture {
            suspend fun press(function: Function, code: Int) { if (phone) action(function) else key(code) }
            manual(); availability = RideReplyAvailability.MICROPHONE_REQUIRED
            assertEquals(listOf("語音回覆", "快速回覆"), pages.state.value.replyChoices)
            repeat(3) { press(Function.NEXT, 2) }
            assertEquals(1, pages.state.value.replyChoice)
            press(Function.SELECT, 4); state(RidePageStage.MESSAGE_QUICK_LIST)
            assertEquals(quickReplies.value, pages.state.value.replyChoices)
            press(Function.PREVIOUS, 1); assertEquals(0, pages.state.value.replyChoice)
            repeat(index) { press(Function.NEXT, 2) }
            press(Function.BACK, 3); state(RidePageStage.MESSAGE_CATEGORY)
            assertEquals(1, pages.state.value.replyChoice); assertTrue(sent.isEmpty())
            press(Function.SELECT, 4); state(RidePageStage.MESSAGE_QUICK_LIST)
            repeat(index) { press(Function.NEXT, 2) }
            if (index == 4) repeat(3) { press(Function.NEXT, 2) }
            assertEquals(index, pages.state.value.replyChoice)
            assertTrue(recognized.isEmpty()); assertTrue(confirmed.isEmpty()); assertTrue(sent.isEmpty())
            availability = RideReplyAvailability.NO_NETWORK
            press(Function.SELECT, 4); state(RidePageStage.MESSAGE_SENDING)
            press(Function.SELECT, 4); assertEquals(1, sent.size)
            scope.advanceTimeBy(1_001); settle(); state(RidePageStage.MESSAGE_SENT)
            assertEquals(listOf("one" to quickReplies.value[index]), sent)
            assertTrue(confirmed.isEmpty()); assertNull(pages.state.value.replyText)
        }
    }

    @Test fun preferenceReorderRefreshesTheViewAndRejectsThePhoneTapOnItsOldSnapshot() = runTest {
        fixture {
            manual(); action(Function.NEXT); action(Function.SELECT); state(RidePageStage.MESSAGE_QUICK_LIST)
            action(Function.NEXT)
            val reordered = quickReplies.value.reversed()
            quickReplies.value = reordered
            assertFalse(pages.performAction(Function.SELECT)) // Collector has not run; the shared handler fences the old view.
            settle(); state(RidePageStage.MESSAGE_QUICK_LIST)
            assertEquals(reordered, pages.state.value.replyChoices); assertEquals(1, pages.state.value.replyChoice)
            assertTrue(sent.isEmpty())
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            assertEquals(listOf("one" to reordered[1]), sent)
        }
    }

    @Test fun preferencesChangingAfterSelectButBeforeTheJobRunsAbortWithoutSendingOrRetrying() = runTest {
        fixture {
            manual(); action(Function.NEXT); action(Function.SELECT); action(Function.NEXT)
            assertTrue(pages.performAction(Function.SELECT))
            quickReplies.value = listOf("Latest only")
            settle(); state(RidePageStage.MESSAGE_QUICK_LIST)
            assertEquals(listOf("Latest only"), pages.state.value.replyChoices)
            assertEquals(0, pages.state.value.replyChoice); assertTrue(sent.isEmpty())
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            assertEquals(listOf("one" to "Latest only"), sent)
        }
    }

    @Test fun authoritativePreferencesCanRevokeAQuickReplyBeforeTheRelayCollectorCatchesUp() = runTest {
        fixture {
            manual(); action(Function.NEXT); action(Function.SELECT)
            val changed = listOf("Authority newer than relay")
            authoritativeQuickReplies = changed
            action(Function.SELECT); state(RidePageStage.MESSAGE_QUICK_LIST)
            assertTrue(sent.isEmpty()) // Relay still displays the old list, but authority refuses it.
            authoritativeQuickReplies = null
            assertTrue(pages.performAction(Function.SELECT))
            authoritativeQuickReplies = changed
            settle(); state(RidePageStage.MESSAGE_QUICK_LIST)
            assertTrue(sent.isEmpty()) // The job revalidates authority again before calling NativeMessages.
            quickReplies.value = changed; settle()
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            assertEquals(listOf("one" to changed.single()), sent)
        }
    }

    @Test fun pendingNativeDispatchRevalidatesPreferencesButAnAlreadyStartedSendNeverReadsAnotherBody() = runTest {
        fixture {
            manual(); action(Function.NEXT); action(Function.SELECT)
            val selected = quickReplies.value.first()
            val release = CompletableDeferred<Unit>()
            var nativeDispatched = false
            delivery = {
                release.await()
                if (pages.messageReplyCurrent("one")) { nativeDispatched = true; MessageReplyResult.DISPATCHED }
                else MessageReplyResult.STALE
            }
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            quickReplies.value = quickReplies.value.reversed(); settle()
            state(RidePageStage.MESSAGE_SENDING); assertFalse(pages.messageReplyCurrent("one"))
            action(Function.SELECT); assertEquals(listOf("one" to selected), sent)
            release.complete(Unit); scope.advanceTimeBy(1_001); settle()
            state(RidePageStage.MESSAGE_NOT_SENT); assertFalse(nativeDispatched); assertEquals(1, sent.size)
        }
    }

    @Test fun preferenceChangesAfterSourceActionConsumptionKeepTheOneSendAndItsResultOwner() = runTest {
        fixture {
            manual(); action(Function.NEXT); action(Function.SELECT); action(Function.NEXT)
            val selected = quickReplies.value[1]
            val finish = CompletableDeferred<MessageReplyResult>()
            delivery = {
                inbox.value = inbox.value.copy(messages = inbox.value.messages.map { it.copy(canReply = false) })
                finish.await()
            }
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            quickReplies.value = emptyList(); settle(); state(RidePageStage.MESSAGE_SENDING)
            action(Function.SELECT); assertEquals(listOf("one" to selected), sent)
            scope.advanceTimeBy(1_001); settle()
            finish.complete(MessageReplyResult.DISPATCHED); settle(); state(RidePageStage.MESSAGE_SENT)
            assertNull(pages.state.value.replyText); assertEquals(1, sent.size)
        }
    }

    @Test fun quickSubmenuKeepsTheSharedSourceModeAndCallSafetyFences() = runTest {
        for (change in 0..4) fixture {
            manual(); action(Function.NEXT); action(Function.SELECT); state(RidePageStage.MESSAGE_QUICK_LIST)
            when (change) {
                0 -> inbox.value = inbox.value.copy(messages = listOf(item("replacement")))
                1 -> inbox.value = inbox.value.copy(messages = emptyList())
                2 -> inbox.value = inbox.value.copy(access = MessageAccess.ACCESS_REQUIRED)
                3 -> key(10)
                4 -> callInbox.value = CallInboxState(CallAccess.READY, listOf(CallItem("call", "app", "package",
                    "Caller", CallKind.INCOMING, setOf(CallAction.ANSWER), false)))
            }
            settle(); assertFalse(pages.messageReplyCurrent("one"))
            quickReplies.value = quickReplies.value.reversed(); settle()
            assertFalse(pages.state.value.stage == RidePageStage.MESSAGE_QUICK_LIST)
            assertNull(pages.state.value.replyText); assertTrue(sent.isEmpty()); assertTrue(confirmed.isEmpty())
        }
    }

    @Test fun emptyAndInvalidQuickListsNeverSendAndTemplateBodiesStayOffTheWire() = runTest {
        fixture {
            quickReplies.value = emptyList()
            manual(); action(Function.NEXT); action(Function.SELECT); state(RidePageStage.MESSAGE_QUICK_LIST)
            assertTrue(pages.state.value.replyChoices.isEmpty())
            action(Function.NEXT); action(Function.PREVIOUS); action(Function.SELECT)
            state(RidePageStage.MESSAGE_QUICK_LIST); assertEquals(0, pages.state.value.replyChoice); assertTrue(sent.isEmpty())
            quickReplies.value = listOf(" ", "a".repeat(2001), "bad" + 0.toChar(), "PRIVATE_TEMPLATE_" + "字".repeat(1980))
            settle()
            repeat(3) { action(Function.SELECT); action(Function.NEXT) }
            assertTrue(sent.isEmpty())
            val projection = RideMessageReply.page(RidePageStage.MESSAGE_QUICK_LIST, 3, null, quickReplies.value) as Gen1Projection.Menu
            assertEquals("4", projection.line1); assertNull(projection.line2)
            assertEquals(4, projection.contentIcon); assertEquals(Gen1Projection.Arrow.RIGHT_BIG, projection.right)
            val encoded = Gen1Projection.encode(projection, 1)
            assertTrue(encoded.size < 100); assertFalse(encoded.toString(Charsets.UTF_8).contains("PRIVATE_TEMPLATE_"))
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            assertEquals(quickReplies.value.last(), sent.single().second)
        }
    }

    @Test fun voiceAvailabilityAndRecognitionErrorsStayExplicitAndNeverSend() = runTest {
        fixture {
            manual(); availability = RideReplyAvailability.RECONNECT_REQUIRED
            action(Function.SELECT); state(RidePageStage.MESSAGE_TRY_AGAIN)
            assertEquals(RideReplyAvailability.RECONNECT_REQUIRED, pages.state.value.replyError)
            assertTrue(recognized.isEmpty())
            availability = RideReplyAvailability.READY; recognition = { "   " }
            action(Function.SELECT); state(RidePageStage.MESSAGE_TRY_AGAIN)
            assertTrue(sent.isEmpty()); assertNull(pages.state.value.replyText)
        }
    }

    @Test fun sourceReplacementRemovalAccessLossAndModeChangesDiscardDraftAndLateCallbacks() = runTest {
        for (change in 0..4) fixture {
            manual()
            val late = CompletableDeferred<String>()
            recognition = { withContext(NonCancellable) { late.await() } }
            action(Function.SELECT); state(RidePageStage.MESSAGE_LISTENING)
            when (change) {
                0 -> inbox.value = inbox.value.copy(messages = listOf(item("replacement")))
                1 -> inbox.value = inbox.value.copy(messages = emptyList())
                2 -> inbox.value = inbox.value.copy(access = MessageAccess.ACCESS_REQUIRED)
                3 -> key(10)
                4 -> key(9)
            }
            settle(); assertFalse(pages.messageReplyCurrent("one"))
            late.complete("old reply"); settle()
            assertFalse(pages.state.value.stage == RidePageStage.MESSAGE_CONFIRMATION)
            assertNull(pages.state.value.replyText); assertTrue(sent.isEmpty())
        }
    }

    @Test fun callTakeoverClearsConfirmationAndDoesNotResumeDraft() = runTest {
        fixture {
            manual(); action(Function.SELECT); state(RidePageStage.MESSAGE_CONFIRMATION)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(CallItem("call", "app", "package", "Caller", CallKind.INCOMING,
                setOf(CallAction.ANSWER), false))); settle()
            state(RidePageStage.CALL); assertNull(pages.state.value.replyText)
            callInbox.value = CallInboxState(CallAccess.READY); settle()
            state(RidePageStage.MESSAGE); assertNull(pages.state.value.replyText); assertTrue(sent.isEmpty())
        }
    }

    @Test fun incomingReadCanReplyWhileReadingAndCancelYieldsToDefaultOwner() = runTest {
        fixture(incoming = true) {
            key(9)
            assertTrue(pages.openFunction(MainMenuSelection.Item.VOLUME)); settle()
            inbox.value = MessageInboxState(MessageAccess.READY, listOf(item("incoming"))); settle()
            state(RidePageStage.MESSAGE_PROMPT)
            val body = CompletableDeferred<Unit>()
            readAction = { body.await() }
            action(Function.SELECT); state(RidePageStage.MESSAGE_READ)
            assertEquals(MessageReadStatus.READING, pages.state.value.messageReadStatus)
            action(Function.SELECT); state(RidePageStage.MESSAGE_CATEGORY)
            action(Function.SELECT); state(RidePageStage.MESSAGE_CONFIRMATION)
            assertTrue(saved.contains("incoming"))
            action(Function.MENU); state(RidePageStage.MENU)
            assertTrue(saved.isEmpty()); assertNull(pages.state.value.replyText)
            body.complete(Unit); settle(); assertTrue(sent.isEmpty())
        }
    }

    @Test fun incomingCancelForgetsOnlyCurrentAndRestoresTheOrdinaryPage() = runTest {
        fixture(incoming = true) {
            key(9)
            assertTrue(pages.openFunction(MainMenuSelection.Item.VOLUME)); settle()
            inbox.value = MessageInboxState(MessageAccess.READY, listOf(item("incoming"))); settle()
            action(Function.SELECT); state(RidePageStage.MESSAGE_READ)
            action(Function.SELECT); state(RidePageStage.MESSAGE_CATEGORY)
            action(Function.BACK); state(RidePageStage.VOLUME)
            assertTrue(saved.isEmpty()); assertNull(pages.state.value.replyText); assertTrue(sent.isEmpty())
        }
    }

    @Test fun readbackFailureAndDispatchFailureRemainExplicitAndNeverRetry() = runTest {
        fixture {
            manual(); confirmation = { throw IllegalStateException("readback failed") }
            action(Function.SELECT); state(RidePageStage.MESSAGE_CONFIRMATION)
            assertEquals("Recognized reply", pages.state.value.replyText)
            assertTrue(sent.isEmpty())
            delivery = { MessageReplyResult.UNAVAILABLE }
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            scope.advanceTimeBy(1_001); settle(); state(RidePageStage.MESSAGE_NOT_SENT)
            assertNull(pages.state.value.replyText)
            action(Function.SELECT); assertEquals(1, sent.size)
            action(Function.BACK); state(RidePageStage.MESSAGE)
        }
    }

    @Test fun mediaAndFunctionOrderUpdatesDoNotCancelReplyAndCloseClearsIt() = runTest {
        fixture {
            manual()
            val late = CompletableDeferred<String>()
            recognition = { late.await() }
            action(Function.SELECT); state(RidePageStage.MESSAGE_LISTENING)
            pages.clearSelection(); order.value = order.value.reversed(); settle()
            state(RidePageStage.MESSAGE_LISTENING)
            late.complete("still current"); settle(); state(RidePageStage.MESSAGE_CONFIRMATION)
            pages.close(); settle(); state(RidePageStage.DISABLED)
            assertNull(pages.state.value.replyText); assertFalse(pages.messageReplyCurrent("one"))
        }
    }

    @Test fun categoryAndConfirmationAutoReturnButListeningAndReadbackBlockTheirTimers() = runTest {
        fixture {
            manual(); autoReturn.value = true; settle()
            scope.advanceTimeBy(15_001); settle(); state(RidePageStage.MENU)
            assertTrue(pages.openFunction(MainMenuSelection.Item.MESSAGE)); settle()
            action(Function.SELECT); action(Function.SELECT)
            val recording = CompletableDeferred<String>()
            recognition = { recording.await() }
            val readback = CompletableDeferred<Unit>()
            confirmation = { readback.await() }
            action(Function.SELECT); state(RidePageStage.MESSAGE_LISTENING)
            scope.advanceTimeBy(20_000); settle(); state(RidePageStage.MESSAGE_LISTENING)
            recording.complete("timeout review"); settle(); state(RidePageStage.MESSAGE_CONFIRMATION)
            scope.advanceTimeBy(20_000); settle(); state(RidePageStage.MESSAGE_CONFIRMATION)
            readback.complete(Unit); settle()
            scope.advanceTimeBy(15_001); settle(); state(RidePageStage.MESSAGE)
            assertNull(pages.state.value.replyText); assertTrue(sent.isEmpty())
        }
    }

    @Test fun categorySelectionRepairsTheTimerAfterItsNewRouteRevision() = runTest {
        fixture {
            manual(); autoReturn.value = true; settle()
            scope.advanceTimeBy(10_000); settle()
            action(Function.NEXT); action(Function.PREVIOUS)
            scope.advanceTimeBy(10_000); settle(); state(RidePageStage.MESSAGE_CATEGORY)
            scope.advanceTimeBy(5_001); settle(); state(RidePageStage.MENU)
            assertNull(pages.state.value.replyText); assertTrue(sent.isEmpty())
        }
    }

    @Test fun synchronousRecognitionCannotOverwriteTheManagedReadbackJob() = runTest {
        fixture(immediate = true) {
            manual(); autoReturn.value = true; settle()
            var cancelled = false
            confirmation = { try { awaitCancellation() } finally { cancelled = true } }
            action(Function.SELECT); state(RidePageStage.MESSAGE_CONFIRMATION)
            scope.advanceTimeBy(20_000); settle(); state(RidePageStage.MESSAGE_CONFIRMATION)
            assertFalse(cancelled)
            action(Function.MENU); state(RidePageStage.MENU)
            assertTrue(cancelled); assertNull(pages.state.value.replyText); assertTrue(sent.isEmpty())
        }
    }

    @Test fun resultSpeechOwnsThePageThenExitsAfterOneSecondWithoutResending() = runTest {
        for (result in listOf(MessageReplyResult.DISPATCHED, MessageReplyResult.UNAVAILABLE)) fixture(immediate = true) {
            manual(); action(Function.SELECT)
            val gate = CompletableDeferred<Unit>()
            report = { gate.await() }
            delivery = { result }
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            assertEquals(1, sent.size); assertTrue(reported.isEmpty())
            scope.advanceTimeBy(800); settle(); state(RidePageStage.MESSAGE_SENDING)
            scope.advanceTimeBy(41); settle()
            state(if (result == MessageReplyResult.DISPATCHED) RidePageStage.MESSAGE_SENT else RidePageStage.MESSAGE_NOT_SENT)
            assertTrue(pages.messageReplyResultCurrent("one")); assertFalse(pages.messageReplyCurrent("one"))
            assertNull(pages.state.value.replyText)
            scope.advanceTimeBy(20_000); settle()
            assertTrue(pages.messageReplyResultCurrent("one")); action(Function.SELECT)
            assertEquals(1, sent.size); assertEquals(listOf("one" to result), reported)
            gate.complete(Unit); settle()
            scope.advanceTimeBy(800); settle(); assertTrue(pages.messageReplyResultCurrent("one"))
            scope.advanceTimeBy(201); settle(); state(RidePageStage.MESSAGE)
            assertFalse(pages.messageReplyResultCurrent("one")); assertEquals(1, sent.size)
        }
    }

    @Test fun leavingDuringMinimumSendingIntervalCancelsLateResultAndNeverDispatchesAgain() = runTest {
        fixture(immediate = true) {
            manual(); action(Function.SELECT)
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            assertEquals(1, sent.size); assertTrue(reported.isEmpty())
            action(Function.SELECT); assertEquals(1, sent.size)
            action(Function.MENU); state(RidePageStage.MENU)
            scope.advanceTimeBy(2_001); settle(); state(RidePageStage.MENU)
            assertNull(pages.state.value.replyText); assertTrue(reported.isEmpty()); assertEquals(1, sent.size)
            assertFalse(pages.messageReplyResultCurrent("one"))
        }
    }

    @Test fun failedResultSpeechStillReleasesItsSourceWithoutRetry() = runTest {
        fixture(immediate = true) {
            manual(); action(Function.SELECT)
            report = { throw IllegalStateException("result speech unavailable") }
            action(Function.SELECT); state(RidePageStage.MESSAGE_SENDING)
            scope.advanceTimeBy(1_001); settle(); state(RidePageStage.MESSAGE_SENT)
            scope.advanceTimeBy(1_001); settle(); state(RidePageStage.MESSAGE)
            assertFalse(pages.messageReplyResultCurrent("one")); assertNull(pages.state.value.replyText)
            assertEquals(1, sent.size)
        }
    }

    @Test fun messageReadAdvertisesOnlyRealReplyAndManualReadChangesItsHeader() {
        val message = item("source")
        val read = MessageProjection.incoming(message, "Messages", canPlay = false, reading = true, canReply = true)
        assertEquals(Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 14, 3), read.right)
        val unsupported = MessageProjection.incoming(message.copy(canReply = false), "Messages", false, true, true)
        assertNull(unsupported.right.arrow)
        val prompt = MessageProjection.incoming(message, "Messages", true, false, true)
        assertEquals(3, prompt.right.contentIcon)
        val labels = RidePageLabels("Unknown", "Music", "Playing", "Choose", "Volume")
        assertEquals("Reply", MessageProjection.browse(listOf(message), message.id, labels,
            MessageReadStatus.COMPLETE).header)
    }

    @Test fun replyProjectionKeepsTranscriptOffWireAndShowsExplicitRetry() {
        val confirmation = RideMessageReply.page(RidePageStage.MESSAGE_CONFIRMATION, 0, null) as Gen1Projection.Live
        assertNull(confirmation.line1); assertNull(confirmation.line2)
        assertEquals(Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 7, 3), confirmation.right)
        val retry = RideMessageReply.page(RidePageStage.MESSAGE_TRY_AGAIN, 0, RideReplyAvailability.NO_NETWORK) as Gen1Projection.Live
        assertEquals(100, retry.contentIcon); assertEquals(6, retry.right.contentIcon)
        assertNull(RideMessageReply.text(" ")); assertNull(RideMessageReply.text("a".repeat(2001)))
    }

    companion object {
        private fun item(id: String) = MessageItem(id, "App", "package", "Sender", "Body", 1, true)
    }
}
