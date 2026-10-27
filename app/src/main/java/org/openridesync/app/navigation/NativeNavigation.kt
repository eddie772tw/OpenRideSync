package org.openridesync.app.navigation

import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import org.openridesync.app.media.MediaAccessService
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*

internal enum class NavigationStatus { LOADING, ACCESS_REQUIRED, LISTENER_UNAVAILABLE, MAPS_UNAVAILABLE, WAITING, OBSERVED, AMBIGUOUS, UNSUPPORTED }
internal data class NavigationObservation(val status: NavigationStatus = NavigationStatus.LISTENER_UNAVAILABLE,
    val instruction: MapsInstruction? = null, val templates: Int = 0,
    val sourceId: String? = null, val canCancel: Boolean = false,
    val canRepeat: Boolean = instruction != null)
internal enum class NavigationCancelResult { DISPATCHED, STALE, UNSUPPORTED, ACCESS_REQUIRED, UNAVAILABLE, LOCKED }

private data class ParsedNavigation(val observation: NavigationObservation, val source: MapsSource? = null,
    val exitLabel: String? = null, val directions: String = " - ", val summary: String = " · ")

/** Explicit page/ride owner. Route text remains in memory and disappears on close or access loss. */
internal class NativeNavigation(context: Context, private val journal: DiagnosticJournal? = null,
    // Only the live ride owner may permit a fresh, physically confirmed action while locked.
    private val allowLockedControl: () -> Boolean = { false }) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val keyguard = this.context.getSystemService(KeyguardManager::class.java)
    private val component = ComponentName(this.context, MediaAccessService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(NavigationObservation())
    val state = mutableState.asStateFlow()
    private var reading: Job? = null
    private var running = false
    private var closed = false
    private var templates: Deferred<GoogleMapsManeuvers>? = null
    private var mapsVersion: Pair<Long, Long>? = null
    private var reported: NavigationStatus? = null
    private var source: MapsSource? = null
    private var sourceRevision = -1L
    private var exitLabel: String? = null
    private var directions = " - "
    private var summary = " · "
    private val spent = mutableSetOf<String>()

    fun start() {
        mainThread()
        check(!closed)
        if (running) return
        running = true
        scope.launch { MediaAccessService.changes.drop(1).collect { refresh() } }
        scope.launch {
            var granted = hasAccess()
            while (isActive) {
                delay(1_000)
                val now = hasAccess()
                if (now != granted) { granted = now; refresh(); if (now) rebind() }
            }
        }
        refresh()
        if (hasAccess()) rebind()
    }

    fun refresh() {
        mainThread()
        if (!running || closed) return
        reading?.cancel()
        if (!hasAccess()) { clearSource(); publish(NavigationObservation(NavigationStatus.ACCESS_REQUIRED)); return }
        if (!MediaAccessService.callSummary.value.listenerConnected) { clearSource(); publish(NavigationObservation()); return }
        publish(NavigationObservation(NavigationStatus.LOADING))
        reading = scope.launch {
            var revision = MediaAccessService.changes.value
            val parsed = try {
                val version = withContext(Dispatchers.Default) {
                    context.packageManager.getPackageInfo(MAPS_PACKAGE, 0).let { it.longVersionCode to it.lastUpdateTime }
                }
                if (mapsVersion != version) {
                    templates?.cancel()
                    // Sibling of the reading job: notification replacement does not restart SVG loading.
                    templates = scope.async(Dispatchers.Default) { GoogleMapsManeuvers(context) }
                    mapsVersion = version
                }
                val matcher = checkNotNull(templates).await()
                val resources = context.packageManager.getResourcesForApplication(MAPS_PACKAGE)
                fun resource(name: String): String? {
                    val id = resources.getIdentifier(name, "string", MAPS_PACKAGE)
                    return if (id == 0) null else resources.getString(id)
                }
                val exit = resource("DA_EXIT_NAVIGATION")
                val directions = mapsSeparator(resource("DA_LOCKSCREEN_NOTIFICATION_DIRECTIONS"), " - ")
                val summary = mapsSeparator(resource("DA_EXPANDED_NOTIFICATION_SUBTEXT"), " · ")
                // Read only after templates load; carry no other app's notification to the worker.
                revision = MediaAccessService.changes.value
                if (!hasAccess()) {
                    clearSource(); publish(NavigationObservation(NavigationStatus.ACCESS_REQUIRED)); return@launch
                }
                val candidates = MediaAccessService.readActiveNotifications()?.filter { isMapsNavigation(it, exit) }
                    ?: run { clearSource(); publish(NavigationObservation()); return@launch }
                withContext(Dispatchers.Default) {
                    ensureActive()
                    when (candidates.size) {
                        0 -> ParsedNavigation(NavigationObservation(NavigationStatus.WAITING,
                            templates = matcher.templateCount), exitLabel = exit,
                            directions = directions, summary = summary)
                        1 -> {
                            val candidate = candidates.single()
                            val n = candidate.notification
                            val type = mapsNotificationBitmap(context, n)?.let(matcher::detect)
                            val source = mapsSource(candidate, exit, directions, summary, type)
                            ParsedNavigation(NavigationObservation(
                                if (source?.instruction == null) NavigationStatus.UNSUPPORTED else NavigationStatus.OBSERVED,
                                source?.instruction, matcher.templateCount), source, exit, directions, summary)
                        }
                        else -> ParsedNavigation(NavigationObservation(NavigationStatus.AMBIGUOUS,
                            templates = matcher.templateCount), exitLabel = exit,
                            directions = directions, summary = summary)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                  ParsedNavigation(NavigationObservation(NavigationStatus.MAPS_UNAVAILABLE))
              }
              catch (_: RuntimeException) { ParsedNavigation(NavigationObservation(NavigationStatus.UNSUPPORTED)) }
            if (running && !closed) {
                if (MediaAccessService.changes.value != revision) { refresh(); return@launch }
                if (!hasAccess()) { clearSource(); publish(NavigationObservation(NavigationStatus.ACCESS_REQUIRED)); return@launch }
                if (!MediaAccessService.callSummary.value.listenerConnected) {
                    clearSource(); publish(NavigationObservation()); return@launch
                }
                val previous = source
                val next = reconcileMapsSource(previous, parsed.source)
                if (previous?.id != next?.id) spent.clear()
                source = next
                sourceRevision = revision
                exitLabel = parsed.exitLabel
                directions = parsed.directions
                summary = parsed.summary
                publish(parsed.observation.copy(sourceId = next?.id,
                    canCancel = next?.let { it.exitAction != null && it.id !in spent } == true,
                    canRepeat = next?.let { it.instruction != null && it.id !in spent } == true))
            }
        }
    }

    /** Re-read the exact current notification before explicitly requested speech starts. */
    fun currentInstruction(id: String): MapsInstruction? {
        mainThread()
        val before = source?.takeIf { it.id == id && state.value.sourceId == id &&
            state.value.status == NavigationStatus.OBSERVED && id !in spent } ?: return null
        return before.instruction?.takeIf { liveSource(before)?.signature == before.signature }
    }

    /** Cheap hook for speakRideLines.valid(); listener changes invalidate speech before parsing finishes. */
    fun isCurrent(id: String): Boolean {
        mainThread()
        return running && !closed && id !in spent && source?.id == id && state.value.sourceId == id &&
            state.value.status == NavigationStatus.OBSERVED && sourceRevision == MediaAccessService.changes.value &&
            hasAccess() && MediaAccessService.callSummary.value.listenerConnected
    }

    /** Send only the current unique Maps exit action; dispatch is not confirmed route termination. */
    fun cancel(id: String): NavigationCancelResult {
        mainThread()
        val operation = journal?.newOperation()
        journal?.record(Component.NAVIGATION, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
        val result = try {
            val before = source
            when {
                !running || closed -> NavigationCancelResult.UNAVAILABLE
                !hasAccess() -> NavigationCancelResult.ACCESS_REQUIRED
                !MediaAccessService.callSummary.value.listenerConnected -> NavigationCancelResult.UNAVAILABLE
                before == null || before.id != id || state.value.sourceId != id || id in spent -> NavigationCancelResult.STALE
                keyguard?.isDeviceLocked != false && !allowLockedControl() -> NavigationCancelResult.LOCKED
                liveSource(before)?.signature != before.signature -> NavigationCancelResult.STALE
                before.exitAction == null -> NavigationCancelResult.UNSUPPORTED
                else -> dispatchMapsExit(before.exitAction, spent, id).also {
                    if (id in spent) publish(state.value.copy(canCancel = false, canRepeat = false))
                }
            }
        } catch (_: SecurityException) { NavigationCancelResult.ACCESS_REQUIRED }
          catch (_: RuntimeException) { NavigationCancelResult.UNAVAILABLE }
        val code = when (result) {
            NavigationCancelResult.DISPATCHED -> ErrorCode.NONE
            NavigationCancelResult.STALE -> ErrorCode.ABORTED
            NavigationCancelResult.UNSUPPORTED -> ErrorCode.UNSUPPORTED
            NavigationCancelResult.ACCESS_REQUIRED -> ErrorCode.PERMISSION_DENIED
            NavigationCancelResult.UNAVAILABLE -> ErrorCode.UNAVAILABLE
            NavigationCancelResult.LOCKED -> ErrorCode.FAILED_PRECONDITION
        }
        journal?.record(Component.NAVIGATION, operation, Event.REQUEST,
            if (result == NavigationCancelResult.DISPATCHED) Outcome.OBSERVED else Outcome.FAILED, code)
        return result
    }

    private fun liveSource(before: MapsSource): MapsSource? {
        if (!running || closed || sourceRevision != MediaAccessService.changes.value || !hasAccess() ||
            !MediaAccessService.callSummary.value.listenerConnected) return null
        val candidates = MediaAccessService.readActiveNotifications()?.filter { isMapsNavigation(it, exitLabel) }
            ?: return null
        if (candidates.size != 1) return null
        return mapsSource(candidates.single(), exitLabel, directions, summary, before.signature.maneuver)
    }

    private fun clearSource() {
        source = null; sourceRevision = -1L; exitLabel = null; spent.clear()
    }

    private fun publish(value: NavigationObservation) {
        if (reported != value.status && value.status != NavigationStatus.LOADING) {
            reported = value.status
            val code = when (value.status) {
                NavigationStatus.ACCESS_REQUIRED -> ErrorCode.PERMISSION_DENIED
                NavigationStatus.LISTENER_UNAVAILABLE, NavigationStatus.MAPS_UNAVAILABLE -> ErrorCode.UNAVAILABLE
                NavigationStatus.AMBIGUOUS, NavigationStatus.UNSUPPORTED -> ErrorCode.FAILED_PRECONDITION
                else -> ErrorCode.NONE
            }
            journal?.record(Component.NAVIGATION, null, Event.STATE_CHANGE,
                if (code == ErrorCode.NONE) Outcome.OBSERVED else Outcome.UNAVAILABLE, code)
        }
        mutableState.value = value
    }

    private fun hasAccess() = try { manager?.isNotificationListenerAccessGranted(component) == true }
        catch (_: RuntimeException) { false }
    private fun rebind() { try { MediaAccessService.rebindIfDisconnected(component) } catch (_: RuntimeException) { } }
    override fun close() {
        mainThread()
        if (closed) return
        closed = true; running = false
        scope.cancel()
        templates = null; mapsVersion = null
        clearSource()
        mutableState.value = NavigationObservation()
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
}

internal fun dispatchMapsExit(pending: PendingIntent, spent: MutableSet<String>, id: String): NavigationCancelResult {
    if (!spent.add(id)) return NavigationCancelResult.STALE
    return try { pending.send(); NavigationCancelResult.DISPATCHED }
    catch (_: PendingIntent.CanceledException) { NavigationCancelResult.STALE }
    catch (_: RuntimeException) { NavigationCancelResult.UNAVAILABLE }
}
