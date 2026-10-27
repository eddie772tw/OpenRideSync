package org.openridesync.app.weather

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.auth.AuthState
import org.openridesync.app.auth.AuthStatus
import org.openridesync.honda.wire.WeatherWire
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Production report ownership through existing fake platform/Honda ports; no network or audible proof. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RideWeatherLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    @Before fun setup() { Dispatchers.setMain(dispatcher); context = RuntimeEnvironment.getApplication() }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun explicitReportUsesCapturedIdentityLocaleUnitAndSequentialDataBeforeSpeechCompletion() = runTest(dispatcher) {
        val environment = FakeEnvironment()
        val done = CompletableDeferred<Unit>()
        val reporter = RideWeather(context, null, "synthetic-operation", environment,
            speech = { forecast, unit, valid ->
                assertTrue(valid()); assertEquals(environment.forecastValue, forecast)
                assertEquals(TemperatureUnit.FAHRENHEIT, unit)
                environment.events += "speech"
                done.await()
            }, isCurrent = { true })
        assertTrue(environment.events.isEmpty())
        val job = launch { reporter.report() }
        runCurrent()
        assertEquals(listOf("location", "reader", "current", "forecast", "speech"), environment.events)
        assertEquals(RideWeatherStage.SPEAKING, reporter.state.value.stage)
        reporter.report()
        assertEquals(1, environment.locations)
        done.complete(Unit); runCurrent(); job.join()
        assertEquals(RideWeatherStage.COMPLETE, reporter.state.value.stage)
        assertEquals(environment.currentValue, reporter.state.value.current)
        reporter.close()
        assertEquals(RideWeatherState(), reporter.state.value)
        reporter.report()
        assertEquals(1, environment.locations)
    }

    @Test fun cancelledStubbornLocationOrCurrentCannotBlockOrOverwriteImmediateSuccessor() = runTest(dispatcher) {
        for (duringCurrent in listOf(false, true)) for (lateError in listOf(false, true)) {
            val environment = FakeEnvironment()
            val oldFinished = CompletableDeferred<Unit>()
            val newFinished = CompletableDeferred<Unit>()
            val hold: suspend () -> Unit = {
                val first = if (duringCurrent) environment.currents == 1 else environment.locations == 1
                if (first) withContext(NonCancellable) {
                    oldFinished.await()
                    if (lateError) throw WeatherDataException()
                }
            }
            if (duringCurrent) environment.onCurrent = hold else environment.onLocation = hold
            var spoken = 0
            val reporter = RideWeather(context, null, "synthetic-operation", environment,
                speech = { _, _, valid -> assertTrue(valid()); spoken++; newFinished.await() }, isCurrent = { true })
            val oldJob = launch { reporter.report() }
            runCurrent()
            reporter.cancel()
            assertEquals(RideWeatherState(), reporter.state.value)
            val newJob = launch { reporter.report() }
            runCurrent()
            val successor = reporter.state.value
            assertEquals(RideWeatherStage.SPEAKING, successor.stage)
            assertEquals(1, spoken)
            assertEquals(2, environment.locations)
            oldFinished.complete(Unit); runCurrent(); oldJob.join()
            assertEquals(successor, reporter.state.value)
            reporter.report() // Old finally must not release the active successor.
            assertEquals(2, environment.locations)
            newFinished.complete(Unit); runCurrent(); newJob.join()
            assertEquals(successor.copy(stage = RideWeatherStage.COMPLETE), reporter.state.value)
            reporter.close()
        }
    }

    @Test fun permissionAndIdentityChangesAfterEachSourcePreventFurtherReadsAndSpeech() = runTest(dispatcher) {
        for (source in 0..2) for (change in 0..2) {
            val environment = FakeEnvironment()
            val invalidate: suspend () -> Unit = {
                when (change) {
                    0 -> environment.locationGranted = false
                    1 -> environment.uid = "replacement"
                    else -> environment.revision++
                }
            }
            when (source) {
                0 -> environment.onLocation = invalidate
                1 -> environment.onCurrent = invalidate
                else -> environment.onForecast = invalidate
            }
            var spoken = false
            val reporter = RideWeather(context, null, "synthetic-operation", environment,
                speech = { _, _, _ -> spoken = true }, isCurrent = { true })
            val job = launch { reporter.report() }
            runCurrent(); job.join()
            assertFalse(spoken)
            assertEquals(if (source > 0) 1 else 0, environment.currents)
            assertEquals(if (source == 2) 1 else 0, environment.forecasts)
            assertNull(reporter.state.value.current)
            assertEquals(if (change == 0) "LOCATION_PERMISSION" else null, reporter.state.value.error)
            assertEquals(if (change == 0) RideWeatherStage.FAILED else RideWeatherStage.IDLE, reporter.state.value.stage)
            reporter.close()
        }
    }

    @Test fun ownerPermissionAndSignedOutAuthInvalidateSpeechGateAndLateCompletion() = runTest(dispatcher) {
        for (change in 0..3) {
            val environment = FakeEnvironment()
            val done = CompletableDeferred<Unit>()
            var current = true
            lateinit var valid: () -> Boolean
            val reporter = RideWeather(context, null, "synthetic-operation", environment,
                speech = { _, _, check -> valid = check; withContext(NonCancellable) { done.await() } },
                isCurrent = { current })
            val job = launch { reporter.report() }
            runCurrent()
            assertTrue(valid())
            when (change) {
                0 -> current = false
                1 -> environment.locationGranted = false
                2 -> environment.auth.value = AuthState(AuthStatus.SIGNED_OUT)
                else -> reporter.close()
            }
            assertFalse(valid())
            done.complete(Unit); runCurrent(); job.join()
            assertNotEquals(RideWeatherStage.COMPLETE, reporter.state.value.stage)
            if (change != 1) assertEquals(RideWeatherState(), reporter.state.value)
            reporter.close()
        }
    }

    @Test fun deniedPermissionDoesNotAcquireLocationAndForecastFailureRetainsTypedError() = runTest(dispatcher) {
        val environment = FakeEnvironment()
        environment.locationGranted = false
        val reporter = RideWeather(context, null, "synthetic-operation", environment,
            speech = { _, _, _ -> fail("Failed reads must not speak") }, isCurrent = { true })
        reporter.report()
        assertEquals(0, environment.locations)
        assertEquals("LOCATION_PERMISSION", reporter.state.value.error)
        environment.locationGranted = true
        environment.onForecast = { throw WeatherDataException() }
        reporter.report()
        assertEquals("DATA_LOSS", reporter.state.value.error)
        reporter.close()
    }

    private class FakeEnvironment : WeatherEnvironment, WeatherPageReader {
        override val auth = MutableStateFlow(AuthState(AuthStatus.SIGNED_IN))
        override var uid: String? = "synthetic"
        override var revision = 7L
        override var locationGranted = true
        override val unit = TemperatureUnit.FAHRENHEIT
        override val locale = Locale.TRADITIONAL_CHINESE
        val fix = WeatherLocation(0.0, 0.0, false, 0)
        val events = mutableListOf<String>()
        var locations = 0; var currents = 0; var forecasts = 0
        var onLocation: suspend () -> Unit = {}
        var onCurrent: suspend () -> Unit = {}
        var onForecast: suspend () -> Unit = {}
        val currentValue = currentWeather(WeatherWire.Weather.newBuilder()
            .setMain(WeatherWire.Main.newBuilder().setTemp(25f))
            .addWeather(WeatherWire.WeatherCondition.newBuilder().setId(800)).setName("Synthetic").build())
        val forecastValue = ForecastWeather(WeatherWire.WeatherForecast.getDefaultInstance(), emptyList())
        override fun saveUnit(unit: TemperatureUnit) = Unit
        override fun nowMillis() = 0L
        override fun operation() = "synthetic-operation"
        override fun recordCancelled(operation: String) = Unit
        override fun recordFailure(operation: String, code: String) = Unit
        override fun openLocationSettings() = Unit
        override suspend fun location(): WeatherLocation { events += "location"; locations++; onLocation(); return fix }
        override suspend fun withReader(uid: String?, revision: Long, operation: String, check: () -> Unit,
            block: suspend (WeatherPageReader) -> Unit) {
            assertEquals("synthetic", uid); assertEquals(7L, revision); assertEquals("synthetic-operation", operation)
            check(); events += "reader"; block(this)
        }
        override suspend fun current(location: WeatherLocation, locale: Locale, check: () -> Unit): CurrentWeather {
            assertEquals(fix, location); assertEquals(this.locale, locale)
            check(); events += "current"; currents++; onCurrent(); return currentValue
        }
        override suspend fun forecast(location: WeatherLocation, locale: Locale, check: () -> Unit): ForecastWeather {
            assertEquals(fix, location); assertEquals(this.locale, locale)
            check(); events += "forecast"; forecasts++; onForecast(); return forecastValue
        }
    }
}
