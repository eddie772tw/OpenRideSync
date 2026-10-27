package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.*
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.media.*
import org.openridesync.app.messages.*
import org.openridesync.app.navigation.*
import org.openridesync.app.calls.*
import org.openridesync.diagnostics.DiagnosticJournal
import java.nio.file.Files
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import org.openridesync.app.weather.*
import org.openridesync.honda.wire.WeatherWire

class RidePagesTest {
    private class Fixture(scope: CoroutineScope, journal: DiagnosticJournal, capabilities: Int?, startup: suspend () -> Unit,
                          immediateControl: Boolean, immediateMonitor: Boolean, weatherEnabled: Boolean, initialMode: Int?) {
        val source = Channel<ByteArray>(Channel.UNLIMITED).also { channel ->
            initialMode?.let { channel.trySend(byteArrayOf(it.toByte())) }
        }
        val receipts = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = Channel<ByteArray>(Channel.UNLIMITED)
        val monitor = Gen1InputMonitor(journal, journal.newOperation())
        val media = MutableStateFlow<MediaState?>(selected("first"))
        val calls = MutableStateFlow(CallContext(phoneObserved = true, cellularIdle = true, notificationUnknown = false))
        val autoReturn = MutableStateFlow(false)
        val functionOrder = MutableStateFlow<List<MainMenuSelection.Item>>(MainMenuSelection.Item.entries)
        val volume = MutableStateFlow<RideVolume.State?>(RideVolume.State(RideVolume.Stream.MEDIA, 3, 5, 0, 10, 50, 3, false, 1))
        val musicActions = mutableListOf<Pair<Function, String>>()
        val volumeActions = mutableListOf<Function>()
        val observed = mutableListOf<RidePageState>()
        var current = true
        var autoReceipt = true
        var holdNextReceipt: CompletableDeferred<Unit>? = null
        var volumeAction: suspend (Function) -> Unit = { volumeActions.add(it) }
        val weather = MutableStateFlow(RideWeatherState())
        var weatherAction: suspend () -> Unit = {}
        val messages = MutableStateFlow(MessageInboxState())
        val messageMode = MutableStateFlow(IncomingMessageMode.IGNORE)
        val promptRequests = mutableListOf<String>()
        var promptAction: suspend (String, IncomingMessageMode) -> Unit = { id, _ -> promptRequests.add(id) }
        var savedPending = emptySet<String>()
        val readRequests = mutableListOf<String>()
        var readAction: suspend (String) -> Unit = { readRequests.add(it) }
        val navigation = MutableStateFlow(NavigationObservation())
        val repeatRequests = mutableListOf<String>()
        var repeatAction: suspend (String) -> Unit = { repeatRequests.add(it) }
        val cancelRequests = mutableListOf<String>()
        var cancelAction: suspend (String) -> NavigationCancelResult = {
            cancelRequests.add(it); NavigationCancelResult.DISPATCHED
        }
        val callInbox = MutableStateFlow(CallInboxState())
        val callRequests = mutableListOf<Pair<String, CallAction>>()
        var callAction: suspend (String, CallAction) -> CallDispatchResult = { id, action ->
            callRequests.add(id to action)
            CallDispatchResult.DISPATCHED
        }
        var inputInterceptor: (Gen1InputMonitor.Input) -> Boolean = { false }
        var popupBlocked = false
        val controls = Gen1ControlExchange(receipts, { frame ->
            writes.send(frame.copyOf())
            val held = holdNextReceipt
            holdNextReceipt = null
            held?.await()
            if (autoReceipt) receipts.send(byteArrayOf(frame[0], frame[1], 1))
        }, journal, journal.newOperation())
        val page = RidePages(monitor, controls, media, calls, volume, capabilities,
            RidePageLabels("Unknown", "Music", "Playing", "Choose player", "Volume"), Locale.US, false,
            { current }, startup, { function, id -> musicActions.add(function to id) }, { volumeAction(it) }, autoReturn,
            if (weatherEnabled) weather else null, { weatherAction() }, functionOrder = functionOrder,
            messages = messages, readMessage = { readAction(it) }, navigation = navigation,
            repeatNavigation = { repeatAction(it) }, cancelNavigation = { cancelAction(it) },
            callInbox = callInbox, dispatchCall = { id, action -> callAction(id, action) },
            interceptInput = { inputInterceptor(it) }, popupBlocksInput = { popupBlocked },
            messageMode = messageMode, savePendingMessages = { savedPending = it; true },
            promptMessage = { id, mode -> promptAction(id, mode) })
        val monitorJob = scope.launch(if (immediateMonitor) Dispatchers.Unconfined else EmptyCoroutineContext,
            start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
        val controlJob = scope.launch(if (immediateControl) Dispatchers.Unconfined else EmptyCoroutineContext,
            start = CoroutineStart.UNDISPATCHED) { controls.run() }
        val pageJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { page.run() }
        val stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { page.state.collect { observed.add(it) } }

        suspend fun input(id: Int) {
            val before = monitor.sequence
            source.send(byteArrayOf(id.toByte()))
            while (monitor.sequence == before) yield()
            settle()
        }
        suspend fun settled(stage: RidePageStage) = page.state.first {
            it.stage == stage && !it.pending && it.receiptStatus == 1
        }
        suspend fun projected(expected: Gen1Projection.Page) {
            repeat(12) {
                val frame = writes.receive()
                if (frame.contentEquals(Gen1Projection.encode(expected, frame[1].toInt() and 255))) return
            }
            fail("Expected ride projection was not published")
        }
        suspend fun close() {
            page.close(); pageJob.join()
            controls.close(); controlJob.join()
            monitorJob.cancelAndJoin(); stateJob.cancelAndJoin()
            source.cancel(); receipts.cancel(); writes.cancel()
        }
    }

    private suspend fun fixture(capabilities: Int? = 4, startup: suspend () -> Unit = {},
                                immediateControl: Boolean = false,
                                immediateMonitor: Boolean = false,
                                weatherEnabled: Boolean = false, initialMode: Int? = 9,
                                timeout: Long = 5_000,
                                block: suspend Fixture.() -> Unit) = withTimeout(timeout) {
        val directory = Files.createTempDirectory("ride-pages-").toFile()
        try {
            DiagnosticJournal(directory).use { journal -> coroutineScope {
                val fixture = Fixture(this, journal, capabilities, startup, immediateControl,
                    immediateMonitor, weatherEnabled, initialMode)
                try { fixture.block() } finally { withContext(NonCancellable) { withTimeout(1_000) { fixture.close() } } }
            } }
        } finally { directory.deleteRecursively() }
    }

    @Test fun phoneAndHandlebarShareMusicActionsAndAwaitTheSameMenuReceipt() = runBlocking {
        for (phone in listOf(false, true)) fixture {
            suspend fun action(function: Function, id: Int) {
                if (phone) {
                    val sequence = monitor.sequence
                    val mode = monitor.mode.value
                    assertTrue(page.performAction(function))
                    settle()
                    assertEquals(sequence, monitor.sequence)
                    assertEquals(mode, monitor.mode.value)
                } else input(id)
            }
            settled(RidePageStage.MENU)
            action(Function.SELECT, 4); settled(RidePageStage.MUSIC)
            projected(MusicProjection.live(media.value!!, "Unknown", Locale.US, false))
            for ((function, id) in listOf(Function.SELECT to 4, Function.BACK to 3,
                Function.NEXT to 2, Function.PREVIOUS to 1)) action(function, id)
            assertEquals(listOf(Function.SELECT, Function.BACK, Function.NEXT, Function.PREVIOUS).map { it to "first" }, musicActions)
            assertTrue(volumeActions.isEmpty()) // MUSIC NEXT/PREVIOUS use the media dispatcher's volume branch.
            while (writes.tryReceive().isSuccess) { }
            autoReceipt = false
            action(Function.MENU, 8)
            val frame = writes.receive()
            page.state.first { it.stage == RidePageStage.MENU && it.pending }
            assertNull(page.state.value.receiptStatus)
            assertArrayEquals(Gen1Projection.encode(MusicProjection.menu(media.value, "Music", "Playing", "Unknown",
                Locale.US, false, first = true, last = false), frame[1].toInt() and 255), frame)
            receipts.send(byteArrayOf(frame[0], frame[1], 1))
            autoReceipt = true
            settled(RidePageStage.MENU)
        }
    }

    @Test fun phoneAndHandlebarShareNavigationConfirmationAndExplicitMessageRead() = runBlocking {
        for (phone in listOf(false, true)) fixture {
            suspend fun action(function: Function, id: Int) {
                if (phone) { assertTrue(page.performAction(function)); settle() } else input(id)
            }
            navigation.value = guidance("route")
            settled(RidePageStage.MENU); settle()
            assertTrue(page.openFunction(MainMenuSelection.Item.NAVIGATION)); settled(RidePageStage.NAVIGATION)
            action(Function.BACK, 3); settled(RidePageStage.NAVIGATION_CANCEL)
            assertTrue(cancelRequests.isEmpty()) // Back alone is only the confirmation page.
            projected(NavigationProjection.cancel("End navigation?"))
            action(Function.SELECT, 4)
            page.state.first { it.navigationCancelResult == NavigationCancelResult.DISPATCHED }
            assertEquals(listOf("route"), cancelRequests)
            action(Function.MENU, 8); settled(RidePageStage.MENU)
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settle()
            assertTrue(page.openFunction(MainMenuSelection.Item.MESSAGE)); settled(RidePageStage.MESSAGE)
            assertTrue(readRequests.isEmpty())
            action(Function.SELECT, 4)
            page.state.first { it.messageReadStatus == MessageReadStatus.COMPLETE }
            assertEquals(listOf("one"), readRequests)
            assertTrue(callRequests.isEmpty())
        }
    }

    @Test fun phoneDataTracksOwnedVolumeAndWeatherWithoutRepublishingTheMeter() = runBlocking {
        fixture(weatherEnabled = true) {
            settled(RidePageStage.MENU)
            assertTrue(page.openFunction(MainMenuSelection.Item.MUSIC)); settled(RidePageStage.MUSIC)
            while (writes.tryReceive().isSuccess) { }
            val report = RideWeatherState(RideWeatherStage.SPEAKING,
                current = CurrentWeather(WeatherWire.Weather.getDefaultInstance(),
                    WeatherReading(27f, 800, "Fixture place", "Clear", "Clear", true)),
                unit = TemperatureUnit.FAHRENHEIT)
            volume.value = volume.value!!.copy(current = 8, percentage = 80, level = 5)
            weather.value = report
            page.state.first { it.volumePercentage == 80 && it.weather == null }
            settle()
            assertTrue(writes.tryReceive().isFailure) // MUSIC projection did not change.
            assertNull(page.state.value.weather) // Another function cannot retain private report detail.

            volume.value = volume.value!!.copy(stream = RideVolume.Stream.CALL)
            page.state.first { it.volumePercentage == null }
            volume.value = volume.value!!.copy(stream = RideVolume.Stream.MEDIA)
            page.state.first { it.volumePercentage == 80 }
            input(10)
            page.state.first { it.volumePercentage == null && it.weather == null }
            input(9)
            page.state.first { it.volumePercentage == 80 && it.weather == null }
            assertTrue(page.returnToMenu()); settled(RidePageStage.MENU)
            weatherAction = { awaitCancellation() }
            assertTrue(page.openFunction(MainMenuSelection.Item.WEATHER)); settled(RidePageStage.STATUS_MENU)
            assertNull(page.state.value.weather)
            assertTrue(page.performAction(Function.SELECT)); settled(RidePageStage.WEATHER)
            page.state.first { it.weather == report }
            assertEquals(27f, page.state.value.weather!!.current!!.weather.celsius, 0f)
            assertEquals(TemperatureUnit.FAHRENHEIT, page.state.value.weather!!.unit)
            current = false
            weather.value = report.copy(stage = RideWeatherStage.COMPLETE)
            page.state.first { it.volumePercentage == null && it.weather == null }
            current = true
            page.close(); pageJob.join()
            volume.value = volume.value!!.copy(percentage = 10)
            weather.value = report
            settle()
            assertNull(page.state.value.volumePercentage)
            assertNull(page.state.value.weather)
        }
    }

    @Test fun phoneMenuPublishesTheProviderOrderAndTheRealHandlebarFocus() = runBlocking {
        fixture(weatherEnabled = true) {
            settled(RidePageStage.MENU)
            assertEquals(listOf(MainMenuSelection.Item.WEATHER, MainMenuSelection.Item.MUSIC, MainMenuSelection.Item.VOLUME),
                page.state.value.menuItems)
            assertEquals(MainMenuSelection.Item.MUSIC, page.state.value.menuSelected)
            input(2); settled(RidePageStage.MENU)
            assertEquals(MainMenuSelection.Item.VOLUME, page.state.value.menuSelected)
            functionOrder.value = MainMenuSelection.Item.entries.reversed()
            page.state.first { it.menuItems == listOf(MainMenuSelection.Item.VOLUME,
                MainMenuSelection.Item.MUSIC, MainMenuSelection.Item.WEATHER) }
            assertEquals(MainMenuSelection.Item.VOLUME, page.state.value.menuSelected)
            calls.value = calls.value.copy(cellularEstablished = true, cellularIdle = false)
            page.state.first { it.menuItems == listOf(MainMenuSelection.Item.VOLUME, MainMenuSelection.Item.WEATHER) }
            assertEquals(MainMenuSelection.Item.VOLUME, page.state.value.menuSelected)
            messages.value = MessageInboxState(MessageAccess.READY)
            calls.value = calls.value.copy(cellularEstablished = false, cellularIdle = true)
            page.state.first { MainMenuSelection.Item.MESSAGE in it.menuItems }
            assertEquals(MainMenuSelection.Item.VOLUME, page.state.value.menuSelected)
        }
    }

    @Test fun phoneFunctionNavigationWaitsForRealReceiptsAndCancelsWeatherOnReturn() = runBlocking {
        fixture(weatherEnabled = true) {
            settled(RidePageStage.MENU)
            while (writes.tryReceive().isSuccess) { }
            autoReceipt = false
            assertTrue(page.openFunction(MainMenuSelection.Item.VOLUME))
            val frame = writes.receive()
            page.state.first { it.stage == RidePageStage.VOLUME && it.pending }
            assertNull(page.state.value.receiptStatus)
            assertEquals(MainMenuSelection.Item.VOLUME, page.state.value.menuSelected)
            assertFalse(page.openFunction(MainMenuSelection.Item.MUSIC)) // Only the menu owns entry.
            autoReceipt = true
            receipts.send(byteArrayOf(frame[0], frame[1], 1))
            settled(RidePageStage.VOLUME)
            assertTrue(page.returnToMenu()); settled(RidePageStage.MENU)

            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            weatherAction = { started.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } }
            assertTrue(page.openFunction(MainMenuSelection.Item.WEATHER)); settled(RidePageStage.STATUS_MENU)
            assertFalse(started.isCompleted)
            assertTrue(page.performAction(Function.SELECT))
            started.await(); settled(RidePageStage.WEATHER)
            assertTrue(page.returnToMenu()); stopped.await(); settled(RidePageStage.MENU)
            assertEquals(MainMenuSelection.Item.WEATHER, page.state.value.menuSelected)
            assertTrue(musicActions.isEmpty()); assertTrue(volumeActions.isEmpty())
            assertTrue(readRequests.isEmpty()); assertTrue(callRequests.isEmpty())
        }
    }

