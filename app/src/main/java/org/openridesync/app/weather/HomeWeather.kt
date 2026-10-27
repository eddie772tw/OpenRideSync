package org.openridesync.app.weather

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.honda.HondaSessionRead
import java.io.File
import java.io.IOException

internal data class HomeWeatherState(
    val signedIn: Boolean = false,
    val locationGranted: Boolean = false,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val current: CurrentWeather? = null,
    val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val fetchedAtMillis: Long? = null,
    val locationAtMillis: Long? = null,
    val lastKnownLocation: Boolean = false,
    val savedLocationAtMillis: Long? = null,
    val errorCode: String? = null,
    val lastAttemptMillis: Long? = null,
    val storageError: Boolean = false,
)

/** App lifetime; background work reads a saved point and never asks LocationManager for a fix. */
internal class HomeWeather(
    context: Context,
    private val environment: WeatherEnvironment = AndroidWeatherEnvironment(context as OpenRideSyncApplication),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("home-weather", Context.MODE_PRIVATE)
    private val store = HomeWeatherStore(File(context.noBackupFilesDir, "home-weather"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val disk = Mutex()
    private val requests = Mutex()
    private val mutableState = MutableStateFlow(HomeWeatherState(enabled = preferences.getBoolean("enabled", true)))
    val state = mutableState.asStateFlow()
    private var visible = false
    @Volatile private var generation = 0L
    private var ownerUid: String? = null
    private var ownerRevision = Long.MIN_VALUE
    private var snapshot: HomeWeatherSnapshot? = null
    private var identityJob: Job? = null
    private var locationJob: Job? = null
    private var foregroundRead: Job? = null
    private val backgroundReads = mutableSetOf<Job>()
    private var scheduleFailed = false

    init {
        scope.launch {
            environment.auth.collect { synchronizeIdentity() }
        }
    }

    private fun signedIn() = environment.auth.value.status == AuthStatus.SIGNED_IN && environment.uid != null
    private fun valid(uid: String, revision: Long, ticket: Long) = generation == ticket &&
        state.value.enabled && signedIn() && environment.uid == uid && environment.revision == revision

    private fun synchronizeIdentity() {
        val uid = environment.uid.takeIf { signedIn() }
        val revision = environment.revision
        if (ownerUid == uid && ownerRevision == revision) return
        val first = ownerRevision == Long.MIN_VALUE
        ownerUid = uid; ownerRevision = revision
        generation++
        locationJob?.cancel(); foregroundRead?.cancel(); identityJob?.cancel()
        backgroundReads.toList().forEach { it.cancel() }
        snapshot = null
        mutableState.value = HomeWeatherState(signedIn = uid != null, locationGranted = environment.locationGranted,
            enabled = preferences.getBoolean("enabled", true), unit = environment.unit)
        schedule(uid != null && state.value.enabled && environment.locationGranted)
        val ticket = generation
        identityJob = scope.launch {
            try {
                val saved = withContext(io) { disk.withLock {
                    // Process restoration may reuse only the current UID's private file.
                    // Any live identity transition clears the previous account's location/weather.
                    if (!first || uid == null || !state.value.enabled) store.clear()
                    if (uid != null && state.value.enabled) store.read(uid) else null
                } }
                if (generation != ticket) return@launch
                snapshot = saved
                publish()
            } catch (_: IOException) {
                if (generation == ticket) mutableState.value = state.value.copy(storageError = true)
            }
            if (generation == ticket && visible) startLocation()
        }
    }

    fun onHomeStart() {
        visible = true
        synchronizeIdentity()
        mutableState.value = state.value.copy(unit = environment.unit, locationGranted = environment.locationGranted)
        schedule(signedIn() && state.value.enabled && environment.locationGranted)
        if (identityJob?.isActive != true) startLocation()
    }

    fun onHomeStop() {
        visible = false
        locationJob?.cancel(); locationJob = null
        if (foregroundRead?.isActive != true && backgroundReads.none { it.isActive })
            mutableState.value = state.value.copy(loading = false)
    }

    fun setEnabled(enabled: Boolean) {
        if (state.value.enabled == enabled) return
        generation++
        locationJob?.cancel(); foregroundRead?.cancel(); identityJob?.cancel()
        backgroundReads.toList().forEach { it.cancel() }
        preferences.edit().putBoolean("enabled", enabled).apply()
        snapshot = null
        mutableState.value = HomeWeatherState(signedIn = signedIn(), enabled = enabled,
            locationGranted = environment.locationGranted, unit = environment.unit)
        schedule(enabled && signedIn() && environment.locationGranted)
        val ticket = generation
        identityJob = scope.launch {
            try { withContext(io) { disk.withLock { store.clear() } } }
            catch (_: IOException) { if (ticket == generation) mutableState.value = state.value.copy(storageError = true) }
            if (ticket == generation && enabled && visible) startLocation()
        }
    }

    private fun startLocation() {
        if (!visible || !state.value.enabled || !signedIn() || locationJob?.isActive == true || foregroundRead?.isActive == true) return
        if (!environment.locationGranted) {
            mutableState.value = state.value.copy(locationGranted = false, loading = false, errorCode = "LOCATION_PERMISSION")
            return
        }
        val uid = environment.uid ?: return
        val revision = environment.revision
        val ticket = generation
        val operation = environment.operation()
        locationJob = scope.launch(start = CoroutineStart.LAZY) {
            val requestJob = currentCoroutineContext().job
            mutableState.value = state.value.copy(loading = true, errorCode = null, lastAttemptMillis = environment.nowMillis())
            try {
                val location = environment.location()
                ensureActive()
                if (!visible || !valid(uid, revision, ticket)) return@launch
                checkPermission()
                val acquiredAt = (environment.nowMillis() - (location.ageMillis ?: 0).coerceAtLeast(0)).coerceAtLeast(0)
                requests.withLock {
                    ensureActive()
                    val next = (snapshot ?: HomeWeatherSnapshot(location, acquiredAt)).copy(location = location, locationAtMillis = acquiredAt)
                    persist(uid, revision, ticket, next)
                    snapshot = next; publish()
                }
                // This separate job survives Home ON_STOP; only foreground location acquisition stops.
                foregroundRead = scope.launch(start = CoroutineStart.LAZY) { fetch(uid, revision, ticket, operation) }
                foregroundRead?.start()
            } catch (_: CancellationException) { environment.recordCancelled(operation) }
            catch (error: Exception) { fail(error, uid, revision, ticket, operation) }
            finally {
                if (locationJob === requestJob && valid(uid, revision, ticket) && foregroundRead?.isActive != true && backgroundReads.none { it.isActive })
                    mutableState.value = state.value.copy(loading = false)
            }
        }
        locationJob?.start()
    }

    /** Called by JobService. Cancellation belongs to that job, not to the foreground request. */
    suspend fun runBackground(): Boolean = withContext(Dispatchers.Main.immediate) {
        synchronizeIdentity()
        identityJob?.join()
        val uid = environment.uid ?: return@withContext false
        val revision = environment.revision
        val ticket = generation
        mutableState.value = state.value.copy(locationGranted = environment.locationGranted, unit = environment.unit)
        if (!valid(uid, revision, ticket) || !environment.locationGranted || snapshot == null) return@withContext false
        val job = currentCoroutineContext().job
        backgroundReads += job
        try { fetch(uid, revision, ticket, environment.operation()) } finally { backgroundReads -= job }
    }

    private suspend fun fetch(uid: String, revision: Long, ticket: Long, operation: String): Boolean = requests.withLock {
        if (!valid(uid, revision, ticket)) return@withLock false
        val currentJob = currentCoroutineContext()
        fun check() { currentJob.ensureActive(); if (!valid(uid, revision, ticket)) throw CancellationException(); checkPermission() }
        var source = snapshot ?: return@withLock false
        val attempt = environment.nowMillis()
        mutableState.value = state.value.copy(loading = true, lastAttemptMillis = attempt)
        try {
            check()
            var response: CurrentWeather? = null
            environment.withReader(uid, revision, operation, ::check) { reader ->
                response = reader.current(source.location, environment.locale, ::check)
            }
            check()
            source = source.copy(current = response ?: throw WeatherDataException(), fetchedAtMillis = environment.nowMillis(), lastAttemptMillis = attempt,
                weatherLocationAtMillis = source.locationAtMillis, weatherLastKnown = source.location.lastKnown, errorCode = null)
            persist(uid, revision, ticket, source)
            check()
            snapshot = source; publish()
            false
        } catch (error: CancellationException) { environment.recordCancelled(operation); throw error }
        catch (error: Exception) {
            fail(error, uid, revision, ticket, operation)
            if (valid(uid, revision, ticket) && environment.locationGranted) {
                val retained = snapshot?.copy(lastAttemptMillis = attempt, errorCode = state.value.errorCode)
                if (retained != null) try {
                    persist(uid, revision, ticket, retained)
                    snapshot = retained
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.value = state.value.copy(storageError = true) }
            }
            state.value.errorCode in setOf("NETWORK", "UNAVAILABLE", "TIMEOUT", "DEADLINE_EXCEEDED")
        } finally {
            if (valid(uid, revision, ticket)) mutableState.value = state.value.copy(loading = false)
        }
    }

    private suspend fun persist(uid: String, revision: Long, ticket: Long, value: HomeWeatherSnapshot) {
        val job = currentCoroutineContext()
        try { withContext(io) { disk.withLock {
            store.write(uid, value) {
                job.ensureActive()
                if (!valid(uid, revision, ticket)) throw CancellationException()
                checkPermission()
            }
        } } } catch (error: IOException) { throw HomeWeatherStorageException(error) }
    }
    private fun schedule(enabled: Boolean) {
        scheduleFailed = !HomeWeatherJobs.update(context, enabled)
        if (scheduleFailed) mutableState.value = state.value.copy(errorCode = "JOB_UNAVAILABLE")
    }
    private fun checkPermission() { if (!environment.locationGranted) throw WeatherLocationException("LOCATION_PERMISSION") }
    private fun publish() {
        mutableState.value = state.value.copy(signedIn = signedIn(), locationGranted = environment.locationGranted,
            current = snapshot?.current, fetchedAtMillis = snapshot?.fetchedAtMillis,
            locationAtMillis = snapshot?.weatherLocationAtMillis ?: snapshot?.locationAtMillis,
            savedLocationAtMillis = snapshot?.locationAtMillis,
            lastKnownLocation = snapshot?.weatherLastKnown ?: false,
            lastAttemptMillis = snapshot?.lastAttemptMillis, unit = environment.unit, errorCode = snapshot?.errorCode ?: "JOB_UNAVAILABLE".takeIf { scheduleFailed }, storageError = false)
    }
    private fun fail(error: Exception, uid: String, revision: Long, ticket: Long, operation: String) {
        val code = when (error) {
            is WeatherLocationException -> error.code
            is WeatherDataException -> "DATA_LOSS"
            is HomeWeatherStorageException -> "IO"
            else -> HondaSessionRead.errorCode(error)
        }
        environment.recordFailure(operation, code)
        if (valid(uid, revision, ticket)) mutableState.value = state.value.copy(errorCode = code,
            storageError = code == "IO", locationGranted = environment.locationGranted)
    }
    override fun close() { generation++; backgroundReads.toList().forEach { it.cancel() }; scope.cancel() }
}

private class HomeWeatherStorageException(cause: IOException) : IOException(cause)
