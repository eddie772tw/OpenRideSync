package org.openridesync.app.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class WeatherLocationException(val code: String) : Exception(code)
internal fun weatherLocationGranted(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Foreground-screen request only. No persisted coordinates or background-location permission.
 * Current fix first, then the newest available last-known fix, as in WeatherManager.E. */
@SuppressLint("MissingPermission")
internal suspend fun weatherLocation(context: Context): WeatherLocation {
    if (!weatherLocationGranted(context)) throw WeatherLocationException("LOCATION_PERMISSION")
    val manager = context.getSystemService(LocationManager::class.java) ?: throw WeatherLocationException("LOCATION_UNAVAILABLE")
    try {
        if (!manager.isLocationEnabled) throw WeatherLocationException("LOCATION_DISABLED")
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it in manager.allProviders && manager.isProviderEnabled(it) }
        if (providers.isEmpty()) throw WeatherLocationException("LOCATION_UNAVAILABLE")
        val signal = CancellationSignal()
        val current = try {
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(providers.first(), signal, context.mainExecutor) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                }
            }
        } finally { signal.cancel() }
        fun valid(value: Location) = value.latitude.isFinite() && value.longitude.isFinite() &&
            value.latitude in -90.0..90.0 && value.longitude in -180.0..180.0
        val fresh = current?.takeIf(::valid)
        val location = fresh ?: providers.mapNotNull { manager.getLastKnownLocation(it) }.filter(::valid)
            .maxByOrNull { it.elapsedRealtimeNanos } ?: throw WeatherLocationException("LOCATION_UNAVAILABLE")
        if (!weatherLocationGranted(context)) throw WeatherLocationException("LOCATION_PERMISSION")
        if (!manager.isLocationEnabled) throw WeatherLocationException("LOCATION_DISABLED")
        val age = location.elapsedRealtimeNanos.takeIf { it > 0 }?.let { ((SystemClock.elapsedRealtimeNanos() - it) / 1_000_000).coerceAtLeast(0) }
        return WeatherLocation(location.latitude, location.longitude, fresh == null, age)
    } catch (_: SecurityException) { throw WeatherLocationException("LOCATION_PERMISSION") }
    catch (_: IllegalArgumentException) { throw WeatherLocationException("LOCATION_UNAVAILABLE") }
}
