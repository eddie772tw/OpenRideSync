package org.openridesync.app.trips

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.auth.AuthState
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.honda.TripCache
import org.openridesync.honda.wire.HondaWire.*
import java.io.File
import java.security.MessageDigest
import java.util.TimeZone
import java.util.concurrent.TimeoutException

data class TripState(
    val summary: TripsSummary? = null,
    val days: List<DailyTrips> = emptyList(),
    val selectedDay: DailyTrips? = null,
    val details: ListTripDetailsResponse? = null,
    val busy: Boolean = false,
    val hasLoaded: Boolean = false,
    val cancelled: Boolean = false,
    val cached: Boolean = false,
    val summaryCached: Boolean = false,
    val detailsCached: Boolean = false,
    val errorCode: String? = null,
    val summaryErrorCode: String? = null,
    val storageError: Boolean = false,
    val hasMore: Boolean = false,
    val offsetMillis: Int = 0,
)

/** Same Honda identity, read-only history. No upload, delete or synthetic trip creation. */
class TripViewModel(application: Application) : AndroidViewModel(application) {
    val session = AuthSession.get()
    private val app = application as OpenRideSyncApplication
    private val mutableState = MutableStateFlow(TripState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var nextCursor: Long? = null
    private var retryCursor: Long? = null
    private var retryBase: List<DailyTrips> = emptyList()
    private var retryCached = false
    private var historyReturn: TripState? = null
    private val readGate = TripReadGate()
    private var observedAuth = session.state.value

    init {
        viewModelScope.launch {
            session.state.collect(::resetForAuth)
        }
    }

    private fun resetForAuth(auth: AuthState) {
        if (auth == observedAuth) return
        observedAuth = auth
        cancel()
        mutableState.value = TripState()
        nextCursor = null; retryCursor = null; retryBase = emptyList(); retryCached = false
        historyReturn = null
    }

    fun onStart() { readGate.onStart() }
    fun onHomeStart() { resetForAuth(session.state.value); onStart(); refresh() }
    fun onStop() { readGate.onStop(); cancel() }

    fun cancel() {
        readGate.cancel()
        generation++; job?.cancel(); job = null
        mutableState.value = state.value.copy(busy = false, cancelled = state.value.busy || state.value.cancelled)
    }
    fun back(): Boolean {
        cancel()
        if (state.value.selectedDay == null) return false
        mutableState.value = restoreTripHistory(historyReturn ?: TripState())
        historyReturn = null
        return true
    }
    fun retry() {
        when (tripRetryTarget(state.value, retryCursor != null)) {
            TripRetryTarget.PAGE -> loadMore()
            TripRetryTarget.HISTORY, TripRetryTarget.DETAIL -> refresh()
        }
    }

    fun refresh() {
        if (state.value.busy || !readGate.canBegin(session.state.value.status == AuthStatus.SIGNED_IN)) return
        state.value.selectedDay?.let { openDay(it); return }
        cancel()
        val now = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(now)
        nextCursor = null; retryCursor = null; retryBase = emptyList()
        val sameScope = state.value.offsetMillis == offset
        mutableState.value = state.value.copy(busy = true, cancelled = false, errorCode = null, summaryErrorCode = null, storageError = false,
            offsetMillis = offset, hasMore = false, days = if (sameScope) state.value.days else emptyList(),
            hasLoaded = sameScope && state.value.hasLoaded,
            summary = if (sameScope) state.value.summary else null,
            cached = sameScope && state.value.days.isNotEmpty(), summaryCached = sameScope && state.value.summary != null)
        load { cache, reader, publish, check ->
            val summaryRequest = tripRequest(now, offset, 1)
            val pageRequest = tripRequest(now, offset)
            try {
                cache.latestDailyRequest(summaryRequest)?.let { old -> cache.readDaily(old)?.let { saved ->
                    val summary = tripSummary(saved.value())
                    publish { it.copy(summary = summary, summaryCached = true) }
                } }
            } catch (_: Exception) { publish { it.copy(storageError = true) } }
            try {
                cache.latestDailyRequest(pageRequest)?.let { old -> cache.readDaily(old)?.let { saved ->
                    val page = tripPage(saved.value(), old.startDate * 1000, offset)
                    publish { nextCursor = page.nextCursorMillis; it.copy(days = page.days, hasLoaded = true, cached = true, hasMore = nextCursor != null) }
                } }
            } catch (_: Exception) { publish { it.copy(storageError = true) } }
            try {
                val response = readDaily(reader, summaryRequest, check)
                val summary = tripSummary(response)
                save(cache, summaryRequest, response, latest = true, publish)
                publish { it.copy(summary = summary, summaryCached = false, summaryErrorCode = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                publish { it.copy(summaryErrorCode = errorCode(error)) }
            }
            val response = readDaily(reader, pageRequest, check)
            val page = tripPage(response, now, offset)
            save(cache, pageRequest, response, latest = true, publish)
            publish { nextCursor = page.nextCursorMillis; it.copy(days = page.days, hasLoaded = true, cached = false, hasMore = nextCursor != null) }
        }
    }

    fun loadMore() {
        if (state.value.busy || state.value.selectedDay != null || !readGate.canBegin(session.state.value.status == AuthStatus.SIGNED_IN)) return
        val cursor = retryCursor ?: nextCursor ?: return
        if (retryCursor == null) { retryBase = state.value.days; retryCached = state.value.cached }
        val base = retryBase
        val wasCached = retryCached
        val offset = state.value.offsetMillis
        cancel(); retryCursor = cursor
        mutableState.value = state.value.copy(busy = true, cancelled = false, errorCode = null)
        load { cache, reader, publish, check ->
            val request = tripRequest(cursor, offset)
            try { cache.readDaily(request)?.let { saved ->
                val page = tripPage(saved.value(), cursor, offset)
                publish { nextCursor = page.nextCursorMillis; it.copy(days = mergeTripDays(base, page.days), cached = true, hasMore = nextCursor != null) }
            } } catch (_: Exception) { publish { it.copy(storageError = true) } }
            val response = readDaily(reader, request, check)
            val page = tripPage(response, cursor, offset)
            save(cache, request, response, latest = false, publish)
            publish {
                nextCursor = page.nextCursorMillis; retryCursor = null; retryBase = emptyList()
                it.copy(days = mergeTripDays(base, page.days), cached = wasCached, hasMore = nextCursor != null)
            }
        }
    }

    fun openDay(day: DailyTrips) {
        if (state.value.busy || !readGate.canBegin(session.state.value.status == AuthStatus.SIGNED_IN) || day.tripUuidsCount == 0 ||
            (state.value.selectedDay != null && state.value.selectedDay != day)) return
        if (state.value.selectedDay == null) historyReturn = state.value
        cancel()
        val existing = state.value.details?.takeIf { state.value.selectedDay == day }
        mutableState.value = state.value.copy(selectedDay = day, details = existing, detailsCached = existing != null,
            busy = true, cancelled = false, errorCode = null, storageError = false)
        load { cache, reader, publish, check ->
            val request = ListTripDetailsRequest.newBuilder().addAllTripUuids(day.tripUuidsList).build()
            try { cache.readDetails(request)?.let { saved ->
                val details = tripDetails(day, saved.value())
                publish { it.copy(details = details, detailsCached = true) }
            } } catch (_: Exception) { publish { it.copy(storageError = true) } }
            val response = withTimeoutOrNull(20_000) { runInterruptible(Dispatchers.IO) {
                reader.read(Event.HONDA_LIST_TRIP_DETAILS) { client, key, token -> check(); client.listTripDetails(request, key, token) }
            } } ?: throw TimeoutException()
            val details = tripDetails(day, response)
            try { TripWrites.commit { cache.writeDetails(request, response) } }
            catch (_: java.io.IOException) { publish { it.copy(storageError = true) } }
            publish { it.copy(details = details, detailsCached = false) }
        }
    }

    private suspend fun readDaily(reader: HondaSessionRead, request: ListDailyTripsRequest, check: () -> Unit): ListDailyTripsResponse =
        withTimeoutOrNull(10_000) { runInterruptible(Dispatchers.IO) {
            reader.read(Event.HONDA_LIST_DAILY_TRIPS) { client, key, token -> check(); client.listDailyTrips(request, key, token) }
        } } ?: throw TimeoutException()

    private suspend fun save(cache: TripCache, request: ListDailyTripsRequest, response: ListDailyTripsResponse,
        latest: Boolean, publish: suspend ((TripState) -> TripState) -> Unit) {
        try { TripWrites.commit { cache.writeDaily(request, response); if (latest) cache.indexLatestDaily(request) } }
        catch (_: java.io.IOException) { publish { it.copy(storageError = true) } }
    }

    private fun load(block: suspend (TripCache, HondaSessionRead, suspend ((TripState) -> TripState) -> Unit, () -> Unit) -> Unit) {
        val uid = session.currentUser()?.uid ?: run {
            mutableState.value = state.value.copy(busy = false, errorCode = "AUTH_REQUIRED")
            return
        }
        val revision = session.revision
        val ticket = readGate.begin(session.state.value.status == AuthStatus.SIGNED_IN) ?: run { cancel(); return }
        val current = ++generation
        fun currentIdentity() = readGate.accepts(ticket) && current == generation && session.revision == revision && session.currentUser()?.uid == uid &&
            session.state.value.status == AuthStatus.SIGNED_IN
        val operation = app.journal?.newOperation() ?: java.util.UUID.randomUUID().toString()
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    val cache = TripCache(File(app.filesDir, "trips/$hash"))
                    HondaSessionRead(session, app.journal, operation, uid, revision).use { reader ->
                        val check = {
                            if (Thread.currentThread().isInterrupted || !currentIdentity()) throw CancellationException()
                        }
                        check()
                        block(cache, reader, { transform -> withContext(Dispatchers.Main) {
                            if (currentIdentity()) mutableState.value = transform(state.value)
                        } }, check)
                    }
                }
                ensureActive()
                if (currentIdentity()) mutableState.value = state.value.copy(busy = false)
            } catch (_: CancellationException) {
                app.journal?.record(Component.SYNC, operation, Event.STOP, Outcome.CANCELLED, ErrorCode.CANCELLED)
            } catch (error: Exception) {
                val code = errorCode(error)
                app.journal?.record(Component.SYNC, operation, Event.STATE_CHANGE, Outcome.FAILED, HondaSessionRead.diagnosticError(code))
                if (currentIdentity()) mutableState.value = state.value.copy(busy = false, errorCode = code)
            }
        }
    }

    private fun errorCode(error: Exception): String = if (error is TripDataException) "DATA_LOSS" else HondaSessionRead.errorCode(error)
}

private object TripWrites {
    private val mutex = Mutex()
    suspend fun <T> commit(action: () -> T): T = mutex.withLock { currentCoroutineContext().ensureActive(); action() }
}
