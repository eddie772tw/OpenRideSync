package org.openridesync.app.weather

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Executes the production ViewModel + lifecycle binding, with fake external location/Honda ports. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeatherViewModelLifecycleTest {
    private val scheduler = TestCoroutineScheduler()
    private val environment = FakeEnvironment()
    private lateinit var model: WeatherViewModel
    private var store = ViewModelStore()
    private var owner = Owner()
    private var dispose: () -> Unit = {}

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun event(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
    }
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        createModel()
    }
    private fun createModel() {
        model = WeatherViewModel(RuntimeEnvironment.getApplication(), environment)
        store.put("weather", model)
        scheduler.runCurrent()
        dispose = bindWeatherLifecycle(owner.lifecycle, model)
        owner.event(Lifecycle.Event.ON_CREATE)
        owner.event(Lifecycle.Event.ON_START)
        owner.event(Lifecycle.Event.ON_RESUME)
        scheduler.runCurrent()
    }
    @After fun tearDown() { dispose(); store.clear(); scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun entryPermissionAndForegroundChangesDoNotRead() {
        assertEquals(0, environment.locations)
        environment.locationGranted = false
        model.refreshAccess(); model.refresh(); scheduler.runCurrent()
        assertEquals("LOCATION_PERMISSION", model.state.value.errorCode)
        environment.locationGranted = true
        owner.event(Lifecycle.Event.ON_PAUSE); owner.event(Lifecycle.Event.ON_STOP)
        owner.event(Lifecycle.Event.ON_START); owner.event(Lifecycle.Event.ON_RESUME)
        scheduler.runCurrent()
        assertEquals(0, environment.locations); assertEquals(0, environment.currents)
        assertFalse(model.state.value.loading)
    }
    @Test fun explicitReadRunsOneCurrentAndForecastAndUsesClock() {
        model.refresh(); model.refresh(); scheduler.runCurrent()
        assertEquals(1, environment.locations); assertEquals(1, environment.currents); assertEquals(1, environment.forecasts)
        assertNotNull(model.state.value.current); assertNotNull(model.state.value.forecast)
        assertEquals(1234L, model.state.value.fetchedAtMillis); assertFalse(model.state.value.loading)
    }
    @Test fun cancelBeforeLateLocationPreventsRemoteRead() {
        environment.holdLocation = true
        model.refresh(); scheduler.runCurrent(); model.cancel()
        environment.locationContinuation!!.resume(environment.fix); scheduler.runCurrent()
        assertEquals(0, environment.currents); assertNull(model.state.value.current)
        assertEquals("CANCELLED", model.state.value.errorCode)
    }
    @Test fun stoppedOwnerRejectsLateForecastWithoutLosingCompletedCurrent() {
        environment.holdForecast = true
        model.refresh(); scheduler.runCurrent()
        assertNotNull(model.state.value.current)
        owner.event(Lifecycle.Event.ON_PAUSE); owner.event(Lifecycle.Event.ON_STOP)
        environment.forecastContinuation!!.resume(environment.forecastValue); scheduler.runCurrent()
        assertNull(model.state.value.forecast); assertNotNull(model.state.value.current)
        assertFalse(model.state.value.observing); assertFalse(model.state.value.loading)
        owner.event(Lifecycle.Event.ON_START); owner.event(Lifecycle.Event.ON_RESUME); scheduler.runCurrent()
        assertEquals(1, environment.locations)
    }
    @Test fun accountReplacementDropsOldPublicationAndDoesNotRestart() {
        environment.holdLocation = true
        model.refresh(); scheduler.runCurrent()
        environment.replaceIdentity("second", AuthStatus.SIGNED_IN); scheduler.runCurrent()
        environment.locationContinuation!!.resume(environment.fix); scheduler.runCurrent()
        assertNull(model.state.value.current); assertEquals(0, environment.currents)
        assertEquals(1, environment.locations); assertTrue(model.state.value.signedIn)
    }
    @Test fun signOutInvalidatesCapturedTokenRetryGuard() {
        environment.holdForecast = true
        model.refresh(); scheduler.runCurrent()
        val oldCheck = environment.lastCheck!!
        environment.replaceIdentity(null, AuthStatus.SIGNED_OUT); scheduler.runCurrent()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { oldCheck() }
        environment.forecastContinuation!!.resume(environment.forecastValue); scheduler.runCurrent()
        assertNull(model.state.value.current); assertNull(model.state.value.forecast); assertFalse(model.state.value.signedIn)
    }
    @Test fun permissionLossInvalidatesOngoingReadAndTimer() {
        environment.holdForecast = true
        model.refresh(); scheduler.runCurrent()
        environment.locationGranted = false; model.refreshAccess()
        environment.forecastContinuation!!.resume(environment.forecastValue); scheduler.runCurrent()
        scheduler.advanceTimeBy(3_600_000); scheduler.runCurrent()
        assertEquals(1, environment.locations); assertNull(model.state.value.current)
        assertFalse(model.state.value.locationGranted); assertFalse(model.state.value.loading)
    }
    @Test fun visibleTimerRefreshesOnceButStopAndRecreateDoNotReplayIt() {
        model.refresh(); scheduler.runCurrent()
        scheduler.advanceTimeBy(3_600_000); scheduler.runCurrent()
        assertEquals(2, environment.locations)
        model.setUnit(TemperatureUnit.FAHRENHEIT)
        dispose(); store.clear(); scheduler.runCurrent()
        store = ViewModelStore(); owner = Owner(); createModel()
        assertEquals(TemperatureUnit.FAHRENHEIT, model.state.value.unit)
        assertNull(model.state.value.current); assertEquals(2, environment.locations)
        scheduler.advanceTimeBy(3_600_000); scheduler.runCurrent()
        assertEquals(2, environment.locations)
    }
    @Test fun forecastFailureKeepsCurrentAndExplicitRetryRecovers() {
        environment.failForecast = true
        model.refresh(); scheduler.runCurrent()
        assertNotNull(model.state.value.current); assertNull(model.state.value.forecast)
        assertEquals("DATA_LOSS", model.state.value.forecastErrorCode)
        environment.failForecast = false; model.refresh(); scheduler.runCurrent()
        assertNotNull(model.state.value.forecast); assertNull(model.state.value.forecastErrorCode)
    }

    @Test fun clearedStoreRejectsLateLocationAfterReplacementModelIsCreated() {
        environment.holdLocation = true
        model.refresh(); scheduler.runCurrent()
        val late = environment.locationContinuation!!
        store.clear(); scheduler.runCurrent()
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.observing)
        dispose()
        store = ViewModelStore(); owner = Owner(); createModel()
        late.resume(environment.fix); scheduler.runCurrent()
        assertEquals(1, environment.locations); assertEquals(0, environment.currents)
        assertNull(model.state.value.current); assertFalse(model.state.value.loading)
    }
    @Test fun serviceAuthFailureStopsTimerWithoutPretendingFirebaseSignedOut() {
        environment.currentFailure = io.grpc.Status.UNAUTHENTICATED.asRuntimeException()
        model.refresh(); scheduler.runCurrent()
        assertEquals("UNAUTHENTICATED", model.state.value.errorCode)
        assertTrue(model.state.value.signedIn); assertNull(model.state.value.current)
        scheduler.advanceTimeBy(3_600_000); scheduler.runCurrent()
        assertEquals(1, environment.locations); assertEquals(0, environment.forecasts)
    }

    private class FakeEnvironment : WeatherEnvironment, WeatherPageReader {
        override val auth = MutableStateFlow(AuthState(AuthStatus.SIGNED_IN))
        override var uid: String? = "synthetic"
        override var revision = 0L
        override var locationGranted = true
        override var unit = TemperatureUnit.CELSIUS
        override val locale = Locale.US
        val fix = WeatherLocation(0.0, 0.0, false, 0)
        var locations = 0; var currents = 0; var forecasts = 0
        var currentFailure: Exception? = null
        var holdLocation = false; var holdForecast = false; var failForecast = false
        var locationContinuation: Continuation<WeatherLocation>? = null
        var forecastContinuation: Continuation<ForecastWeather>? = null
        var lastCheck: (() -> Unit)? = null
        private val raw = WeatherWire.Weather.newBuilder().setMain(WeatherWire.Main.newBuilder().setTemp(25f))
            .addWeather(WeatherWire.WeatherCondition.newBuilder().setId(800)).setName("Synthetic").build()
        private val currentValue = currentWeather(raw)
        val forecastValue = ForecastWeather(WeatherWire.WeatherForecast.getDefaultInstance(), emptyList())
        fun replaceIdentity(value: String?, status: AuthStatus) { uid = value; revision++; auth.value = AuthState(status, revision = revision) }
        override fun saveUnit(unit: TemperatureUnit) { this.unit = unit }
        override fun nowMillis() = 1234L
        override fun operation() = "synthetic-operation"
        override fun recordCancelled(operation: String) = Unit
        override fun recordFailure(operation: String, code: String) = Unit
        override fun openLocationSettings() = Unit
        override suspend fun location(): WeatherLocation { locations++; return if (holdLocation) suspendCoroutine { locationContinuation = it } else fix }
        override suspend fun withReader(uid: String?, revision: Long, operation: String, check: () -> Unit, block: suspend (WeatherPageReader) -> Unit) { check(); block(this) }
        override suspend fun current(location: WeatherLocation, locale: Locale, check: () -> Unit): CurrentWeather {
            lastCheck = check; check(); currents++; currentFailure?.let { throw it }; return currentValue
        }
        override suspend fun forecast(location: WeatherLocation, locale: Locale, check: () -> Unit): ForecastWeather {
            lastCheck = check; check(); forecasts++
            if (failForecast) throw WeatherDataException()
            return if (holdForecast) suspendCoroutine { forecastContinuation = it } else forecastValue
        }
    }
}
