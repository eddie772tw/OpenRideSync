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
import org.openridesync.app.calls.*
import org.openridesync.app.media.*
import org.openridesync.app.messages.*
import org.openridesync.app.weather.*
import org.openridesync.diagnostics.DiagnosticJournal

/** Native submenu and synthetic report owners; no Android sampling, audio or vehicle acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideStatusPagesTest {
    private class Fixture(val scope: TestScope, journal: DiagnosticJournal, statusEnabled: Boolean = true) {
        val source = Channel<ByteArray>(Channel.UNLIMITED)
        val receipts = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        val monitor = Gen1InputMonitor(journal, journal.newOperation())
        val calls = MutableStateFlow(CallContext(phoneObserved = true, cellularIdle = true, notificationUnknown = false))
        val callInbox = MutableStateFlow(CallInboxState(CallAccess.READY))
        val media = MutableStateFlow<MediaState?>(null)
        val order = MutableStateFlow(MainMenuSelection.Item.entries.toList())
        val messages = MutableStateFlow(MessageInboxState(MessageAccess.READY))
        val autoReturn = MutableStateFlow(false)
        val weather = MutableStateFlow(RideWeatherState())
        val report = MutableStateFlow(RideStatusReportState())
        val requested = mutableListOf<RideStatusKind>()
        val volume = mutableListOf<Function>()
        var weatherRequests = 0
        var current = true
        var popup = false
        var cancelledStatus = 0
        var cancelledWeather = 0
        var reportAction: suspend (RideStatusKind) -> Unit = { kind ->
            report.value = RideStatusReportState(kind, RideStatusReportStage.COMPLETE)
        }
        var weatherAction: suspend () -> Unit = { weather.value = RideWeatherState(RideWeatherStage.COMPLETE) }
        val controls = Gen1ControlExchange(receipts, { frame ->
            writes.add(frame.copyOf()); receipts.send(byteArrayOf(frame[0], frame[1], 1))
        }, journal, journal.newOperation())
        val pages = RidePages(monitor, controls, media, calls, MutableStateFlow(null), 4,
            RidePageLabels("Unknown", "Music", "Playing", "Choose", "Volume"), Locale.US, false,
            { current }, {}, { _, _ -> }, { volume.add(it) }, autoReturn,
            weather = weather, reportWeather = { weatherRequests++; weatherAction() },
            cancelWeather = { cancelledWeather++; weather.value = RideWeatherState() },
            functionOrder = order, callInbox = callInbox, messages = messages,
            popupBlocksInput = { popup }, messageMode = MutableStateFlow(IncomingMessageMode.ASK_TO_LISTEN),
            statusReport = if (statusEnabled) report else null,
            reportStatus = { kind ->
                requested.add(kind); report.value = RideStatusReportState(kind, RideStatusReportStage.LOADING)
                reportAction(kind)
            }, cancelStatus = { cancelledStatus++; report.value = RideStatusReportState() })
        val monitorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
        val controlsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { controls.run() }
        val pagesJob = scope.launch(UnconfinedTestDispatcher(scope.testScheduler), start = CoroutineStart.UNDISPATCHED) { pages.run() }
        fun settle() { repeat(4) { scope.runCurrent(); scope.advanceTimeBy(20) }; scope.runCurrent() }
        suspend fun key(id: Int) { source.send(byteArrayOf(id.toByte())); settle() }
        fun action(function: Function) {
            val sequence = monitor.sequence
            val mode = monitor.mode.value
            assertTrue(pages.performAction(function)); settle()
            assertEquals(sequence, monitor.sequence); assertEquals(mode, monitor.mode.value)
        }
        fun state(stage: RidePageStage) { assertEquals(stage, pages.state.value.stage) }
        suspend fun menu() {
            key(9); assertTrue(pages.openFunction(MainMenuSelection.Item.WEATHER)); settle()
            state(RidePageStage.STATUS_MENU)
            assertTrue(pages.state.value.statusCanReport)
        }
        suspend fun close() {
            pages.close(); pagesJob.join(); controls.close(); controlsJob.join(); monitorJob.cancelAndJoin()
            source.cancel(); receipts.cancel()
        }
    }
    private suspend fun TestScope.fixture(statusEnabled: Boolean = true, block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ride-status-pages-").toFile()
        try { DiagnosticJournal(directory).use { journal ->
            val fixture = Fixture(this, journal, statusEnabled)
            try { fixture.settle(); fixture.block() }
            finally { withContext(NonCancellable) { fixture.close() } }
        } } finally { directory.deleteRecursively() }
    }

    @Test fun entryAndFocusNeverSpeakAndAllFourSelectionsAreBoundedAndShared() = runTest {
        for (phone in listOf(true, false)) fixture {
            menu()
            suspend fun press(function: Function, id: Int) { if (phone) action(function) else key(id) }
            repeat(3) { press(Function.PREVIOUS, 1) }
            assertEquals(0, pages.state.value.statusChoice)
            assertEquals(0, weatherRequests); assertTrue(requested.isEmpty())
            for (choice in 0..3) {
                assertEquals(choice, pages.state.value.statusChoice)
                press(Function.SELECT, 4); state(RidePageStage.STATUS_MENU)
                assertEquals(choice, pages.state.value.statusChoice)
                if (choice < 3) press(Function.NEXT, 2)
            }
            repeat(4) { press(Function.NEXT, 2) }
            assertEquals(3, pages.state.value.statusChoice)
            assertEquals(1, weatherRequests)
            assertEquals(listOf(RideStatusKind.BATTERY, RideStatusKind.MOBILE, RideStatusKind.WIFI), requested)
            assertTrue(volume.isEmpty()); assertNull(pages.state.value.statusReport)
            action(Function.MENU); assertTrue(pages.openFunction(MainMenuSelection.Item.WEATHER)); settle()
            assertEquals(0, pages.state.value.statusChoice); assertEquals(1, weatherRequests)
        }
    }

    @Test fun activeReportsDoNotOverlapAndDirectionalKeysRemainVolume() = runTest {
        fixture {
            menu(); action(Function.NEXT)
            val done = CompletableDeferred<Unit>()
            reportAction = { kind ->
                assertTrue(pages.statusReportCurrent(kind)); assertTrue(pages.statusReportCurrent())
                report.value = RideStatusReportState(kind, RideStatusReportStage.SPEAKING, "PRIVATE_SSID")
                done.await(); report.value = RideStatusReportState(kind, RideStatusReportStage.COMPLETE)
            }
            action(Function.SELECT); state(RidePageStage.STATUS_REPORT)
            assertEquals("PRIVATE_SSID", pages.state.value.statusReport?.detail)
            action(Function.SELECT); key(4); assertEquals(1, requested.size)
            action(Function.NEXT); action(Function.PREVIOUS); action(Function.VOLUME_UP)
            assertEquals(listOf(Function.NEXT, Function.PREVIOUS, Function.VOLUME_UP), volume)
            pages.clearSelection(); order.value = order.value.reversed(); media.value = MediaState(); settle()
            assertTrue(pages.statusReportCurrent(RideStatusKind.BATTERY))
            done.complete(Unit); settle(); state(RidePageStage.STATUS_MENU)
            assertEquals(1, pages.state.value.statusChoice); assertNull(pages.state.value.statusReport)
        }
    }

    @Test fun failureStaysForExplicitRetryAndBackCancelsWithoutReplaying() = runTest {
        fixture {
            menu(); action(Function.NEXT); action(Function.NEXT)
            reportAction = { kind -> report.value = RideStatusReportState(kind, RideStatusReportStage.FAILED, error = "Unavailable") }
            action(Function.SELECT); state(RidePageStage.STATUS_REPORT)
            assertEquals(RideStatusReportStage.FAILED, pages.state.value.statusReport?.stage)
            assertFalse(pages.statusReportCurrent()); assertTrue(pages.state.value.statusCanReport)
            assertEquals(listOf(RideStatusKind.MOBILE), requested)
            val finish = CompletableDeferred<Unit>()
            var cancelled = false
            reportAction = { try { finish.await() } finally { cancelled = true } }
            action(Function.SELECT); state(RidePageStage.STATUS_REPORT); assertEquals(2, requested.size)
            action(Function.BACK); state(RidePageStage.STATUS_MENU)
            assertTrue(cancelled); assertTrue(cancelledStatus > 0); assertEquals(2, pages.state.value.statusChoice)
            assertNull(pages.state.value.statusReport)
            finish.complete(Unit); settle(); state(RidePageStage.STATUS_MENU); assertEquals(2, requested.size)
        }
    }

    @Test fun completionFlowReturnsToTheSubmenuAndCancelsTheFinishedReportOwner() = runTest {
        fixture {
            menu(); action(Function.NEXT)
            var released = false
            reportAction = { try { awaitCancellation() } finally { released = true } }
            action(Function.SELECT); state(RidePageStage.STATUS_REPORT)
            report.value = RideStatusReportState(RideStatusKind.BATTERY, RideStatusReportStage.COMPLETE)
            settle(); state(RidePageStage.STATUS_MENU)
            assertTrue(released); assertEquals(1, pages.state.value.statusChoice)
            assertNull(pages.state.value.statusReport); assertEquals(1, requested.size)
        }
    }

    @Test fun weatherUsesTheSameExplicitOwnerAndReturnsToItsSubmenu() = runTest {
        fixture {
            menu()
            val done = CompletableDeferred<Unit>()
            weatherAction = {
                assertTrue(pages.weatherReportCurrent())
                weather.value = RideWeatherState(RideWeatherStage.SPEAKING)
                done.await(); weather.value = RideWeatherState(RideWeatherStage.COMPLETE)
            }
            action(Function.SELECT); state(RidePageStage.WEATHER)
            action(Function.SELECT); assertEquals(1, weatherRequests)
            action(Function.NEXT); action(Function.PREVIOUS)
            assertEquals(listOf(Function.NEXT, Function.PREVIOUS), volume)
            done.complete(Unit); settle(); state(RidePageStage.STATUS_MENU)
            assertNull(pages.state.value.weather); assertFalse(pages.weatherReportCurrent())
            val late = CompletableDeferred<Unit>()
            weatherAction = { withContext(NonCancellable) { late.await() }; weather.value = RideWeatherState(RideWeatherStage.COMPLETE) }
            action(Function.SELECT); state(RidePageStage.WEATHER)
            action(Function.BACK); state(RidePageStage.STATUS_MENU)
            late.complete(Unit); settle(); state(RidePageStage.STATUS_MENU)
            assertEquals(2, weatherRequests)
        }
    }

    @Test fun modeRevisionAndAccountPopupOrCloseFenceLateCompletionAndClearDetail() = runTest {
        for (interruption in 0..5) fixture {
            menu(); action(Function.NEXT)
            val late = CompletableDeferred<Unit>()
            reportAction = { kind ->
                report.value = RideStatusReportState(kind, RideStatusReportStage.SPEAKING, "PRIVATE_NAME")
                withContext(NonCancellable) { late.await() }
                report.value = RideStatusReportState(kind, RideStatusReportStage.COMPLETE, "PRIVATE_NAME")
            }
            action(Function.SELECT); state(RidePageStage.STATUS_REPORT)
            when (interruption) {
                0 -> key(10)
                1 -> key(9)
                2 -> { popup = true; assertFalse(pages.statusReportCurrent()) }
                3 -> { current = false; assertFalse(pages.statusReportCurrent()) }
                4 -> pages.close()
                5 -> action(Function.MENU)
            }
            late.complete(Unit); settle()
            assertFalse(pages.statusReportCurrent())
            assertNull(pages.state.value.statusReport)
            assertEquals(1, requested.size)
            if (interruption == 0) { assertFalse(pages.performAction(Function.SELECT)); assertFalse(pages.openFunction(MainMenuSelection.Item.WEATHER)) }
        }
    }

    @Test fun incomingAndCallsRestoreOnlyASafeSubmenu() = runTest {
        for (call in listOf(false, true)) fixture {
            menu(); action(Function.NEXT); action(Function.NEXT); action(Function.NEXT)
            var cancelled = false
            reportAction = { kind ->
                report.value = RideStatusReportState(kind, RideStatusReportStage.SPEAKING, "PRIVATE_WIFI")
                try { awaitCancellation() } finally { cancelled = true }
            }
            action(Function.SELECT); state(RidePageStage.STATUS_REPORT)
            if (call) {
                callInbox.value = CallInboxState(CallAccess.READY, listOf(CallItem("call", "App", "package", "Caller",
                    CallKind.INCOMING, setOf(CallAction.ANSWER), false))); settle(); state(RidePageStage.CALL)
                callInbox.value = CallInboxState(CallAccess.READY); settle()
            } else {
                messages.value = MessageInboxState(MessageAccess.READY,
                    listOf(MessageItem("message", "App", "package", "Sender", "Body", 1, false))); settle()
                state(RidePageStage.MESSAGE_PROMPT); action(Function.BACK)
            }
            state(RidePageStage.STATUS_MENU); assertEquals(3, pages.state.value.statusChoice)
            assertTrue(cancelled); assertNull(pages.state.value.statusReport); assertEquals(1, requested.size)
        }
    }

    @Test fun onlyObservedIdlePhoneAndKnownQuietNotificationsAuthorizeReports() = runTest {
        val blocked = listOf(
            CallContext(phoneObserved = false, cellularIdle = true, notificationUnknown = false),
            CallContext(phoneObserved = true, cellularIdle = false, notificationUnknown = false),
            CallContext(phoneObserved = true, cellularIdle = true, notificationUnknown = true),
            CallContext(phoneObserved = true, cellularIdle = true, notificationUnknown = false, notificationIncoming = true),
            CallContext(phoneObserved = true, cellularIdle = true, notificationUnknown = false, notificationActive = true))
        for (context in blocked) fixture {
            key(9); calls.value = context; settle()
            if (context.musicBlocked) assertFalse(pages.openFunction(MainMenuSelection.Item.WEATHER))
            else {
                assertTrue(pages.openFunction(MainMenuSelection.Item.WEATHER)); settle()
                state(RidePageStage.STATUS_MENU); assertFalse(pages.state.value.statusCanReport)
                action(Function.SELECT); state(RidePageStage.STATUS_MENU)
                action(Function.NEXT); assertEquals(1, pages.state.value.statusChoice)
                assertFalse(pages.state.value.statusCanReport)
                action(Function.SELECT); state(RidePageStage.STATUS_MENU)
                action(Function.PREVIOUS); assertEquals(0, pages.state.value.statusChoice)
            }
            assertFalse(pages.statusReportCurrent()); assertFalse(pages.weatherReportCurrent())
            assertFalse(pages.state.value.statusCanReport)
            assertTrue(requested.isEmpty()); assertEquals(0, weatherRequests)
        }
    }

    @Test fun losingObservedIdleOrNotificationKnowledgeCancelsAnActiveReportAndClearsPrivateDetail() = runTest {
        for (unknownPhone in listOf(true, false)) fixture {
            menu(); action(Function.NEXT)
            val late = CompletableDeferred<Unit>()
            reportAction = { kind ->
                report.value = RideStatusReportState(kind, RideStatusReportStage.SPEAKING, "PRIVATE_CARRIER")
                withContext(NonCancellable) { late.await() }
                report.value = RideStatusReportState(kind, RideStatusReportStage.COMPLETE, "PRIVATE_CARRIER")
            }
            action(Function.SELECT); state(RidePageStage.STATUS_REPORT)
            calls.value = if (unknownPhone) calls.value.copy(phoneObserved = false)
                else calls.value.copy(notificationUnknown = true)
            assertFalse(pages.statusReportCurrent()); settle(); state(RidePageStage.MENU)
            assertNull(pages.state.value.statusReport); assertTrue(cancelledStatus > 0)
            late.complete(Unit); settle(); state(RidePageStage.MENU)
            assertNull(pages.state.value.statusReport); assertEquals(1, requested.size)
        }
    }

    @Test fun idleWeatherReturnAndAnOlderCancelledCallbackRequireANewExplicitAttempt() = runTest {
        fixture {
            menu()
            val old = CompletableDeferred<Unit>()
            weatherAction = {
                withContext(NonCancellable) { old.await() }
                weather.value = RideWeatherState()
            }
            action(Function.SELECT); state(RidePageStage.WEATHER)
            action(Function.BACK); state(RidePageStage.STATUS_MENU)
            weatherAction = { weather.value = RideWeatherState() }
            action(Function.SELECT); state(RidePageStage.WEATHER)
            assertTrue(pages.state.value.weatherCanRetry); assertFalse(pages.weatherReportCurrent())
            assertEquals(2, weatherRequests)
            val fresh = CompletableDeferred<Unit>()
            weatherAction = {
                weather.value = RideWeatherState(RideWeatherStage.SPEAKING)
                fresh.await(); weather.value = RideWeatherState(RideWeatherStage.COMPLETE)
            }
            action(Function.SELECT); state(RidePageStage.WEATHER)
            assertFalse(pages.state.value.weatherCanRetry); assertTrue(pages.weatherReportCurrent())
            old.complete(Unit); settle(); state(RidePageStage.WEATHER)
            assertTrue(pages.weatherReportCurrent()); assertEquals(3, weatherRequests)
            fresh.complete(Unit); settle(); state(RidePageStage.STATUS_MENU)
            assertEquals(3, weatherRequests)
        }
    }

    @Test fun unavailableBackendNeverPretendsToStartAndUnknownModeNeverAcceptsInput() = runTest {
        fixture(statusEnabled = false) {
            assertFalse(pages.openFunction(MainMenuSelection.Item.WEATHER))
            assertFalse(pages.performAction(Function.SELECT)); assertFalse(pages.statusReportCurrent())
            menu(); action(Function.NEXT); assertFalse(pages.state.value.statusCanReport)
            action(Function.SELECT); state(RidePageStage.STATUS_MENU)
            assertTrue(requested.isEmpty()); assertEquals(0, weatherRequests)
        }
    }

    @Test fun projectionNeverContainsReportDetailOrBackendErrorAndMenuTimeoutDoesNotStartReports() = runTest {
        fixture {
            menu(); autoReturn.value = true
            action(Function.NEXT); action(Function.NEXT); action(Function.NEXT)
            val gate = CompletableDeferred<Unit>()
            reportAction = { kind ->
                report.value = RideStatusReportState(kind, RideStatusReportStage.SPEAKING, "PRIVATE_SSID_PROVIDER_99")
                gate.await(); report.value = RideStatusReportState(kind, RideStatusReportStage.FAILED,
                    detail = "PRIVATE_SSID_PROVIDER_99", error = "PRIVATE_ERROR")
            }
            action(Function.SELECT); state(RidePageStage.STATUS_REPORT)
            scope.advanceTimeBy(20_000); settle(); state(RidePageStage.STATUS_REPORT)
            gate.complete(Unit); settle(); state(RidePageStage.STATUS_REPORT)
            assertTrue(writes.isNotEmpty())
            assertTrue(writes.none { it.toString(Charsets.UTF_8).contains("PRIVATE_") })
            action(Function.BACK); state(RidePageStage.STATUS_MENU)
            scope.advanceTimeBy(15_001); settle(); state(RidePageStage.MENU)
            assertEquals(listOf(RideStatusKind.WIFI), requested); assertNull(pages.state.value.statusReport)
        }
    }
}
