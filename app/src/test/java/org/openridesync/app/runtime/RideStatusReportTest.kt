package org.openridesync.app.runtime

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic observations exercise ownership and wording, not real platform signal or audible speech. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RideStatusReportTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun onlyExplicitSelectionSamplesAndConcurrentSelectionCannotStartAnotherReport() = runTest(dispatcher) {
        var samples = 0
        var spoken = 0
        val finished = CompletableDeferred<Unit>()
        val report = RideStatusReport(context, null, "synthetic", { true },
            sample = { samples++; RideDeviceStatus(batteryPercent = 0, charging = true) },
            speak = { _, valid -> assertTrue(valid()); spoken++; finished.await() })
        assertEquals(0, samples)
        assertEquals(RideStatusReportState(), report.state.value)
        val job = launch { report.report(RideStatusKind.BATTERY) }
        runCurrent()
        assertEquals(RideStatusReportStage.SPEAKING, report.state.value.stage)
        report.report(RideStatusKind.MOBILE)
        assertEquals(1, samples)
        assertEquals(1, spoken)
        finished.complete(Unit)
        runCurrent(); job.join()
        assertEquals(RideStatusReportStage.COMPLETE, report.state.value.stage)
        assertEquals(RideStatusKind.BATTERY, report.state.value.kind)
        report.cancel()
        assertEquals(RideStatusReportState(), report.state.value)
    }

    @Test fun cancelledOrClosedLoadingCannotPublishLatePrivateObservation() = runTest(dispatcher) {
        for (close in listOf(false, true)) {
            val loaded = CompletableDeferred<Unit>()
            var spoken = false
            var samples = 0
            val report = RideStatusReport(context, null, "synthetic", { true }, sample = {
                samples++
                withContext(NonCancellable) { loaded.await() }
                RideDeviceStatus(wifi = RideSignal(SignalAvailability.READY, 3, "Private synthetic name"))
            }, speak = { _, _ -> spoken = true }, fineLocationGranted = { true })
            val job = launch { report.report(RideStatusKind.WIFI) }
            runCurrent()
            assertEquals(RideStatusReportStage.LOADING, report.state.value.stage)
            if (close) report.close() else report.cancel()
            assertEquals(RideStatusReportState(), report.state.value)
            loaded.complete(Unit)
            runCurrent(); job.join()
            assertFalse(spoken)
            assertEquals(RideStatusReportState(), report.state.value)
            if (close) {
                report.report(RideStatusKind.WIFI)
                assertEquals(1, samples)
            }
        }
    }

    @Test fun cancelledStubbornSampleCannotBlockOrClearImmediateSuccessor() = runTest(dispatcher) {
        val oldLoaded = CompletableDeferred<Unit>()
        val newSpoken = CompletableDeferred<Unit>()
        var samples = 0
        var speechCount = 0
        val report = RideStatusReport(context, null, "synthetic", { true }, sample = {
            samples++
            if (samples == 1) withContext(NonCancellable) { oldLoaded.await() }
            RideDeviceStatus(batteryPercent = 42)
        }, speak = { _, valid ->
            assertTrue(valid())
            speechCount++
            newSpoken.await()
        })
        val oldJob = launch { report.report(RideStatusKind.WIFI) }
        runCurrent()
        report.cancel()
        val newJob = launch { report.report(RideStatusKind.BATTERY) }
        runCurrent()
        assertEquals(2, samples)
        assertEquals(1, speechCount)
        val successor = report.state.value
        assertEquals(RideStatusKind.BATTERY, successor.kind)
        assertEquals(RideStatusReportStage.SPEAKING, successor.stage)
        oldLoaded.complete(Unit)
        runCurrent(); oldJob.join()
        assertEquals(successor, report.state.value)
        report.report(RideStatusKind.MOBILE)
        assertEquals(2, samples) // Old finally must not release the successor's active task.
        newSpoken.complete(Unit)
        runCurrent(); newJob.join()
        assertEquals(successor.copy(stage = RideStatusReportStage.COMPLETE), report.state.value)
        report.close()
    }

    @Test fun unusedStaleWifiNameDoesNotRequireFinePermission() = runTest(dispatcher) {
        var spoken = ""
        val report = RideStatusReport(context, null, "synthetic", { true }, sample = {
            RideDeviceStatus(wifi = RideSignal(SignalAvailability.OFF, 4, "Unused synthetic name"))
        }, speak = { lines, valid -> assertTrue(valid()); spoken = lines.single() },
            fineLocationGranted = { false })
        report.report(RideStatusKind.WIFI)
        assertEquals(RideStatusReportStage.COMPLETE, report.state.value.stage)
        assertFalse(spoken.contains("Unused synthetic name"))
        report.close()
    }

    @Test fun ownerOrNamePermissionLostAfterSamplingCannotSpeak() = runTest(dispatcher) {
        for (permissionLoss in listOf(false, true)) {
            var current = true
            var fine = true
            var spoken = false
            val report = RideStatusReport(context, null, "synthetic", { current }, sample = {
                if (permissionLoss) fine = false else current = false
                RideDeviceStatus(wifi = RideSignal(SignalAvailability.READY, 0, "Synthetic SSID"))
            }, speak = { _, _ -> spoken = true }, fineLocationGranted = { fine })
            val job = launch { report.report(RideStatusKind.WIFI) }
            runCurrent(); job.join()
            assertFalse(spoken)
            assertEquals(RideStatusReportState(), report.state.value)
        }
    }

    @Test fun speechGateRevocationAndLateSpeechCompletionClearPrivateDetail() = runTest(dispatcher) {
        for (interruption in 0..3) {
            var fine = true
            var current = true
            lateinit var allowed: () -> Boolean
            val finished = CompletableDeferred<Unit>()
            val report = RideStatusReport(context, null, "synthetic", { current }, sample = {
                RideDeviceStatus(wifi = RideSignal(SignalAvailability.READY, 2, "Synthetic SSID"))
            }, speak = { _, valid ->
                allowed = valid
                withContext(NonCancellable) { finished.await() }
                if (!valid()) throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
            }, fineLocationGranted = { fine })
            val job = launch { report.report(RideStatusKind.WIFI) }
            runCurrent()
            assertTrue(allowed())
            assertNotNull(report.state.value.detail)
            when (interruption) {
                0 -> fine = false
                1 -> report.cancel()
                2 -> current = false
                else -> report.close()
            }
            assertFalse(allowed())
            finished.complete(Unit)
            runCurrent(); job.join()
            assertEquals(RideStatusReportState(), report.state.value)
        }
    }

    @Test fun deniedNamePermissionStillSpeaksValidWifiStrengthAndTypedSpeechFailureHasNoDetail() = runTest(dispatcher) {
        var spoken = ""
        val report = RideStatusReport(context, null, "synthetic", { true }, sample = {
            RideDeviceStatus(wifi = RideSignal(SignalAvailability.READY, 3,
                nameIssue = SignalNameIssue.PERMISSION_REQUIRED))
        }, speak = { lines, valid ->
            assertTrue(valid()); spoken = lines.single()
            throw RideSpeechFailure(RideSpeechError.UNAVAILABLE)
        }, fineLocationGranted = { false })
        report.report(RideStatusKind.WIFI)
        assertTrue(spoken.contains("3"))
        assertEquals(RideStatusReportState(RideStatusKind.WIFI, RideStatusReportStage.FAILED,
            error = RideSpeechError.UNAVAILABLE.name), report.state.value)
    }

    @Test fun observationsDistinguishZeroUnknownChargingAndSignalAvailabilityWithoutInternetClaims() {
        assertEquals("Phone battery, 0 percent. Charging.", rideStatusReportText(RideStatusKind.BATTERY,
            RideDeviceStatus(0, true), false))
        assertEquals("手機電量，電量未知。", rideStatusReportText(RideStatusKind.BATTERY, RideDeviceStatus(), true))
        val mobile = RideDeviceStatus(mobile = RideSignal(SignalAvailability.READY, 0), internet = true)
        assertEquals("Mobile network, signal observation. signal 0 out of 4. carrier name unavailable.",
            rideStatusReportText(RideStatusKind.MOBILE, mobile, false))
        assertTrue(rideStatusReportText(RideStatusKind.MOBILE,
            mobile.copy(mobile = mobile.mobile.copy(level = null)), false).contains("strength unknown"))
        for ((availability, text) in listOf(SignalAvailability.OFF to "off", SignalAvailability.NO_SIM to "no SIM",
            SignalAvailability.UNSUPPORTED to "unsupported", SignalAvailability.DISCONNECTED to "disconnected",
            SignalAvailability.UNAVAILABLE to "status unavailable")) {
            assertEquals("Mobile network, $text.", rideStatusReportText(RideStatusKind.MOBILE,
                mobile.copy(mobile = RideSignal(availability, 4, "Stale name")), false))
        }
        for ((issue, wording) in listOf(SignalNameIssue.PERMISSION_REQUIRED to "名稱未授權",
            SignalNameIssue.LOCATION_OFF to "定位已關閉", SignalNameIssue.UNAVAILABLE to "名稱無法取得")) {
            val text = rideStatusReportText(RideStatusKind.WIFI,
                RideDeviceStatus(wifi = RideSignal(SignalAvailability.READY, 4, nameIssue = issue)), true)
            assertTrue(text.contains("訊號 4 格"))
            assertTrue(text.contains(wording))
        }
    }

    @Test fun sourceNameIsPlainBoundedUnicodeAndNeverSplitsSupplementaryCharacters() {
        val emoji = "\uD83D\uDEB2"
        assertEquals(emoji.repeat(64), statusReportName("  ${emoji.repeat(80)}  "))
        for (name in listOf("unsafe\nname", "unsafe\u202ename", "unsafe\uD800", "unsafe\uDB40\uDC01"))
            assertNull(statusReportName(name))
        assertEquals("a\"b", statusReportName(" a\"b "))
        val text = rideStatusReportText(RideStatusKind.MOBILE,
            RideDeviceStatus(mobile = RideSignal(SignalAvailability.READY, 4, emoji.repeat(100))), false)
        assertTrue(text.length < 256)
        assertTrue(text.contains(emoji.repeat(64)))
        assertFalse(text.contains(emoji.repeat(65)))
    }
}
