package org.openridesync.app.weather

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.honda.HondaSessionRead

internal data class WeatherPageState(
    val signedIn: Boolean = false,
    val observing: Boolean = false,
    val locationGranted: Boolean = false,
    val loading: Boolean = false,
    val current: CurrentWeather? = null,
    val forecast: ForecastWeather? = null,
    val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val lastKnownLocation: Boolean = false,
    val locationAgeMillis: Long? = null,
    val fetchedAtMillis: Long? = null,
    val errorCode: String? = null,
    val forecastErrorCode: String? = null,
    val settingsUnavailable: Boolean = false,
)

/** Foreground weather page, original Honda identity. No fake location or offline success cache. */
internal class WeatherViewModel(application: Application, private val environment: WeatherEnvironment) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AndroidWeatherEnvironment(application as OpenRideSyncApplication))
    private val initialUnit = environment.unit
    private val mutableState = MutableStateFlow(WeatherPageState(unit = initialUnit))
    val state = mutableState.asStateFlow()
    private var visible = false
    private val requests = WeatherRequestGate()
    private var job: Job? = null
    private var periodic: Job? = null

    init {
        viewModelScope.launch {
            environment.auth.collect {
                invalidate()
                mutableState.value = WeatherPageState(signedIn = it.status == AuthStatus.SIGNED_IN,
                    observing = visible, unit = state.value.unit, locationGranted = environment.locationGranted)
            }
        }
    }

    fun onStart() {
        visible = true
        requests.onStart()
        refreshAccess()
    }
    fun onStop() {
        visible = false
        requests.onStop()
        if (state.value.loading) cancel() else invalidate()
        mutableState.value = state.value.copy(observing = false, loading = false)
    }
    /** Re-read status after a system dialog/settings return; never acquire location or call Honda. */
    fun refreshAccess() {
        val signedIn = environment.auth.value.status == AuthStatus.SIGNED_IN && environment.uid != null
        val granted = environment.locationGranted
        if (!signedIn || !granted) {
            invalidate()
            mutableState.value = WeatherPageState(signedIn = signedIn, observing = visible,
                locationGranted = granted, unit = state.value.unit)
        } else mutableState.value = state.value.copy(signedIn = true, observing = visible, locationGranted = true)
    }
    private fun invalidate() { requests.cancel(); job?.cancel(); job = null; periodic?.cancel(); periodic = null }
    fun cancel() {
        invalidate()
        mutableState.value = if (state.value.current == null) state.value.copy(loading = false, errorCode = "CANCELLED")
            else state.value.copy(loading = false, forecastErrorCode = "CANCELLED")
    }
    fun setUnit(unit: TemperatureUnit) {
        environment.saveUnit(unit)
        mutableState.value = state.value.copy(unit = unit)
    }

    fun refresh() {
        if (!visible || state.value.loading) return
        invalidate()
        val uid = environment.uid
        val revision = environment.revision
        val signedIn = uid != null && environment.auth.value.status == AuthStatus.SIGNED_IN
        val granted = environment.locationGranted
        val ticket = requests.begin(signedIn, granted)
        mutableState.value = WeatherPageState(signedIn = signedIn, observing = true,
            locationGranted = granted, loading = ticket != null, unit = environment.unit,
            errorCode = if (!signedIn) "AUTH_REQUIRED" else if (!granted) "LOCATION_PERMISSION" else null)
        if (ticket == null) return
        fun currentIdentity() = requests.accepts(ticket) && environment.revision == revision &&
            environment.uid == uid && environment.auth.value.status == AuthStatus.SIGNED_IN
        // OEM60-minute refresh is a schedule, not a cache lifetime. Manual refresh restarts it.
        periodic = viewModelScope.launch {
            delay(60 * 60 * 1_000L)
            if (requests.mayRefreshAutomatically() && currentIdentity()) refresh()
        }
        val operation = environment.operation()
        val locale = environment.locale
        job = viewModelScope.launch {
            try {
                val location = environment.location()
                ensureActive()
                if (!currentIdentity()) return@launch
                mutableState.value = state.value.copy(lastKnownLocation = location.lastKnown, locationAgeMillis = location.ageMillis)
                ensureActive()
                if (!currentIdentity()) return@launch
                fun checkPermission() {
                    if (!currentIdentity()) throw CancellationException("Weather request is no longer active")
                    if (!environment.locationGranted) throw WeatherLocationException("LOCATION_PERMISSION")
                }
                environment.withReader(uid, revision, operation, ::checkPermission) { reader ->
                    val reading = reader.current(location, locale, ::checkPermission)
                    withContext(Dispatchers.Main) {
                        checkPermission()
                        mutableState.value = state.value.copy(current = reading, fetchedAtMillis = environment.nowMillis())
                    }
                    ensureActive()
                    val forecast = reader.forecast(location, locale, ::checkPermission)
                    withContext(Dispatchers.Main) {
                        checkPermission()
                        mutableState.value = state.value.copy(forecast = forecast, loading = false)
                    }
                }
            } catch (_: CancellationException) {
                environment.recordCancelled(operation)
            } catch (error: Exception) {
                val code = when (error) {
                    is WeatherLocationException -> error.code
                    is WeatherDataException -> "DATA_LOSS"
                    else -> HondaSessionRead.errorCode(error)
                }
                environment.recordFailure(operation, code)
                if (currentIdentity()) {
                    if (requests.onFailure(code)) invalidate()
                    mutableState.value = if (weatherFailureIsForecastOnly(state.value.current != null, code))
                        state.value.copy(loading = false, forecastErrorCode = code)
                        else state.value.copy(loading = false, current = null, forecast = null, errorCode = code, locationGranted = environment.locationGranted)
                }
            }
        }
    }

    fun openLocationSettings() {
        try {
            environment.openLocationSettings()
            mutableState.value = state.value.copy(settingsUnavailable = false)
        } catch (_: RuntimeException) { mutableState.value = state.value.copy(settingsUnavailable = true) }
    }
    override fun onCleared() { onStop(); super.onCleared() }
}
