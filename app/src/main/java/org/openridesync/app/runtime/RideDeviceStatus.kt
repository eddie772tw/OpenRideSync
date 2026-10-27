package org.openridesync.app.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.*

internal enum class SignalAvailability { READY, OFF, DISCONNECTED, NO_SIM, UNSUPPORTED, UNAVAILABLE }
internal enum class SignalNameIssue { PERMISSION_REQUIRED, LOCATION_OFF, UNAVAILABLE }
internal data class RideSignal(val availability: SignalAvailability = SignalAvailability.UNAVAILABLE, val level: Int? = null,
    val name: String? = null, val nameIssue: SignalNameIssue? = null)
internal data class RideDeviceStatus(val batteryPercent: Int? = null, val charging: Boolean = false,
    val mobile: RideSignal = RideSignal(), val wifi: RideSignal = RideSignal(), val internet: Boolean? = null)

internal fun batteryPercentage(level: Int, scale: Int): Int? =
    if (scale <= 0 || level < 0 || level > scale) null else (level.toLong() * 100 / scale).toInt()

internal fun signalName(raw: String?): String? = raw?.takeUnless { value ->
    value.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
}?.trim()?.takeIf { it.isNotEmpty() }

internal fun wifiSsid(raw: String?): String? = signalName(raw)?.let {
    if (it.length >= 2 && it.first() == '"' && it.last() == '"') it.substring(1, it.lastIndex) else it
}?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }

internal fun missingWifiNameIssue(fineLocationGranted: Boolean, locationEnabled: Boolean?): SignalNameIssue = when {
    !fineLocationGranted -> SignalNameIssue.PERMISSION_REQUIRED
    locationEnabled == false -> SignalNameIssue.LOCATION_OFF
    else -> SignalNameIssue.UNAVAILABLE
}

/** Foreground display observations only: names stay in memory; no scan, BSSID, permission request or journal body. */
@Suppress("DEPRECATION") // API30-compatible current WifiInfo; never request scan results.
internal fun readRideDeviceStatus(context: Context): RideDeviceStatus {
    val app = context.applicationContext
    val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val wifi = runCatching {
        val manager = app.getSystemService(WifiManager::class.java) ?: return@runCatching RideSignal(SignalAvailability.UNSUPPORTED)
        when {
            !app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) -> RideSignal(SignalAvailability.UNSUPPORTED)
            !manager.isWifiEnabled -> RideSignal(SignalAvailability.OFF)
            else -> {
                val info = manager.connectionInfo
                if (info?.supplicantState != SupplicantState.COMPLETED) RideSignal(SignalAvailability.DISCONNECTED)
                else {
                    val level = runCatching { info.rssi.takeIf { it in -126..-1 }
                        ?.let { WifiManager.calculateSignalLevel(it, 5) } }.getOrNull()
                    val fineLocation = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val name = if (fineLocation) runCatching { wifiSsid(info.ssid) }.getOrNull() else null
                    val issue = if (name != null) null else missingWifiNameIssue(fineLocation,
                        runCatching { app.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull())
                    RideSignal(SignalAvailability.READY, level, name, issue)
                }
            }
        }
    }.getOrElse { RideSignal() }
    val mobile = runCatching {
        when {
            !app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> RideSignal(SignalAvailability.UNSUPPORTED)
            Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 -> RideSignal(SignalAvailability.OFF)
            else -> {
                val manager = app.getSystemService(TelephonyManager::class.java) ?: return@runCatching RideSignal()
                val dataManager = runCatching {
                    SubscriptionManager.getDefaultDataSubscriptionId().takeIf { SubscriptionManager.isValidSubscriptionId(it) }
                        ?.let { manager.createForSubscriptionId(it) }
                }.getOrNull() ?: manager
                if (runCatching { dataManager.simState }.getOrNull() == TelephonyManager.SIM_STATE_ABSENT)
                    RideSignal(SignalAvailability.NO_SIM)
                else {
                    val level = runCatching { dataManager.signalStrength?.level?.takeIf { it in 0..4 } }.getOrNull()
                    val name = runCatching { signalName(dataManager.simOperatorName) }.getOrNull()
                        ?: runCatching { signalName(dataManager.networkOperatorName) }.getOrNull()
                    RideSignal(SignalAvailability.READY, level, name,
                        if (name == null) SignalNameIssue.UNAVAILABLE else null)
                }
            }
        }
    }.getOrElse { RideSignal() }
    val internet = runCatching {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrNull()
    return RideDeviceStatus(batteryPercentage(battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
        battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1),
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, mobile, wifi, internet)
}

/** One bounded foreground sampler shared by all connected phone routes, independent of handlebar input. */
@Composable
internal fun rememberRideDeviceStatus(connected: Boolean): RideDeviceStatus {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(RideDeviceStatus()) }
    LifecycleResumeEffect(context, connected) {
        state = RideDeviceStatus()
        val job = if (connected) scope.launch {
            while (isActive) {
                val observed = withContext(Dispatchers.IO) { readRideDeviceStatus(context) }
                ensureActive()
                state = observed
                delay(5_000)
            }
        } else null
        onPauseOrDispose { job?.cancel(); state = RideDeviceStatus() }
    }
    return state
}
