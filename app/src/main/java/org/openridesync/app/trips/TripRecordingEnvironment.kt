package org.openridesync.app.trips

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthState
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.app.weather.weatherLocationGranted
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.honda.wire.PipelineWire
import java.util.TimeZone
import kotlin.coroutines.resume

/** Android capture and authenticated network boundary; host tests use only synthetic samples. */
internal interface TripRecordingEnvironment {
    val auth: StateFlow<AuthState>
    val uid: String?
    val revision: Long
    val locationGranted: Boolean
    val locationEnabled: Boolean
    val coarse: Boolean
    fun samples(): Flow<Location>
    suspend fun finalLocation(): Location?
    suspend fun upload(uid: String, revision: Long, points: List<PipelineWire.Location>, operation: String, check: () -> Unit)
}

internal class TripRecordingFailure(val code: String) : Exception(code)

internal class AndroidTripRecordingEnvironment(private val app: OpenRideSyncApplication) : TripRecordingEnvironment {
    private val session = AuthSession.get()
    private val manager get() = app.getSystemService(LocationManager::class.java)
    override val auth get() = session.state
    override val uid get() = session.currentUser()?.uid
    override val revision get() = session.revision
    override val locationGranted get() = weatherLocationGranted(app)
    override val locationEnabled get() = manager?.isLocationEnabled == true
    override val coarse get() = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED

    private fun provider(): String {
        if (!locationGranted) throw TripRecordingFailure("LOCATION_PERMISSION")
        val service = manager ?: throw TripRecordingFailure("LOCATION_UNAVAILABLE")
        if (!service.isLocationEnabled) throw TripRecordingFailure("LOCATION_DISABLED")
        val candidates = if (coarse) listOf("fused", LocationManager.NETWORK_PROVIDER)
            else listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return candidates
            .firstOrNull { it in service.allProviders && service.isProviderEnabled(it) }
            ?: throw TripRecordingFailure("LOCATION_UNAVAILABLE")
    }

    @SuppressLint("MissingPermission") // Explicit owner checks before subscription and every accepted sample.
    override fun samples(): Flow<Location> = callbackFlow {
        val service = manager ?: throw TripRecordingFailure("LOCATION_UNAVAILABLE")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (trySend(Location(location)).isFailure) close(TripRecordingFailure("RESOURCE_EXHAUSTED"))
            }
            override fun onProviderDisabled(provider: String) { close(TripRecordingFailure("LOCATION_DISABLED")) }
        }
        try {
            service.requestLocationUpdates(provider(), 30_000L, 10f, app.mainExecutor, listener)
            awaitClose { }
        } finally { runCatching { service.removeUpdates(listener) } }
    }

    @SuppressLint("MissingPermission")
    override suspend fun finalLocation(): Location? {
        val service = manager ?: return null
        val provider = provider()
        val signal = CancellationSignal()
        try {
            val fresh = withTimeoutOrNull(4_500) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    continuation.invokeOnCancellation { signal.cancel() }
                    service.getCurrentLocation(provider, signal, app.mainExecutor) {
                        if (continuation.isActive) continuation.resume(it?.let(::Location))
                    }
                }
            }
            return fresh ?: service.getLastKnownLocation(provider)?.let(::Location)
        } finally { signal.cancel() }
    }

    override suspend fun upload(uid: String, revision: Long, points: List<PipelineWire.Location>, operation: String, check: () -> Unit) {
        runInterruptible(Dispatchers.IO) {
            check()
            // The original builder supplies the current upload-time offset, in seconds.
            val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
            val request = PipelineWire.CreateLocationRequest.newBuilder().addAllLocations(
                points.map { it.toBuilder().setOffsetFromUtc(offset).build() }).build()
            HondaSessionRead(session, app.journal, operationId = operation, expectedUid = uid, expectedRevision = revision).use { rpc ->
                rpc.write(Event.REQUEST) { client, key, token ->
                    check()
                    client.createLocations(request, key, token)
                }
            }
        }
    }
}