    @Test fun phoneNavigationRejectsUnknownHuPopupStaleClosedAndUnsupportedOwners() = runBlocking {
        fixture(initialMode = null) {
            settled(RidePageStage.MENU)
            val actions = listOf(Function.SELECT, Function.BACK, Function.MENU, Function.NEXT, Function.PREVIOUS,
                Function.VOLUME_UP, Function.VOLUME_DOWN, Function.VOLUME_MUTE)
            val sequence = monitor.sequence
            for (function in actions) assertFalse(page.performAction(function))
            assertFalse(page.performAction(Function.MODE_SAB))
            assertNull(monitor.mode.value.function)
            assertEquals(sequence, monitor.sequence)
            assertFalse(page.openFunction(MainMenuSelection.Item.VOLUME))
            assertFalse(page.returnToMenu())
            input(9)
            popupBlocked = true
            assertFalse(page.openFunction(MainMenuSelection.Item.VOLUME))
            for (function in actions) assertFalse(page.performAction(function))
            popupBlocked = false
            assertTrue(page.openFunction(MainMenuSelection.Item.VOLUME)); settled(RidePageStage.VOLUME)
            input(10)
            for (function in actions) assertFalse(page.performAction(function))
            assertEquals(Function.MODE_HU, monitor.mode.value.function)
            assertFalse(page.returnToMenu())
            assertFalse(page.openFunction(MainMenuSelection.Item.MUSIC))
            input(9)
            assertTrue(page.returnToMenu()); settled(RidePageStage.MENU)
            current = false
            for (function in actions) assertFalse(page.performAction(function))
            assertFalse(page.openFunction(MainMenuSelection.Item.MUSIC))
            assertFalse(page.returnToMenu())
            current = true
            page.close(); pageJob.join()
            for (function in actions) assertFalse(page.performAction(function))
            assertFalse(page.openFunction(MainMenuSelection.Item.VOLUME))
            assertFalse(page.returnToMenu())
            assertTrue(page.state.value.menuItems.isEmpty())
            assertNull(page.state.value.menuSelected)
            assertTrue(musicActions.isEmpty()); assertTrue(volumeActions.isEmpty())
        }
        fixture(capabilities = null) {
            page.state.first { it.stage == RidePageStage.UNSUPPORTED }
            assertTrue(page.state.value.menuItems.isEmpty())
            assertFalse(page.openFunction(MainMenuSelection.Item.VOLUME))
            assertFalse(page.performAction(Function.SELECT))
            assertFalse(page.returnToMenu())
        }
    }

