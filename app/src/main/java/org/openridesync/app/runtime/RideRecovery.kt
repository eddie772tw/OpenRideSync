package org.openridesync.app.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.openridesync.app.ble.GattFailure
import org.openridesync.app.weather.weatherLocationGranted
import kotlin.coroutines.resume

/** F7's global ordinal belongs to the whole explicitly started ride, including mixed errors.
 * Local mutex/duplicate subscription errors and downstream control/security are not Rx h7. */
internal suspend fun <T> reconnectRide(onRetry: (GattFailure, Int) -> Unit, connect: suspend () -> T): T {
    var ordinal = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try { return connect() }
        catch (failure: GattFailure) {
            currentCoroutineContext().ensureActive()
            ordinal++
            val retry = when (failure.boundary) {
                GattFailure.Boundary.TRANSPORT -> when (failure.origin) {
                    GattFailure.Origin.CONNECT_DEADLINE, GattFailure.Origin.DISCONNECTED -> true
                    GattFailure.Origin.CONNECTION_STATUS -> ordinal <= 3
                    else -> false
                }
                GattFailure.Boundary.INITIALIZATION -> ordinal <= 3 && failure.origin in setOf(
                    GattFailure.Origin.OPERATION_STATUS, GattFailure.Origin.OPERATION_REJECTED,
                    GattFailure.Origin.OPERATION_TIMEOUT)
                else -> false
            }
            if (!retry) throw failure
            // Native exhaustion is explicit failure rather than Rx's finite notifier completion.
            if (ordinal >= 10_000) throw RideFailure("RECONNECT_LIMIT")
            onRetry(failure, ordinal)
            yield() // Cancellation fairness, not a fabricated OEM backoff timer.
        }
    }
}

internal data class RideMotion(val accuracy: Float, val speed: Float) {
    fun valid() = accuracy.isFinite() && accuracy >= 0 && speed.isFinite() && speed >= 0
}

/** One raw-link recovery owner per ride. No initial false snapshot and no UI readiness claim.
 * Every window/probe is cancelled by raw CONNECTED, explicit stop or identity loss. */
internal class RideRecovery(scope: CoroutineScope, private val sample: suspend () -> RideMotion,
    private val pending: () -> Unit) : AutoCloseable {
    private enum class LinkEvent { CONNECTED, DISCONNECTED, RETRY }
    private val events = Channel<LinkEvent>(64)
    @Volatile private var closed = false
    @Volatile private var rawConnected = false
    private var window: Job? = null
    private var retryAllowed: CompletableDeferred<Unit>? = null
    private val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (event in events) {
            when (event) {
                LinkEvent.CONNECTED -> {
                    window?.cancelAndJoin()
                    window = null
                    retryAllowed = null
                }
                LinkEvent.DISCONNECTED -> if (window == null) {
                    pending()
                    val allowed = CompletableDeferred<Unit>().also { retryAllowed = it }
                    window = scope.launch {
                        try { waitForRecovery(allowed) }
                        catch (failure: RideFailure) {
                            // Drain queued raw state before committing a synchronous location
                            // failure. A recovered link must not be stopped by an obsolete probe.
                            yield()
                            if (!closed && !rawConnected) throw failure
                        }
                    }
                }
                LinkEvent.RETRY -> retryAllowed?.complete(Unit)
            }
        }
    }

    fun rawState(connected: Boolean) {
        if (closed) return
        rawConnected = connected
        send(if (connected) LinkEvent.CONNECTED else LinkEvent.DISCONNECTED)
    }

    /** Classify and join the failed transport before allowing motion errors to end the ride.
     * Nonretryable control/security failures retain their original cause during teardown. */
    fun retrying() = send(LinkEvent.RETRY)

    private fun send(event: LinkEvent) {
        if (!closed && events.trySend(event).isFailure) events.close(RideFailure("RECONNECT_STOPPED"))
    }

    private suspend fun probe(deadline: Long): RideMotion = try {
        withTimeout(deadline) { sample() }.also {
            if (!it.valid()) throw RideFailure("RECONNECT_STOPPED")
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw RideFailure("RECONNECT_STOPPED")
    }

    private suspend fun waitForRecovery(allowed: Deferred<Unit>) {
        withTimeoutOrNull(60_000) {
            allowed.await()
            val initial = probe(5_000)
            if (initial.accuracy > 30 || initial.speed < 3.5f) throw RideFailure("RECONNECT_STOPPED")
            val samples = coroutineScope {
                // Start at 1s intervals, permitting the original 1.5s overlapping probe deadlines.
                // Native policy: join all three replies before judging the mean, avoiding the
                // OEM tick-stream finally racing its last independently subscribed response.
                List(3) {
                    delay(1_000)
                    async(start = CoroutineStart.UNDISPATCHED) { probe(1_500) }
                }.awaitAll()
            }
            val mean = samples.filter { it.accuracy < 30 }.map { it.speed.toDouble() }.average()
            if (!mean.isFinite() || mean < 3.5) throw RideFailure("RECONNECT_STOPPED")
            awaitCancellation()
        }
        throw RideFailure("RECONNECT_WINDOW_EXPIRED")
    }

    override fun close() {
        closed = true
        events.cancel()
        observer.cancel()
        window?.cancel()
        window = null
        retryAllowed = null
    }
}

/** Location is optional for manual connection. Recovery needs a location foreground service
 * and current motion; it neither requests precision/background permission nor stores coordinates. */
@SuppressLint("MissingPermission")
internal suspend fun recoveryMotion(context: Context, locationService: Boolean): RideMotion {
    val manager = context.getSystemService(LocationManager::class.java)
    fun allowed() = locationService && weatherLocationGranted(context) && manager?.isLocationEnabled == true
    if (!allowed()) throw RideFailure("RECONNECT_LOCATION_REQUIRED")
    val signal = CancellationSignal()
    try {
        val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { it in manager!!.allProviders && manager.isProviderEnabled(it) }
            ?: throw RideFailure("RECONNECT_STOPPED")
        val location = suspendCancellableCoroutine<Location?> { continuation ->
            continuation.invokeOnCancellation { signal.cancel() }
            manager!!.getCurrentLocation(provider, signal, context.mainExecutor) {
                if (continuation.isActive) continuation.resume(it)
            }
        } ?: throw RideFailure("RECONNECT_STOPPED")
        if (!allowed()) throw RideFailure("RECONNECT_LOCATION_REQUIRED")
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        // Native validation rejects absent/stale/non-finite motion instead of treating it as 0.
        if (!location.hasAccuracy() || !location.hasSpeed() || age !in 0..5_000_000_000L)
            throw RideFailure("RECONNECT_STOPPED")
        return RideMotion(location.accuracy, location.speed).also {
            if (!it.valid()) throw RideFailure("RECONNECT_STOPPED")
        }
    } catch (_: SecurityException) { throw RideFailure("RECONNECT_LOCATION_REQUIRED") }
    catch (_: IllegalArgumentException) { throw RideFailure("RECONNECT_STOPPED") }
    finally { signal.cancel() }
}
