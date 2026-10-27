package org.openridesync.app.weather

import android.app.Application
import android.app.job.JobScheduler
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
import java.io.File
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Real coordinator/persistence; the existing location/Honda port is synthetic. No network/device use. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeWeatherTest {
    private val clock = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(clock)
    private val calls = CoroutineScope(SupervisorJob() + dispatcher)
    private val environment = FakeEnvironment()
    private lateinit var model: HomeWeather
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        app.getSharedPreferences("home-weather", 0).edit().clear().commit()
        HomeWeatherStore(File(app.noBackupFilesDir, "home-weather")).clear()
        model = HomeWeather(app, environment, dispatcher)
        clock.runCurrent()
    }
    @After fun stop() { model.close(); calls.cancel(); clock.runCurrent(); Dispatchers.resetMain() }

    @Test fun homeAutomaticallyReadsAndBackgroundReusesSavedPointWithoutLocation() {
        model.onHomeStart(); clock.runCurrent()
        assertEquals(1, environment.locations); assertEquals(1, environment.reads)
        assertNotNull(model.state.value.current)
        assertEquals(900L, model.state.value.locationAtMillis)
        model.onHomeStop()
        environment.now = 2000
        val background = calls.async { model.runBackground() }; clock.runCurrent()
        assertFalse(background.getCompleted())
        assertEquals(1, environment.locations); assertEquals(2, environment.reads)
        assertEquals(2000L, model.state.value.fetchedAtMillis)
    }

    @Test fun stoppingHomeCancelsLocationButDoesNotCancelAnAlreadyStartedWeatherRead() {
        environment.holdLocation = true
        model.onHomeStart(); clock.runCurrent(); model.onHomeStop()
        environment.locationResult!!.resume(environment.point); clock.runCurrent()
        assertEquals(0, environment.reads)
        environment.holdLocation = false; environment.holdWeather = true
        model.onHomeStart(); clock.runCurrent(); model.onHomeStop()
        environment.weatherResult!!.resume(environment.weather); clock.runCurrent()
        assertNotNull(model.state.value.current)
        assertFalse(model.state.value.loading)
    }

    @Test fun restartLoadsOnlyOwnSuccessfulSnapshotAndFailureRetainsItWithAttemptTime() {
        model.onHomeStart(); clock.runCurrent(); model.onHomeStop(); model.close()
        model = HomeWeather(app, environment, dispatcher); clock.runCurrent()
        assertNotNull(model.state.value.current)
        assertEquals(1, environment.locations); assertEquals(1, environment.reads)
        environment.failure = io.grpc.Status.UNAVAILABLE.asRuntimeException()
        environment.now = 3000
        val retry = calls.async { model.runBackground() }; clock.runCurrent()
        assertTrue(retry.getCompleted())
        assertNotNull(model.state.value.current)
        assertEquals(1000L, model.state.value.fetchedAtMillis)
        assertEquals(3000L, model.state.value.lastAttemptMillis)
        assertEquals("UNAVAILABLE", model.state.value.errorCode)
    }

    @Test fun disableClearsPersistenceAndLateResponseCannotRestoreIt() {
        model.onHomeStart(); clock.runCurrent(); model.onHomeStop()
        environment.holdWeather = true
        val job = calls.async { model.runBackground() }; clock.runCurrent()
        model.setEnabled(false); clock.runCurrent()
        environment.weatherResult!!.resume(environment.weather); clock.runCurrent()
        assertTrue(job.isCancelled)
        assertNull(model.state.value.current)
        assertNull(HomeWeatherStore(File(app.noBackupFilesDir, "home-weather")).read("first"))
        assertNull(app.getSystemService(JobScheduler::class.java).getPendingJob(HomeWeatherJobs.ID))
    }

    @Test fun jobStopCancellationAndAccountReplacementRejectLatePublicationAndDiskWrite() {
        model.onHomeStart(); clock.runCurrent(); model.onHomeStop()
        environment.holdWeather = true; environment.now = 4000
        val stopped = calls.async { model.runBackground() }; clock.runCurrent()
        stopped.cancel()
        environment.weatherResult!!.resume(environment.weather); clock.runCurrent()
        assertEquals(1000L, model.state.value.fetchedAtMillis)
        assertEquals(1000L, HomeWeatherStore(File(app.noBackupFilesDir, "home-weather")).read("first")!!.fetchedAtMillis)
        val replaced = calls.async { model.runBackground() }; clock.runCurrent()
        environment.uid = "second"; environment.revision++
        environment.auth.value = AuthState(AuthStatus.SIGNED_IN, revision = environment.revision)
        clock.runCurrent()
        environment.weatherResult!!.resume(environment.weather); clock.runCurrent()
        assertTrue(replaced.isCancelled); assertNull(model.state.value.current)
        assertNull(HomeWeatherStore(File(app.noBackupFilesDir, "home-weather")).read("first"))
        assertNull(HomeWeatherStore(File(app.noBackupFilesDir, "home-weather")).read("second"))
    }

    @Test fun missingPermissionDoesNotAcquireOrAskForLocationAndJobRegistrationIsStable() {
        environment.locationGranted = false
        model.onHomeStart(); clock.runCurrent()
        assertEquals(0, environment.locations); assertEquals("LOCATION_PERMISSION", model.state.value.errorCode)
        environment.locationGranted = true
        model.onHomeStart(); clock.runCurrent()
        val scheduler = app.getSystemService(JobScheduler::class.java)
        val registered = scheduler.getPendingJob(HomeWeatherJobs.ID)
        assertNotNull(registered)
        HomeWeatherJobs.update(app, true)
        assertSame(registered, scheduler.getPendingJob(HomeWeatherJobs.ID))
    }

    @Test fun aNewLocationAndFailedReadDoNotRelabelThePreviousWeatherPositionTime() {
        model.onHomeStart(); clock.runCurrent(); model.onHomeStop()
        environment.now = 5000
        environment.failure = io.grpc.Status.UNAVAILABLE.asRuntimeException()
        model.onHomeStart(); clock.runCurrent()
        assertNotNull(model.state.value.current)
        assertEquals(1000L, model.state.value.fetchedAtMillis)
        assertEquals(900L, model.state.value.locationAtMillis)
        assertEquals(4900L, model.state.value.savedLocationAtMillis)
        assertEquals("UNAVAILABLE", model.state.value.errorCode)
    }

    @Test fun atomicSnapshotRejectsCancelledReplacementAndUnboundedInput() {
        val directory = File(app.noBackupFilesDir, "home-weather")
        val store = HomeWeatherStore(directory)
        val original = HomeWeatherSnapshot(environment.point, 900, environment.weather, 1000,
            weatherLocationAtMillis = 900)
        store.write("first", original) {}
        var checks = 0
        assertThrows(CancellationException::class.java) {
            store.write("first", original.copy(fetchedAtMillis = 5000)) {
                if (++checks == 2) throw CancellationException()
            }
        }
        assertEquals(1000L, store.read("first")!!.fetchedAtMillis)
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        assertTrue(file.name.matches(Regex("[a-f0-9]{64}\\.json")))
        file.writeBytes(ByteArray(256 * 1024 + 1))
        assertThrows(java.io.IOException::class.java) { store.read("first") }
    }

    @Test fun successfulWeatherReplacesExistingLocationOnlySnapshotAndSurvivesAnotherReplacement() {
        val store = HomeWeatherStore(File(app.noBackupFilesDir, "home-weather"))
        val point = HomeWeatherSnapshot(environment.point, 900)
        store.write("first", point) {}
        assertNull(store.read("first")!!.current)
        val successful = point.copy(current = environment.weather, fetchedAtMillis = 1000,
            weatherLocationAtMillis = 900, lastAttemptMillis = 1000)
        store.write("first", successful) {}
        assertEquals(environment.weather, store.read("first")!!.current)
        assertEquals(1000L, store.read("first")!!.fetchedAtMillis)
        store.write("first", successful.copy(fetchedAtMillis = 2000, lastAttemptMillis = 2000)) {}
        assertEquals(2000L, store.read("first")!!.fetchedAtMillis)
        assertEquals(environment.weather, store.read("first")!!.current)
    }

    private class FakeEnvironment : WeatherEnvironment, WeatherPageReader {
        override val auth = MutableStateFlow(AuthState(AuthStatus.SIGNED_IN))
        override var uid: String? = "first"
        override var revision = 0L
        override var locationGranted = true
        override var unit = TemperatureUnit.CELSIUS
        override val locale = Locale.TAIWAN
        var now = 1000L
        var locations = 0; var reads = 0
        var holdLocation = false; var holdWeather = false
        var failure: Exception? = null
        var locationResult: Continuation<WeatherLocation>? = null
        var weatherResult: Continuation<CurrentWeather>? = null
        val point = WeatherLocation(25.0, 121.0, false, 100)
        val weather = currentWeather(WeatherWire.Weather.newBuilder()
            .setMain(WeatherWire.Main.newBuilder().setTemp(24f))
            .addWeather(WeatherWire.WeatherCondition.newBuilder().setId(800)).build())
        override fun saveUnit(unit: TemperatureUnit) { this.unit = unit }
        override fun nowMillis() = now
        override fun operation() = "synthetic-operation"
        override fun recordCancelled(operation: String) = Unit
        override fun recordFailure(operation: String, code: String) = Unit
        override fun openLocationSettings() = Unit
        override suspend fun location(): WeatherLocation {
            locations++
            return if (holdLocation) suspendCoroutine { locationResult = it } else point
        }
        override suspend fun withReader(uid: String?, revision: Long, operation: String, check: () -> Unit,
            block: suspend (WeatherPageReader) -> Unit) { check(); block(this) }
        override suspend fun current(location: WeatherLocation, locale: Locale, check: () -> Unit): CurrentWeather {
            check(); reads++; failure?.let { throw it }
            return if (holdWeather) suspendCoroutine { weatherResult = it } else weather
        }
        override suspend fun forecast(location: WeatherLocation, locale: Locale, check: () -> Unit): ForecastWeather = error("No forecast in Home")
    }
}
