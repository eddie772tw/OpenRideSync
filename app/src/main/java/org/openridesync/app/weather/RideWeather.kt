package org.openridesync.app.weather

import android.content.Context
import androidx.core.text.util.LocalePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.diagnostics.DiagnosticJournal

internal enum class RideWeatherStage { IDLE, LOADING, SPEAKING, COMPLETE, FAILED }
internal data class RideWeatherState(val stage: RideWeatherStage = RideWeatherStage.IDLE,
    val current: CurrentWeather? = null, val forecast: ForecastWeather? = null,
    val unit: TemperatureUnit = TemperatureUnit.CELSIUS, val error: String? = null)

/** One ride or explicit phone-test owner. No guessed location, provider fallback or retained token. */
internal class RideWeather(context: Context, journal: DiagnosticJournal?, private val operation: String,
    private val environment: WeatherEnvironment = AndroidWeatherEnvironment(context.applicationContext as OpenRideSyncApplication),
    private val speech: suspend (ForecastWeather, TemperatureUnit, () -> Boolean) -> Unit = { forecast, unit, valid ->
        speakWeatherReport(context, forecast, unit, journal, operation, valid)
    }, private val isCurrent: () -> Boolean) : AutoCloseable {
    private val uid = environment.uid
    private val revision = environment.revision
    private val mutableState = MutableStateFlow(RideWeatherState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private fun valid(attempt: Long) = !closed && generation == attempt && isCurrent() && uid != null &&
        environment.uid == uid && environment.revision == revision && environment.auth.value.status == AuthStatus.SIGNED_IN

    suspend fun report() = withContext(Dispatchers.Main.immediate) {
        if (task != null || closed) return@withContext
        val owner = currentCoroutineContext().job
        val attempt = ++generation
        task = owner
        fun checkPermission() {
            owner.ensureActive()
            if (!valid(attempt)) throw CancellationException()
            if (!environment.locationGranted) throw WeatherLocationException("LOCATION_PERMISSION")
        }
        try {
            checkPermission()
            val unit = environment.unit
            mutableState.value = RideWeatherState(RideWeatherStage.LOADING, unit = unit)
            val location = environment.location()
            checkPermission()
            val locale = environment.locale
            var data: Pair<CurrentWeather, ForecastWeather>? = null
            environment.withReader(uid, revision, operation, ::checkPermission) { reader ->
                checkPermission()
                val current = reader.current(location, locale, ::checkPermission)
                checkPermission()
                val forecast = reader.forecast(location, locale, ::checkPermission)
                checkPermission()
                data = current to forecast
            }
            checkPermission()
            val observed = requireNotNull(data)
            mutableState.value = RideWeatherState(RideWeatherStage.SPEAKING, observed.first, observed.second, unit)
            speech(observed.second, unit) { valid(attempt) && owner.isActive && environment.locationGranted }
            checkPermission()
            mutableState.value = state.value.copy(stage = RideWeatherStage.COMPLETE)
        } catch (cancelled: CancellationException) {
            if (generation == attempt) mutableState.value = RideWeatherState()
            throw cancelled
        } catch (error: Exception) {
            if (generation == attempt) {
                if (valid(attempt)) mutableState.value = state.value.copy(stage = RideWeatherStage.FAILED, error = when (error) {
                    is WeatherLocationException -> error.code
                    is WeatherDataException -> "DATA_LOSS"
                    is org.openridesync.app.runtime.RideSpeechFailure -> error.code.name
                    else -> HondaSessionRead.errorCode(error)
                }) else mutableState.value = RideWeatherState()
            }
        } finally { if (task === owner) task = null }
    }
    fun cancel() {
        generation++
        mutableState.value = RideWeatherState()
        val previous = task
        task = null
        previous?.cancel()
    }
    override fun close() { closed = true; cancel() }
}

/** Explicit per-app selection wins; otherwise Android regional temperature preference is inherited. */
internal fun weatherTemperatureUnit(context: Context): TemperatureUnit {
    val saved = context.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("unit", null)
    TemperatureUnit.entries.firstOrNull { it.name == saved }?.let { return it }
    return if (LocalePreferences.getTemperatureUnit() == LocalePreferences.TemperatureUnit.FAHRENHEIT)
        TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS
}