    @Test fun phoneNavigationCannotInventAProviderOrDismissIncomingMessageOwnership() = runBlocking {
        fixture {
            settled(RidePageStage.MENU)
            for (item in listOf(MainMenuSelection.Item.WEATHER, MainMenuSelection.Item.MESSAGE,
                MainMenuSelection.Item.NAVIGATION, MainMenuSelection.Item.CALL)) {
                assertFalse(page.openFunction(item))
            }
            assertEquals(MainMenuSelection.Item.MUSIC, page.state.value.menuSelected)
            messages.value = MessageInboxState(MessageAccess.READY)
            settle()
            assertTrue(page.openFunction(MainMenuSelection.Item.MESSAGE)); settled(RidePageStage.MESSAGE)
            assertTrue(readRequests.isEmpty())
            assertTrue(page.returnToMenu()); settled(RidePageStage.MENU)
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN; settle()
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MESSAGE_PROMPT)
            assertFalse(page.returnToMenu())
            assertFalse(page.openFunction(MainMenuSelection.Item.MUSIC))
            assertTrue(readRequests.isEmpty())
        }
    }

    @Test fun incomingAskQueuesWithoutReadingAndYieldsBackToTheExactMusicSource() = runBlocking {
        fixture {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN; settle()
            assertTrue(page.open()); settled(RidePageStage.MUSIC)
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("two").copy(postedAt = 2), message("one")))
            settled(RidePageStage.MESSAGE_PROMPT)
            assertEquals(listOf("one"), promptRequests); assertTrue(readRequests.isEmpty())
            input(4); settled(RidePageStage.MESSAGE_READ)
            assertEquals(listOf("one"), readRequests)
            assertEquals(MessageReadStatus.COMPLETE, page.state.value.messageReadStatus)
            input(2); assertEquals(listOf(Function.NEXT), volumeActions)
            input(3); settled(RidePageStage.MESSAGE_PROMPT)
            assertEquals(listOf("one", "two"), promptRequests)
            assertEquals(setOf("two"), savedPending)
            input(3); settled(RidePageStage.MUSIC)
            assertTrue(savedPending.isEmpty()); assertTrue(musicActions.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun automaticReadWaitsForAnnouncementAndTimeoutStartsAfterSpeech() = runTest {
        fixture(timeout = 50_000) {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.AUTO_READ; settle()
            val announced = CompletableDeferred<Unit>()
            promptAction = { id, _ -> promptRequests.add(id); announced.await() }
            autoReturn.value = true
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MESSAGE_PROMPT)
            advanceTimeBy(20_000); runCurrent()
            assertEquals(RidePageStage.MESSAGE_PROMPT, page.state.value.stage)
            assertTrue(readRequests.isEmpty())
            announced.complete(Unit)
            settled(RidePageStage.MESSAGE_READ)
            assertEquals(listOf("one"), readRequests)
            advanceTimeBy(14_980); runCurrent()
            assertEquals(RidePageStage.MESSAGE_READ, page.state.value.stage)
            advanceTimeBy(20); runCurrent()
            settled(RidePageStage.MENU)
            assertEquals(setOf("one"), savedPending) // Timeout yields; it is not Back's removal.
        }
    }

    @Test fun livePreferenceAndCallSourcesRevokeSpeechBeforeTheirCollectorsResume() = runBlocking {
        fixture {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN; settle()
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MESSAGE_PROMPT)
            assertTrue(page.messageSpeechCurrent("one"))
            messageMode.value = IncomingMessageMode.IGNORE
            assertFalse(page.messageSpeechCurrent("one"))
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN
            assertTrue(page.messageSpeechCurrent("one"))
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("call", CallKind.INCOMING)))
            assertFalse(page.messageSpeechCurrent("one"))
            settled(RidePageStage.CALL)
            assertTrue(readRequests.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun interruptedReadResumesAsFailedAndItsOwnTimeoutStillYields() = runTest {
        fixture(timeout = 50_000) {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN; settle()
            autoReturn.value = true
            readAction = { id -> readRequests.add(id); awaitCancellation() }
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MESSAGE_PROMPT); input(4)
            page.state.first { it.messageReadStatus == MessageReadStatus.READING }
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("call", CallKind.INCOMING)))
            settled(RidePageStage.CALL)
            advanceTimeBy(20_000); runCurrent()
            callInbox.value = CallInboxState(CallAccess.READY)
            settled(RidePageStage.MESSAGE_READ)
            assertEquals(MessageReadStatus.FAILED, page.state.value.messageReadStatus)
            assertEquals(listOf("one"), readRequests)
            advanceTimeBy(15_000); runCurrent()
            settled(RidePageStage.MENU)
            assertEquals(setOf("one"), savedPending)
        }
    }

    @Test fun failedAutomaticAnnouncementNeverAuthorizesBodyReadAndMenuClearsPending() = runBlocking {
        fixture {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.AUTO_READ; settle()
            promptAction = { _, _ -> throw IllegalStateException("synthetic failure") }
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one"), message("two")))
            settled(RidePageStage.MESSAGE_PROMPT)
            assertEquals(MessageReadStatus.FAILED, page.state.value.messageReadStatus)
            assertTrue(readRequests.isEmpty())
            input(8); settled(RidePageStage.MENU)
            assertTrue(savedPending.isEmpty())
        }
    }

    @Test fun callPreemptsReadingKeepsFifoAndDoesNotReplayTheInterruptedBody() = runBlocking {
        fixture {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN; settle()
            val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            readAction = { id -> readRequests.add(id); try { entered.complete(Unit); awaitCancellation() }
                finally { cancelled.complete(Unit) } }
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MESSAGE_PROMPT); input(4); entered.await()
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("call", CallKind.INCOMING)))
            settled(RidePageStage.CALL); cancelled.await()
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one"), message("two").copy(postedAt = 2)))
            settle(); assertEquals(RidePageStage.CALL, page.state.value.stage)
            callInbox.value = CallInboxState(CallAccess.READY)
            settled(RidePageStage.MESSAGE_READ)
            assertEquals(MessageReadStatus.FAILED, page.state.value.messageReadStatus)
            assertEquals(listOf("one"), readRequests)
            input(3); settled(RidePageStage.MESSAGE_PROMPT)
            assertEquals(listOf("one", "two"), promptRequests)
        }
    }

    @Test fun replacementFencesTheOldConfirmBeforeOpeningAnotherSource() = runBlocking {
        fixture(immediateMonitor = true) {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN; settle()
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MESSAGE_PROMPT)
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one").copy(id = "replacement")))
            input(4); settle()
            assertTrue(readRequests.isEmpty())
            input(4); settled(RidePageStage.MESSAGE_READ)
            assertEquals(listOf("replacement"), readRequests)
            input(9); settle()
            assertEquals(MessageReadStatus.COMPLETE, page.state.value.messageReadStatus)
            assertEquals(listOf("replacement"), readRequests)
        }
    }

    @Test fun changingAutoReadToAskCancelsTheOldAnnouncementBeforeItCanRead() = runBlocking {
        fixture {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.AUTO_READ; settle()
            val held = CompletableDeferred<Unit>()
            promptAction = { id, mode -> promptRequests.add(id)
                if (mode == IncomingMessageMode.AUTO_READ) held.await() }
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MESSAGE_PROMPT)
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN
            page.state.first { it.messageCanRead && it.messageReadStatus == MessageReadStatus.COMPLETE }
            held.complete(Unit); settle()
            assertTrue(readRequests.isEmpty())
            input(4); settled(RidePageStage.MESSAGE_READ)
            assertEquals(listOf("one"), readRequests)
        }
    }

    @Test fun messageYieldRestoresStatusSubmenuWithoutRestartingInterruptedWeather() = runBlocking {
        fixture(weatherEnabled = true) {
            settled(RidePageStage.MENU); input(9)
            messages.value = MessageInboxState(MessageAccess.READY)
            messageMode.value = IncomingMessageMode.ASK_TO_LISTEN; settle()
            var reports = 0
            val cancelled = CompletableDeferred<Unit>()
            weatherAction = {
                reports++
                weather.value = RideWeatherState(RideWeatherStage.LOADING)
                try { awaitCancellation() } finally {
                    weather.value = weather.value.copy(stage = RideWeatherStage.IDLE)
                    cancelled.complete(Unit)
                }
            }
            input(1); input(1); input(4); settled(RidePageStage.STATUS_MENU)
            assertEquals(0, reports)
            input(4); settled(RidePageStage.WEATHER)
            assertEquals(RideWeatherStage.LOADING, page.state.value.weather?.stage)
            assertFalse(page.state.value.weatherCanRetry)
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MESSAGE_PROMPT); cancelled.await()
            input(3); settled(RidePageStage.STATUS_MENU)
            assertEquals(1, reports)
            assertEquals(0, page.state.value.statusChoice)
            assertNull(page.state.value.weather)
            assertFalse(page.state.value.weatherCanRetry)
            assertTrue(page.performAction(Function.SELECT)); settled(RidePageStage.WEATHER)
            assertEquals(2, reports) // Explicit retry, never automatic report replay.
            assertFalse(page.state.value.weatherCanRetry)
            input(10)
            assertFalse(page.state.value.weatherCanRetry)
        }
    }

    @Test fun overlayConsumesInputBeforeOrdinaryMusicAndVolumeHandlers() = runBlocking {
        fixture {
            settled(RidePageStage.MENU)
            input(4); settled(RidePageStage.MUSIC)
            val intercepted = mutableListOf<Function>()
            inputInterceptor = { intercepted.add(it.function); true }
            input(4); input(3); input(2); input(8)
            assertEquals(listOf(Function.SELECT, Function.BACK, Function.NEXT, Function.MENU), intercepted)
            assertTrue(musicActions.isEmpty())
            assertTrue(volumeActions.isEmpty())
            assertEquals(RidePageStage.MUSIC, page.state.value.stage)
            inputInterceptor = { false }
            input(3)
            assertEquals(listOf(Function.BACK to "first"), musicActions)
        }
    }

    @Test fun incomingOverlayInvalidatesAlreadyQueuedOrdinaryAction() = runBlocking {
        fixture(immediateMonitor = true) {
            settled(RidePageStage.MENU)
            input(4); settled(RidePageStage.MUSIC)
            // The overlay becomes authoritative before the collector/queued action resumes.
            val before = monitor.sequence
            source.send(byteArrayOf(4))
            assertEquals(before + 1, monitor.sequence)
            popupBlocked = true
            settle()
            assertTrue(musicActions.isEmpty())
            assertTrue(volumeActions.isEmpty())
            popupBlocked = false
            input(4)
            assertEquals(listOf(Function.SELECT to "first"), musicActions)
        }
    }

    @Test fun reorderedMenuRetainsMusicFocusAndUsesVisibleNewOrder() = runBlocking {
        fixture {
            settled(RidePageStage.MENU)
            functionOrder.value = MainMenuSelection.Item.entries.reversed()
            settle()
            input(4); settled(RidePageStage.MUSIC) // Still MUSIC, not its old numeric index.
            input(8); settled(RidePageStage.MENU)
            input(1); settled(RidePageStage.MENU) // VOLUME is now before MUSIC; unsupported rows are skipped.
            input(4); settled(RidePageStage.VOLUME)
        }
    }

    @Test fun callHidingMusicInReversedOrderKeepsEarlierVolumeReachable() = runBlocking {
        fixture(weatherEnabled = false) {
            settled(RidePageStage.MENU)
            functionOrder.value = MainMenuSelection.Item.entries.reversed()
            settle()
            calls.value = calls.value.copy(cellularEstablished = true, cellularIdle = false)
            page.state.first { it.stage == RidePageStage.MENU && !it.volumeAvailable }
            settled(RidePageStage.MENU) // A real replacement menu page must receive its ACK.
            input(4); settled(RidePageStage.VOLUME)
            assertTrue(musicActions.isEmpty())
        }
    }

    @Test fun callHidingLastMusicPublishesEarlierWeatherAndAllowsVolumeNavigation() = runBlocking {
        fixture(weatherEnabled = true) {
            settled(RidePageStage.MENU)
            functionOrder.value = listOf(MainMenuSelection.Item.WEATHER, MainMenuSelection.Item.VOLUME,
                MainMenuSelection.Item.NAVIGATION, MainMenuSelection.Item.CALL,
                MainMenuSelection.Item.MESSAGE, MainMenuSelection.Item.MUSIC)
            settle()
            calls.value = calls.value.copy(cellularEstablished = true, cellularIdle = false)
            page.state.first { it.stage == RidePageStage.MENU && !it.volumeAvailable }
            settled(RidePageStage.MENU) // Previously no replacement page was sent: this timed out.
            input(2); settled(RidePageStage.MENU) // From the first visible Weather item to Volume.
            input(4); settled(RidePageStage.VOLUME)
            assertTrue(musicActions.isEmpty())
        }
    }

    @Test fun weatherSurvivesMediaReplacementAndVolumeButStopsOnLeavingSab() = runBlocking {
        fixture(weatherEnabled = true) {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            weatherAction = {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            settled(RidePageStage.MENU)
            input(9) // MODE_SAB
            input(1) // Previous visible entry: Weather.
            input(4); settled(RidePageStage.STATUS_MENU) // Entry never starts speech.
            assertFalse(started.isCompleted)
            input(4) // Fresh Select starts the selected forecast.
            started.await()
            settled(RidePageStage.WEATHER)
            page.clearSelection()
            media.value = selected("replacement")
            input(5) // Global volume up must not cancel report.
            input(2) // Weather Next volume down must not cancel report.
            assertFalse(stopped.isCompleted)
            assertEquals(RidePageStage.WEATHER, page.state.value.stage)
            input(10) // MODE_HU ends only the weather route/report.
            stopped.await()
            settled(RidePageStage.MENU)
            assertTrue(volumeActions.contains(Function.VOLUME_UP))
            assertTrue(volumeActions.contains(Function.NEXT))
        }
    }

    @Test fun messagesBrowseOnlyReadsOnExplicitSelect() = runBlocking {
        fixture {
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one"), message("two")))
            settled(RidePageStage.MENU)
            input(9) // MODE_SAB
            input(1) // MESSAGE is immediately before MUSIC in the default order.
            input(4); settled(RidePageStage.MESSAGE)
            assertTrue(readRequests.isEmpty())
            input(2); settled(RidePageStage.MESSAGE)
            assertTrue(readRequests.isEmpty())
            input(4)
            page.state.first { it.stage == RidePageStage.MESSAGE && it.messageReadStatus == MessageReadStatus.COMPLETE }
            assertEquals(listOf("two"), readRequests)
            input(1); settled(RidePageStage.MESSAGE)
            input(4)
            page.state.first { it.stage == RidePageStage.MESSAGE && it.messageReadStatus == MessageReadStatus.COMPLETE }
            assertEquals(listOf("two", "one"), readRequests)
            input(8); settled(RidePageStage.MENU)
        }
    }

    @Test fun emptyMessagePageShowsNewArrivalWithoutReadingUntilSelect() = runBlocking {
        fixture {
            messages.value = MessageInboxState(MessageAccess.READY)
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.MESSAGE)
            assertTrue(readRequests.isEmpty())
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("arrived")))
            settle()
            assertTrue(readRequests.isEmpty())
            input(4)
            page.state.first { it.stage == RidePageStage.MESSAGE && it.messageReadStatus == MessageReadStatus.COMPLETE }
            assertEquals(listOf("arrived"), readRequests)
        }
    }

    @Test fun messagesCancelReadWhenExactSourceDisappears() = runBlocking {
        fixture {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            readAction = { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.MESSAGE)
            input(4); started.await()
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("replacement")))
            page.state.first { it.stage == RidePageStage.MENU }
            cancelled.await()
            assertEquals(RidePageStage.MENU, page.state.value.stage)
            // A replacement must not inherit the old selection or start a read.
            assertFalse(page.state.value.messageReadStatus == MessageReadStatus.COMPLETE)
        }
    }

    @Test fun messageReadIgnoresMediaAndMenuRepairsButCancelsOnModeReassertion() = runBlocking {
        fixture {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            readAction = { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.MESSAGE)
            input(4); started.await()
            media.value = selected("different player")
            page.clearSelection()
            functionOrder.value = MainMenuSelection.Item.entries.reversed()
            settle()
            assertEquals(RidePageStage.MESSAGE, page.state.value.stage)
            assertEquals(MessageReadStatus.READING, page.state.value.messageReadStatus)
            assertFalse(cancelled.isCompleted)
            input(9) // A fresh mode revision cancels even when the function stays MODE_SAB.
            cancelled.await()
            settled(RidePageStage.MENU)
        }
    }

    @Test fun messageBackAndCallGateCancelWithoutReadingAgain() = runBlocking {
        fixture {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            var starts = 0
            readAction = { starts++; started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
            messages.value = MessageInboxState(MessageAccess.READY, listOf(message("one")))
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.MESSAGE)
            input(4); started.await()
            input(3); cancelled.await(); settled(RidePageStage.MENU)
            calls.value = calls.value.copy(notificationUnknown = true)
            settle()
            input(4)
            assertNotEquals(RidePageStage.MESSAGE, page.state.value.stage)
            assertEquals(1, starts)
        }
    }

    @Test fun navigationUpdatesProjectionWithoutAutomaticSpeechAndClearsUnknownTurn() = runBlocking {
        fixture {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            repeatAction = { repeatRequests.add(it); started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) } }
            navigation.value = NavigationObservation(NavigationStatus.WAITING)
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.NAVIGATION)
            assertTrue(repeatRequests.isEmpty())
            while (writes.tryReceive().isSuccess) { }
            navigation.value = guidance("first")
            projected(checkNotNull(NavigationProjection.active(NavigationProjection.Maneuver.RIGHT,
                "300 m", "Navigating", true, true)))
            assertTrue(repeatRequests.isEmpty())
            input(4); started.await()
            input(2) // NEXT is volume down even while speech is active.
            assertTrue(Function.NEXT in volumeActions)
            assertFalse(stopped.isCompleted)
            navigation.value = guidance("second", maneuver = null)
            stopped.await()
            projected(NavigationProjection.waiting("Navigating", true, true))
            assertEquals(listOf("first"), repeatRequests)
            media.value = selected("replacement")
            page.clearSelection()
            functionOrder.value = MainMenuSelection.Item.entries.reversed()
            settle()
            assertEquals(RidePageStage.NAVIGATION, page.state.value.stage)
            assertEquals(listOf("first"), repeatRequests)
            input(8); settled(RidePageStage.MENU) // Home does not request Maps stop.
            assertTrue(cancelRequests.isEmpty())
        }
    }

    @Test fun navigationMenuKeepsFocusDuringNotificationReloadAndSelectDoesNotClaimLaunch() = runBlocking {
        fixture {
            navigation.value = NavigationObservation(NavigationStatus.WAITING)
            settled(RidePageStage.MENU)
            input(9); input(1) // Focus the visible Navigation item.
            navigation.value = NavigationObservation(NavigationStatus.LOADING)
            settle()
            input(4); settled(RidePageStage.NAVIGATION)
            assertTrue(repeatRequests.isEmpty())
            assertTrue(cancelRequests.isEmpty())
            navigation.value = guidance("observed")
            settled(RidePageStage.NAVIGATION)
            assertTrue(repeatRequests.isEmpty())
        }
    }

    @Test fun sameSourceLosingRepeatEligibilityStopsSpeech() = runBlocking {
        fixture {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            repeatAction = { repeatRequests.add(it); started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) } }
            navigation.value = guidance("one")
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.NAVIGATION)
            input(4); started.await()
            navigation.value = guidance("one").copy(canRepeat = false)
            stopped.await()
            input(4)
            assertEquals(listOf("one"), repeatRequests)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun navigationRepeatPausesAutoReturnUntilSpeechCompletes() = runTest {
        fixture(timeout = 60_000) {
            val finish = CompletableDeferred<Unit>()
            autoReturn.value = true
            repeatAction = { repeatRequests.add(it); finish.await() }
            navigation.value = guidance("one")
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.NAVIGATION)
            input(4)
            assertEquals(listOf("one"), repeatRequests)
            advanceTimeBy(20_000); runCurrent()
            assertEquals(RidePageStage.NAVIGATION, page.state.value.stage)
            finish.complete(Unit)
            settle()
            advanceTimeBy(14_999); runCurrent()
            assertEquals(RidePageStage.NAVIGATION, page.state.value.stage)
            advanceTimeBy(1); runCurrent()
            settled(RidePageStage.MENU)
            assertTrue(cancelRequests.isEmpty())
        }
    }

    @Test fun navigationCancelRequiresFreshSecondSelectAndDispatchIsNotRouteEnd() = runBlocking {
        fixture {
            navigation.value = guidance("first")
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.NAVIGATION)
            input(3); settled(RidePageStage.NAVIGATION_CANCEL)
            input(3); settled(RidePageStage.NAVIGATION)
            assertTrue(cancelRequests.isEmpty())
            input(3); settled(RidePageStage.NAVIGATION_CANCEL)
            navigation.value = guidance("second")
            settled(RidePageStage.NAVIGATION) // Old confirmation cannot follow the new source.
            assertTrue(cancelRequests.isEmpty())
            input(3); settled(RidePageStage.NAVIGATION_CANCEL)
            input(4)
            page.state.first { it.stage == RidePageStage.NAVIGATION &&
                it.navigationCancelResult == NavigationCancelResult.DISPATCHED }
            assertEquals(listOf("second"), cancelRequests)
            assertEquals(RidePageStage.NAVIGATION, page.state.value.stage)
            navigation.value = guidance("second").copy(canCancel = false, canRepeat = false)
            settle()
            input(3); input(4)
            assertEquals(listOf("second"), cancelRequests)
            assertTrue(repeatRequests.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun queuedSelectFromOldNavigationSourceCannotRepeatTheReplacement() = runTest {
        fixture(immediateMonitor = true) {
            navigation.value = guidance("old")
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.NAVIGATION)
            // Queue the source collector first, then accept SELECT before it can apply the new ID.
            navigation.value = guidance("new")
            val before = monitor.sequence
            source.send(byteArrayOf(4))
            assertEquals(before + 1, monitor.sequence)
            runCurrent()
            assertTrue(repeatRequests.isEmpty())
            input(4)
            assertEquals(listOf("new"), repeatRequests)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun queuedOldNavigationFrameIsReplacedAndLossAtLastMenuItemClearsTurn() = runTest {
        fixture(immediateControl = true, timeout = 60_000) {
            functionOrder.value = listOf(MainMenuSelection.Item.MUSIC, MainMenuSelection.Item.VOLUME,
                MainMenuSelection.Item.NAVIGATION, MainMenuSelection.Item.WEATHER,
                MainMenuSelection.Item.CALL, MainMenuSelection.Item.MESSAGE)
            navigation.value = guidance("one")
            settled(RidePageStage.MENU)
            input(9); input(2); input(2); input(4); settled(RidePageStage.NAVIGATION)
            while (writes.tryReceive().isSuccess) { }
            val release = CompletableDeferred<Unit>()
            holdNextReceipt = release
            val blocker = controls.submit { byteArrayOf(10, it.toByte()) }
            writes.receive()
            navigation.value = guidance("stale", NavigationProjection.Maneuver.RIGHT, distance = "100 m")
            advanceTimeBy(10); runCurrent()
            navigation.value = guidance("fresh", NavigationProjection.Maneuver.LEFT, distance = "200 m")
            advanceTimeBy(10); runCurrent()
            release.complete(Unit); blocker.await()
            val frame = writes.receive()
            val expected = checkNotNull(NavigationProjection.active(NavigationProjection.Maneuver.LEFT,
                "200 m", "Navigating", true, true))
            assertArrayEquals(Gen1Projection.encode(expected, frame[1].toInt() and 255), frame)
            navigation.value = NavigationObservation(NavigationStatus.ACCESS_REQUIRED)
            settled(RidePageStage.MENU)
            val repaired = writes.receive()
            val menu = MusicProjection.menu(media.value, "Music", "Playing", "Unknown",
                Locale.US, false, first = true, last = false)
            assertArrayEquals(Gen1Projection.encode(menu, repaired[1].toInt() and 255), repaired)
            assertTrue(cancelRequests.isEmpty())
        }
    }

    @Test fun navigationCallOrModeChangeCancelsRepeatAndLeavesExternalRouteAlone() = runBlocking {
        fixture {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            repeatAction = { repeatRequests.add(it); started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) } }
            navigation.value = guidance("first")
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.NAVIGATION)
            input(4); started.await()
            calls.value = calls.value.copy(notificationUnknown = true)
            stopped.await(); settled(RidePageStage.MENU)
            assertTrue(cancelRequests.isEmpty())
            calls.value = calls.value.copy(notificationUnknown = false)
            settle()
            input(1); input(4); settled(RidePageStage.NAVIGATION)
            input(9) // Even a repeated SAB mode receipt fences the old ride page.
            settled(RidePageStage.MENU)
            assertEquals(listOf("first"), repeatRequests)
            assertTrue(cancelRequests.isEmpty())
        }
    }

    @Test fun incomingCallOpensWithoutAnswerAndSameEpisodeDoesNotReenterAfterMenu() = runBlocking {
        fixture {
            settled(RidePageStage.MENU)
            input(9)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("ringing", CallKind.INCOMING)))
            settled(RidePageStage.CALL)
            assertTrue(callRequests.isEmpty())
            input(8); settled(RidePageStage.MENU)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("refreshed", CallKind.INCOMING)))
            settle()
            assertEquals(RidePageStage.MENU, page.state.value.stage)
            input(4); settled(RidePageStage.CALL)
            callAction = { id, action ->
                callRequests.add(id to action)
                callInbox.value = CallInboxState(CallAccess.READY,
                    listOf(call(id, CallKind.INCOMING).copy(actions = emptySet())))
                CallDispatchResult.DISPATCHED
            }
            input(4)
            page.state.first { it.stage == RidePageStage.CALL &&
                it.callDispatchResult == CallDispatchResult.DISPATCHED }
            input(4)
            assertEquals(listOf("refreshed" to CallAction.ANSWER), callRequests)
        }
    }

    @Test fun ongoingCallRequiresFreshHangupConfirmationAndHasNoAutoReturn() = runTest {
        fixture(timeout = 40_000) {
            settled(RidePageStage.MENU)
            input(9)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("old", CallKind.ONGOING)))
            settle()
            input(1); input(4); settled(RidePageStage.CALL)
            autoReturn.value = true
            delay(20_000)
            assertEquals(RidePageStage.CALL, page.state.value.stage)
            input(3); settled(RidePageStage.CALL_CANCEL)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("new", CallKind.ONGOING)))
            settled(RidePageStage.CALL)
            input(4)
            assertTrue(callRequests.isEmpty()) // Old confirmation cannot act on the replacement.
            input(3); settled(RidePageStage.CALL_CANCEL)
            input(4)
            page.state.first { it.stage == RidePageStage.CALL &&
                it.callDispatchResult == CallDispatchResult.DISPATCHED }
            assertEquals(listOf("new" to CallAction.HANG_UP), callRequests)
            callInbox.value = CallInboxState(CallAccess.READY)
            settled(RidePageStage.MENU)
        }
    }

    @Test fun incomingReplacingOngoingStartsOneEpisodeOnly() = runBlocking {
        fixture {
            settled(RidePageStage.MENU)
            input(9)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("ongoing", CallKind.ONGOING)))
            settle()
            input(1); input(4); settled(RidePageStage.CALL)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("incoming", CallKind.INCOMING)))
            settle()
            input(8); settled(RidePageStage.MENU)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("refreshed", CallKind.INCOMING)))
            settle()
            assertEquals(RidePageStage.MENU, page.state.value.stage)
            assertTrue(callRequests.isEmpty())
        }
    }

    @Test fun callModeAndAccessLossFenceControlWithoutUsingCallContextAsGate() = runBlocking {
        fixture {
            settled(RidePageStage.MENU)
            input(9)
            calls.value = calls.value.copy(cellularEstablished = true, notificationActive = true)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("ring", CallKind.INCOMING)))
            settled(RidePageStage.CALL)
            callInbox.value = CallInboxState(CallAccess.ACCESS_REQUIRED)
            settled(RidePageStage.MENU)
            assertTrue(callRequests.isEmpty())
            input(10) // MODE_HU exits the SAB page without dispatch.
            assertTrue(callRequests.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun queuedOldHangupSelectCannotAnswerReplacementIncoming() = runTest {
        fixture(immediateMonitor = true) {
            settled(RidePageStage.MENU)
            input(9)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("old", CallKind.ONGOING)))
            settle()
            input(1); input(4); settled(RidePageStage.CALL)
            input(3); settled(RidePageStage.CALL_CANCEL)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("new", CallKind.INCOMING)))
            val before = monitor.sequence
            source.send(byteArrayOf(4)) // Old confirmation's Select reaches the input stream.
            assertEquals(before + 1, monitor.sequence)
            runCurrent()
            assertEquals(RidePageStage.CALL, page.state.value.stage)
            assertTrue(callRequests.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun multipleCallSourcesRejectQueuedOldCallFrame() = runTest {
        fixture(immediateControl = true, timeout = 60_000) {
            settled(RidePageStage.MENU)
            input(9)
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("first", CallKind.INCOMING)))
            settled(RidePageStage.CALL)
            while (writes.tryReceive().isSuccess) { }
            val release = CompletableDeferred<Unit>()
            holdNextReceipt = release
            val blocker = controls.submit { byteArrayOf(10, it.toByte()) }
            writes.receive()
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("queued", CallKind.INCOMING)))
            advanceTimeBy(10); runCurrent()
            callInbox.value = CallInboxState(CallAccess.READY,
                listOf(call("queued", CallKind.INCOMING), call("other", CallKind.INCOMING)))
            advanceTimeBy(10); runCurrent()
            release.complete(Unit); blocker.await()
            settled(RidePageStage.MENU)
            val frame = writes.receive()
            val expected = MusicProjection.menu(media.value, "Music", "Playing", "Unknown",
                Locale.US, false, first = true, last = false)
            assertArrayEquals(Gen1Projection.encode(expected, frame[1].toInt() and 255), frame)
            assertTrue(callRequests.isEmpty())
        }
    }

    @Test fun incomingCallStopsNavigationRepeatWithoutDispatchingCallAction() = runBlocking {
        fixture {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            repeatAction = { repeatRequests.add(it); started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) } }
            navigation.value = guidance("route")
            settled(RidePageStage.MENU)
            input(9); input(1); input(4); settled(RidePageStage.NAVIGATION)
            input(4); started.await()
            callInbox.value = CallInboxState(CallAccess.READY, listOf(call("incoming", CallKind.INCOMING)))
            stopped.await(); settled(RidePageStage.CALL)
            assertEquals(listOf("route"), repeatRequests)
            assertTrue(callRequests.isEmpty())
            assertTrue(cancelRequests.isEmpty())
        }
    }

    @Test fun queuedMusicMenuIsRejectedBeforeVisibilityCollectorCanRepairSelection() = runBlocking {
        fixture(immediateControl = true) {
            volume.value = null // Availability remains false across the call-state change.
            settled(RidePageStage.MENU)
            while (writes.tryReceive().isSuccess) { }
            val release = CompletableDeferred<Unit>()
            holdNextReceipt = release
            val blocker = controls.submit { byteArrayOf(10, it.toByte()) }
            writes.receive()
            media.value = selected("first").copy(title = "Queued stale title")
            page.state.first { it.stage == RidePageStage.MENU && it.pending }
            // Flow collectors use runBlocking's dispatcher. Unconfined control resumes inline,
            // so its freshness check occurs before they can repair MainMenuSelection.
            calls.value = calls.value.copy(cellularEstablished = true)
            release.complete(Unit)
            blocker.await()
            assertTrue(writes.tryReceive().isFailure)
            settled(RidePageStage.MENU)
            val repaired = writes.receive()
            val expected = VolumeProjection.menu("Volume", first = true, last = true)
            val encoded = Gen1Projection.encode(expected, repaired[1].toInt() and 255)
            assertArrayEquals(encoded, repaired)
        }
    }

    @Test fun startupCompletionNotReceiptOpensMainAndFailureOrCancellationCannotEnterIt() = runBlocking {
        val release = CompletableDeferred<Unit>()
        fixture(startup = { release.await() }) {
            autoReceipt = false
            val splash = writes.receive()
            assertEquals(RidePageStage.SPLASH, page.state.value.stage)
            assertFalse(page.open())
            release.complete(Unit)
            page.state.first { it.stage == RidePageStage.MENU }
            // Main transition happened without acknowledging the held Splash command.
            autoReceipt = true
            receipts.send(byteArrayOf(splash[0], splash[1], 7))
            settled(RidePageStage.MENU)
            assertFalse(observed.any { it.stage == RidePageStage.MENU && it.receiptStatus == 7 })
        }
        fixture(startup = { throw IllegalStateException("synthetic failure") }) {
            settled(RidePageStage.STARTUP_FAILED)
            assertFalse(page.open())
            input(4)
            assertEquals(RidePageStage.STARTUP_FAILED, page.state.value.stage)
        }
        val cancelled = CompletableDeferred<Unit>()
        fixture(startup = { cancelled.await() }) {
            settled(RidePageStage.SPLASH)
            page.close(); pageJob.join(); cancelled.complete(Unit); settle()
            assertEquals(RidePageStage.DISABLED, page.state.value.stage)
            assertFalse(observed.any { it.stage == RidePageStage.MENU })
        }
    }

    @Test fun unknownAndHuRejectAllKeysBeforeOverlaysAndSabAloneResumesActions() = runBlocking {
        fixture(null) {
            page.state.first { it.stage == RidePageStage.UNSUPPORTED }
            assertFalse(page.open()); input(4)
            assertTrue(writes.tryReceive().isFailure); assertTrue(musicActions.isEmpty())
        }
        fixture(initialMode = null, immediateMonitor = true) {
            settled(RidePageStage.MENU)
            assertNull(monitor.mode.value.function)
            val intercepted = mutableListOf<Function>()
            inputInterceptor = { intercepted.add(it.function); false }
            for (id in 1..8) input(id)
            assertTrue(musicActions.isEmpty()); assertTrue(volumeActions.isEmpty()); assertTrue(intercepted.isEmpty())
            settled(RidePageStage.MENU)
            input(9); input(4); settled(RidePageStage.MUSIC)
            input(3)
            assertEquals(listOf(Function.BACK to "first"), musicActions)
            input(10); intercepted.clear()
            for (id in 1..8) input(id)
            assertTrue(intercepted.isEmpty()); assertTrue(volumeActions.isEmpty())
            assertEquals(listOf(Function.BACK to "first"), musicActions)
            input(9); input(4)
            assertEquals(listOf(Function.BACK, Function.SELECT), musicActions.map { it.first })
        }
    }

    @Test fun boundedMenuPausedSelectionVolumeRoutingAndBlockedMusicRepair() = runBlocking {
        fixture {
            media.value = null
            settled(RidePageStage.MENU)
            input(4); settled(RidePageStage.NO_SESSION)
            input(3); settled(RidePageStage.MENU)
            calls.value = calls.value.copy(cellularEstablished = true, cellularIdle = false)
            // Visibility repair must publish even when it occurs in the independent provider collector.
            page.state.first { it.stage == RidePageStage.MENU && !it.volumeAvailable }
            input(4); settled(RidePageStage.VOLUME)
        }
        fixture {
            media.value = selected("first").copy(playback = MediaPlayback.PAUSED)
            settled(RidePageStage.MENU)
            input(1) // First visible item cannot wrap to Volume.
            input(4); settled(RidePageStage.MUSIC)
            assertTrue(musicActions.isEmpty())
            input(8); settled(RidePageStage.MENU)
            input(2); settled(RidePageStage.MENU)
            input(2) // Last visible item cannot wrap to Music.
            input(4); settled(RidePageStage.VOLUME)
            input(4); assertTrue(volumeActions.isEmpty())
            input(2); input(1)
            assertEquals(listOf(Function.NEXT, Function.PREVIOUS), volumeActions)
            input(3); settled(RidePageStage.MENU)
            input(4); settled(RidePageStage.VOLUME) // Home retained Volume selection.
            input(8); settled(RidePageStage.MENU)
            input(1); input(4); settled(RidePageStage.MUSIC)
            calls.value = calls.value.copy(cellularEstablished = true)
            settle()
            assertEquals(RidePageStage.MUSIC, page.state.value.stage)
            input(3); assertTrue(musicActions.isEmpty())
            calls.value = calls.value.copy(cellularEstablished = false)
            settled(RidePageStage.MUSIC)
            input(3); assertEquals(listOf(Function.BACK to "first"), musicActions)
            calls.value = calls.value.copy(cellularEstablished = true)
            settle() // Wait for the call transition's input fence before sending a new Menu command.
            input(8); settled(RidePageStage.MENU)
            input(4); settled(RidePageStage.VOLUME)
            assertFalse(page.open())
        }
    }

    @Test fun volumeAlwaysTimesOutAndOnlyActionsOrPreferenceRestartMusicTimer() = runBlocking {
        fixture(timeout = 40_000) {
            settled(RidePageStage.MENU)
            input(2); input(4); settled(RidePageStage.VOLUME)
            delay(1_000)
            input(4) // Ignored Select still restarts the15-second timer.
            val started = System.nanoTime()
            val updates = launch {
                repeat(70) {
                    media.value = selected("first").copy(title = "Update $it")
                    if (it % 10 == 0) input(10) // Mode, metadata and real volume polls cannot reset it.
                    volume.value = volume.value!!.copy(current = if (it % 2 == 0) 4 else 5)
                    delay(250)
                }
            }
            settled(RidePageStage.MENU)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("Early timeout: $elapsed", elapsed >= 14_900)
            assertTrue("Polling extended timeout: $elapsed", elapsed < 17_500)
            updates.cancelAndJoin()
            assertTrue(page.open()); settled(RidePageStage.MUSIC)
            delay(50)
            autoReturn.value = true
            settled(RidePageStage.MENU)
            assertTrue(page.open()); settled(RidePageStage.MUSIC)
            autoReturn.value = false
            page.close(); pageJob.join()
            assertEquals(RidePageStage.DISABLED, page.state.value.stage)
        }
    }

    @Test fun selectionFenceLateReceiptUnknownVolumeAndCancelableActionsCannotResurrectOldOwner() = runBlocking {
        fixture {
            settled(RidePageStage.MENU)
            while (writes.tryReceive().isSuccess) { }
            autoReceipt = false
            assertTrue(page.open())
            val old = writes.receive()
            page.clearSelection(); media.value = selected("second")
            settle()
            autoReceipt = true
            receipts.send(byteArrayOf(old[0], old[1], 7))
            settled(RidePageStage.MENU)
            assertFalse(observed.any { it.receiptStatus == 7 })
            input(3); assertTrue(musicActions.isEmpty())
            assertTrue(page.open()); settled(RidePageStage.MUSIC)
            input(3); assertEquals(listOf(Function.BACK to "second"), musicActions)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            volumeAction = { try { entered.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) } }
            input(7); entered.await() // Held mute cannot block MENU navigation.
            input(8); settled(RidePageStage.MENU); cancelled.await()
            calls.value = calls.value.copy(notificationUnknown = true)
            page.state.first { !it.volumeAvailable }
            calls.value = calls.value.copy(notificationUnknown = false, notificationActive = true)
            volume.value = volume.value!!.copy(stream = RideVolume.Stream.MEDIA)
            settle()
            assertFalse(page.state.value.volumeAvailable) // CALL context cannot use an old MEDIA poll sample.
            current = false
            input(4); assertEquals(1, musicActions.size)
            page.close(); pageJob.join()
            assertEquals(RidePageStage.DISABLED, page.state.value.stage)
        }
    }

    companion object {
        private fun guidance(id: String, maneuver: NavigationProjection.Maneuver? = NavigationProjection.Maneuver.RIGHT,
            canCancel: Boolean = true, distance: String = "300 m") = NavigationObservation(NavigationStatus.OBSERVED,
            MapsInstruction("Turn on Main Street", distance, "5 min", maneuver),
            sourceId = id, canCancel = canCancel, canRepeat = true)
        private fun call(id: String, kind: CallKind) = CallItem(id, "Dialer", "synthetic.dialer",
            "Caller", kind, if (kind == CallKind.INCOMING)
                setOf(CallAction.ANSWER, CallAction.DECLINE) else setOf(CallAction.HANG_UP), false)
        private fun message(id: String) = MessageItem(id, "Chat", "synthetic.chat", "From $id",
            "Body $id", 1L, false)
        private fun selected(id: String) = MediaState(access = MediaAccess.GRANTED,
            sessions = listOf(MediaSessionChoice(id, "Player", "synthetic.player")), selectedId = id,
            title = "Track", artist = "Artist", playback = MediaPlayback.PLAYING, actions = 0)
        private suspend fun settle() { repeat(20) { yield() } }
    }
}
