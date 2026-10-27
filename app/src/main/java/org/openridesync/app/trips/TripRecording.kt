package org.openridesync.app.trips

import android.content.Context
import android.location.Location
import androidx.core.location.LocationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openridesync.app.BuildConfig
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.honda.wire.PipelineWire
import java.security.MessageDigest
import java.util.UUID

internal data class TripRecordingState(val signedIn: Boolean = false, val enabled: Boolean = false,
    val recording: Boolean = false, val pending: Int = 0, val uploading: Boolean = false,
    val ambiguous: Boolean = false, val errorCode: String? = null,
    val backgroundScheduleFailed: Boolean = false)

/** App-owned recording consent, real-attempt capture and one account's durable upload queue.
 * Neither preview nor opening Settings starts location or network work. No route enters diagnostics. */
internal class TripRecording(context: Context,
    private val environment: TripRecordingEnvironment = AndroidTripRecordingEnvironment(context as OpenRideSyncApplication),
    private val journal: DiagnosticJournal? = (context as? OpenRideSyncApplication)?.journal,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("trip-recording", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val disk = Mutex()
    private val changes = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(TripRecordingState())
    val state = mutableState.asStateFlow()
    private var uploadJob: Job? = null
    private var uploadGeneration = 0L
    private var refreshJob: Job? = null
    private var ownerUid: String? = null
    private var ownerRevision = Long.MIN_VALUE
    private var closed = false
    private val uncertainMarkers = mutableSetOf<String>()

    init { scope.launch { environment.auth.collect { synchronizeIdentity() } } }

    private fun key(uid: String, field: String): String = MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } + ":" + field
    private fun enabled(uid: String) = preferences.all[key(uid, "enabled")] as? Boolean ?: false
    private fun marker(uid: String): String? {
        if (uid in uncertainMarkers) return "UNCONFIRMED_STORAGE"
        val entry = key(uid, "in-flight")
        if (!preferences.contains(entry)) return null
        val raw = preferences.all[entry] as? String ?: return "INVALID_MARKER"
        return raw.takeIf { value -> value.length <= 3_700 && value.split(',').let { ids ->
            ids.size in 1..100 && ids.all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        } } ?: "INVALID_MARKER"
    }
    private fun saveMarker(uid: String, value: String?): Boolean {
        val entry = key(uid, "in-flight")
        val old = preferences.all[entry]
        val editor = preferences.edit()
        if (value == null) editor.remove(entry) else editor.putString(entry, value)
        if (editor.commit()) { uncertainMarkers.remove(uid); return true }
        // commit(false) may still alter Android's in-memory map. Preserve the raw old value and
        // block automatic dispatch even if the attempted rollback also cannot reach disk.
        uncertainMarkers.add(uid)
        val rollback = preferences.edit()
        when (old) {
            null -> rollback.remove(entry)
            is String -> rollback.putString(entry, old)
            is Boolean -> rollback.putBoolean(entry, old)
            is Int -> rollback.putInt(entry, old)
            is Long -> rollback.putLong(entry, old)
            is Float -> rollback.putFloat(entry, old)
            is Set<*> -> rollback.putStringSet(entry, old.filterIsInstance<String>().toSet())
        }
        rollback.commit()
        return false
    }
    private fun current(uid: String, revision: Long) = !closed && environment.auth.value.status == AuthStatus.SIGNED_IN &&
        environment.uid == uid && environment.revision == revision
    private fun check(uid: String, revision: Long) { if (!current(uid, revision)) throw CancellationException() }

    private fun synchronizeIdentity() {
        val uid = environment.uid.takeIf { environment.auth.value.status == AuthStatus.SIGNED_IN }
        if (uid == ownerUid && ownerRevision == environment.revision) return
        ownerUid = uid; ownerRevision = environment.revision
        uploadJob?.cancel(); refreshJob?.cancel(); changes.value++
        mutableState.value = TripRecordingState(signedIn = uid != null, enabled = uid?.let(::enabled) == true,
            ambiguous = uid?.let(::marker) != null)
        schedule()
        refresh()
    }

    private fun schedule() {
        val uid = ownerUid
        val success = TripUploadJobs.update(context, uid != null && current(uid, ownerRevision) && enabled(uid))
        mutableState.value = state.value.copy(backgroundScheduleFailed = !success)
        if (!success) journal?.record(Component.TRIP, null, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.UNAVAILABLE)
    }

    fun refresh() {
        val uid = ownerUid ?: return
        if (state.value.backgroundScheduleFailed) schedule()
        val revision = ownerRevision
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val count = stored(uid) { it.count() }
                if (current(uid, revision)) mutableState.value = state.value.copy(pending = count, ambiguous = marker(uid) != null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(uid, revision)) mutableState.value = state.value.copy(errorCode = "STORAGE_FAILED") }
        }
    }

    fun setEnabled(value: Boolean): Boolean {
        val uid = ownerUid ?: return false
        if (!current(uid, ownerRevision)) return false
        val previous = enabled(uid)
        if (!preferences.edit().putBoolean(key(uid, "enabled"), value).commit()) {
            preferences.edit().putBoolean(key(uid, "enabled"), previous).commit()
            mutableState.value = state.value.copy(errorCode = "STORAGE_FAILED")
            return false
        }
        if (!value) uploadJob?.cancel()
        mutableState.value = state.value.copy(enabled = value, errorCode = null)
        // Register durable work before emitting the change that permits new samples. A process
        // can disappear after a saved point without reaching recordRide's final upload trigger.
        schedule()
        changes.value++
        return true
    }

    /** Explicit retry after a stopped source; it never creates a vehicle connection. */
    fun retryRecording() { changes.value++ }

    private suspend fun <T> stored(uid: String, action: (TripPointStore) -> T): T = withContext(io) {
        disk.withLock { TripPointStore(context, uid).use(action) }
    }

    /** Called exactly once by an authenticated ride attempt. Mode changes do not split its UUID.
     * Loss of transport pauses samples; reconnect resumes with a new previous-point baseline. */
    suspend fun recordRide(uid: String, revision: Long, attempt: String, series: String, rawSeries: String,
        name: String, connectionChanges: Flow<*>, isConnected: () -> Boolean,
        locationForeground: () -> Boolean, ownsAttempt: () -> Boolean) {
        val trip = UUID.randomUUID().toString()
        var savedAny = false
        val operation = journal?.newOperation() ?: UUID.randomUUID().toString()
        fun owned() = current(uid, revision) && ownsAttempt()
        fun allowed() = owned() && enabled(uid) && isConnected() && locationForeground() &&
            environment.locationGranted && environment.locationEnabled
        fun problem(): String? = when {
            !enabled(uid) -> null
            !environment.locationGranted -> "LOCATION_PERMISSION"
            !environment.locationEnabled -> "LOCATION_DISABLED"
            !locationForeground() -> "FOREGROUND_LOCATION_REQUIRED"
            else -> null
        }
        suspend fun save(location: Location, previous: Location?, final: Boolean = false) {
            val point = tripPoint(location, previous, uid, trip, attempt, series, rawSeries, name, environment.coarse)
            stored(uid) {
                check(uid, revision)
                if (!ownsAttempt() || !enabled(uid) || !locationForeground() || !environment.locationGranted ||
                    !environment.locationEnabled || (!final && !isConnected())) throw CancellationException()
                it.append(point)
            }
            savedAny = true
            val count = stored(uid) { it.count() }
            if (owned()) mutableState.value = state.value.copy(pending = count)
            journal?.record(Component.TRIP, operation, Event.RESPONSE, Outcome.SUCCEEDED, ErrorCode.NONE)
        }
        try {
            combine(changes, connectionChanges, flow { while (true) { emit(Unit); delay(1_000) } }) { _, _, _ ->
                Triple(allowed(), problem(), changes.value)
            }.distinctUntilChanged().collectLatest { (active, error, _) ->
                if (!owned()) throw CancellationException()
                mutableState.value = state.value.copy(recording = false, errorCode = error)
                if (!active) return@collectLatest
                var previous: Location? = null
                try {
                    journal?.record(Component.TRIP, operation, Event.START, Outcome.STARTED, ErrorCode.NONE)
                    mutableState.value = state.value.copy(recording = true)
                    environment.samples().collect { sample ->
                        currentCoroutineContext().ensureActive()
                        if (!allowed()) throw CancellationException()
                        save(sample, previous)
                        previous = Location(sample)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (owned()) mutableState.value = state.value.copy(errorCode = tripRecordingError(error))
                    journal?.record(Component.TRIP, operation, Event.STATE_CHANGE, Outcome.FAILED,
                        tripDiagnosticError(error))
                } finally { if (owned()) mutableState.value = state.value.copy(recording = false) }
            }
        } finally {
            // The parent's finally joins us before releasing the location foreground service.
            // A failed final fix never discards already saved samples or invents an end point.
            withContext(NonCancellable) {
                if (savedAny && owned() && enabled(uid) && locationForeground() && environment.locationGranted && environment.locationEnabled) {
                    try { withTimeout(5_000) { environment.finalLocation()?.let { save(it, null, final = true) } } }
                    catch (error: Exception) {
                        journal?.record(Component.TRIP, operation, Event.RESPONSE, Outcome.FAILED, tripDiagnosticError(error))
                    }
                }
                if (owned()) {
                    mutableState.value = state.value.copy(recording = false)
                    if (savedAny && enabled(uid)) upload(automatic = true)
                }
                journal?.record(Component.TRIP, operation, Event.STOP, Outcome.OBSERVED, ErrorCode.NONE)
            }
        }
    }

    /** Explicit calls may retry a prior ambiguous batch. Automatic work never resends one. */
    fun upload(automatic: Boolean = false, emptyOnly: Boolean = false) {
        startUpload(scope, automatic, emptyOnly)
    }

    /** Only stored rows are read; no location permission, provider or vehicle is needed.
     * The sender is a child of the system job, so stopping it cancels this sender, not a UI sender. */
    suspend fun runBackground() = withContext(Dispatchers.Main.immediate) {
        synchronizeIdentity()
        startUpload(this, automatic = true, emptyOnly = false)?.join()
        Unit
    }

    private fun startUpload(parent: CoroutineScope, automatic: Boolean, emptyOnly: Boolean): Job? {
        val uid = ownerUid ?: return null
        val revision = ownerRevision
        if (!current(uid, revision) || uploadJob?.isCompleted == false || (automatic && !enabled(uid))) return null
        if (automatic && marker(uid) != null) {
            mutableState.value = state.value.copy(ambiguous = true, errorCode = "UPLOAD_UNCONFIRMED")
            return null
        }
        val ticket = ++uploadGeneration
        fun valid() = current(uid, revision) && ticket == uploadGeneration
        val job = parent.launch(start = CoroutineStart.LAZY) {
            val operation = journal?.newOperation() ?: UUID.randomUUID().toString()
            mutableState.value = state.value.copy(uploading = true, errorCode = null)
            try {
                while (true) {
                    check(uid, revision)
                    if (automatic && !enabled(uid)) throw CancellationException()
                    val points = stored(uid) { it.batch() }
                    if (points.isEmpty()) {
                        if (!automatic && marker(uid) != null) {
                            if (!saveMarker(uid, null)) throw TripRecordingFailure("STORAGE_FAILED")
                            check(uid, revision)
                            mutableState.value = state.value.copy(pending = 0, ambiguous = false)
                        }
                        break
                    }
                    if (emptyOnly) {
                        val count = stored(uid) { it.count() }
                        check(uid, revision)
                        mutableState.value = state.value.copy(pending = count, errorCode = "QUEUE_CHANGED")
                        break // The clicked action promised only a local empty-queue recovery.
                    }
                    check(uid, revision)
                    val ids = points.joinToString(",") { it.uuid }
                    // Durable before dispatch. Process death, cancellation or uncertain RPC return
                    // leaves this marker and the exact original UUIDs for explicit user retry.
                    if (!saveMarker(uid, ids)) throw TripRecordingFailure("STORAGE_FAILED")
                    mutableState.value = state.value.copy(ambiguous = true)
                    journal?.record(Component.TRIP, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
                    environment.upload(uid, revision, points, operation) {
                        check(uid, revision)
                        if (automatic && !enabled(uid)) throw CancellationException()
                    }
                    currentCoroutineContext().ensureActive()
                    check(uid, revision)
                    stored(uid) {
                        check(uid, revision)
                        it.acknowledge(points)
                    }
                    if (!saveMarker(uid, null)) throw TripRecordingFailure("STORAGE_FAILED")
                    check(uid, revision)
                    val count = stored(uid) { it.count() }
                    check(uid, revision)
                    if (valid()) mutableState.value = state.value.copy(pending = count, ambiguous = false)
                    journal?.record(Component.TRIP, operation, Event.REQUEST, Outcome.SUCCEEDED, ErrorCode.NONE)
                }
            } catch (cancelled: CancellationException) {
                journal?.record(Component.TRIP, operation, Event.REQUEST, Outcome.CANCELLED, ErrorCode.CANCELLED)
                throw cancelled
            }
            catch (error: Exception) {
                if (valid()) mutableState.value = state.value.copy(errorCode = tripRecordingError(error), ambiguous = marker(uid) != null)
                journal?.record(Component.TRIP, operation, Event.REQUEST, Outcome.FAILED,
                    tripDiagnosticError(error))
            } finally {
                if (valid()) mutableState.value = state.value.copy(uploading = false, ambiguous = marker(uid) != null)
            }
        }
        uploadJob = job
        job.start()
        return job
    }

    fun cancelUpload() { uploadJob?.cancel() }
    override fun close() { closed = true; uploadJob?.cancel(); scope.cancel() }
}

internal fun tripRecordingError(error: Exception): String = when (error) {
    is TripRecordingFailure -> error.code
    is SecurityException -> "LOCATION_PERMISSION"
    is java.io.IOException, is android.database.sqlite.SQLiteException -> "STORAGE_FAILED"
    is IllegalArgumentException -> "INVALID_LOCATION"
    else -> HondaSessionRead.errorCode(error)
}

private fun tripDiagnosticError(error: Exception): ErrorCode = when {
    error is TimeoutCancellationException -> ErrorCode.TIMEOUT
    error is android.database.sqlite.SQLiteFullException -> ErrorCode.RESOURCE_EXHAUSTED
    else -> when (val code = tripRecordingError(error)) {
        "STORAGE_FAILED" -> ErrorCode.IO
        "LOCATION_PERMISSION", "FOREGROUND_LOCATION_REQUIRED" -> ErrorCode.PERMISSION_DENIED
        "LOCATION_DISABLED" -> ErrorCode.FAILED_PRECONDITION
        "LOCATION_UNAVAILABLE" -> ErrorCode.UNAVAILABLE
        "INVALID_LOCATION" -> ErrorCode.INVALID_ARGUMENT
        else -> HondaSessionRead.diagnosticError(code)
    }
}

/** Native metadata lookup for the legacy model-code field. Never guess from a display label. */
internal fun tripSeriesModelCode(model: org.openridesync.honda.wire.HondaWire.Model, raw: String): String {
    if (raw.isEmpty()) return ""
    val matches = model.vehicleCodesList.filter { it.seriesCode == raw }
    if (matches.isEmpty() || matches.any { it.modelCode.isBlank() }) return ""
    return matches.map { it.modelCode }.distinct().singleOrNull().orEmpty()
}

/** Milliseconds and SI units from Android; read-history timestamps use a different seconds contract. */
internal fun tripPoint(location: Location, previous: Location?, uid: String, trip: String, attempt: String,
    series: String, rawSeries: String, name: String, coarse: Boolean,
    now: Long = System.currentTimeMillis()): PipelineWire.Location {
    require(!LocationCompat.isMock(location)) { "Mock location is not a real ride sample" }
    require(location.latitude.isFinite() && location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0) { "Invalid real location" }
    val point = PipelineWire.Location.newBuilder().setUuid(UUID.randomUUID().toString()).setUserId(uid)
        .setTripUuid(trip).setAppSessionId(attempt).setTime(location.time).setDeviceTime(now)
        .setLatitude(location.latitude.toFloat()).setLongitude(location.longitude.toFloat())
        .setAccuracy(location.accuracy).setSpeed(location.speed).setSpeedAccuracy(location.speedAccuracyMetersPerSecond)
        .setDistance(previous?.let { location.distanceTo(it) } ?: 0f).setBearing(location.bearing)
        .setBearingAccuracy(location.bearingAccuracyDegrees).setAltitude(location.altitude.toFloat())
        .setVerticalAccuracy(location.verticalAccuracyMeters).setProvider(location.provider.orEmpty())
        .setIsDebug(false).setAppVersionCode(BuildConfig.VERSION_CODE).setPlatform("android")
        .setVehicleSeriesCode(series).setVehicleInfoSeriesCodeRaw(rawSeries).setVehicleInfoVehicleName(name)
        .setIsCoarseLocation(coarse).build()
    require(point.time > 0 && point.deviceTime > 0 && point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in -90f..90f && point.longitude in -180f..180f) { "Invalid real location" }
    return point
}
