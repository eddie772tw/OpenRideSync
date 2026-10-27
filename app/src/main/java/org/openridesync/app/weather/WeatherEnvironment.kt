package org.openridesync.app.weather

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthState
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.honda.wire.WeatherWire
import java.util.Locale

/** Platform/service boundary. Tests substitute this port, never a parallel copy of page logic. */
internal interface WeatherEnvironment {
    val auth: StateFlow<AuthState>
    val uid: String?
    val revision: Long
    val locationGranted: Boolean
    val unit: TemperatureUnit
    val locale: Locale
    fun saveUnit(unit: TemperatureUnit)
    fun nowMillis(): Long
    fun operation(): String
    fun recordCancelled(operation: String)
    fun recordFailure(operation: String, code: String)
    fun openLocationSettings()
    suspend fun location(): WeatherLocation
    suspend fun withReader(uid: String?, revision: Long, operation: String, check: () -> Unit, block: suspend (WeatherPageReader) -> Unit)
}

internal interface WeatherPageReader {
    suspend fun current(location: WeatherLocation, locale: Locale, check: () -> Unit): CurrentWeather
    suspend fun forecast(location: WeatherLocation, locale: Locale, check: () -> Unit): ForecastWeather
}

internal class AndroidWeatherEnvironment(private val app: OpenRideSyncApplication) : WeatherEnvironment {
    private val session = AuthSession.get()
    private val preferences = app.getSharedPreferences("weather", Context.MODE_PRIVATE)
    init { if (!preferences.contains("unit")) saveUnit(weatherTemperatureUnit(app)) }
    override val auth get() = session.state
    override val uid get() = session.currentUser()?.uid
    override val revision get() = session.revision
    override val locationGranted get() = weatherLocationGranted(app)
    override val unit get() = weatherTemperatureUnit(app)
    override val locale get() = app.resources.configuration.locales[0]
    override fun saveUnit(unit: TemperatureUnit) { preferences.edit { putString("unit", unit.name) } }
    override fun nowMillis() = System.currentTimeMillis()
    override fun operation() = app.journal?.newOperation() ?: java.util.UUID.randomUUID().toString()
    override fun recordCancelled(operation: String) {
        app.journal?.record(Component.SYNC, operation, Event.STOP, Outcome.CANCELLED, ErrorCode.CANCELLED)
    }
    override fun recordFailure(operation: String, code: String) {
        app.journal?.record(Component.SYNC, operation, Event.STATE_CHANGE, Outcome.FAILED,
            if (code == "LOCATION_PERMISSION") ErrorCode.PERMISSION_DENIED
            else if (code.startsWith("LOCATION_")) ErrorCode.UNAVAILABLE else HondaSessionRead.diagnosticError(code))
    }
    override fun openLocationSettings() {
        app.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override suspend fun location() = weatherLocation(app)
    override suspend fun withReader(uid: String?, revision: Long, operation: String, check: () -> Unit, block: suspend (WeatherPageReader) -> Unit) {
        withContext(Dispatchers.IO) {
            check()
            HondaSessionRead(session, app.journal, operation, uid, revision).use { reader ->
                block(object : WeatherPageReader {
                    override suspend fun current(location: WeatherLocation, locale: Locale, check: () -> Unit) = runInterruptible {
                        check()
                        currentWeather(reader.read(Event.HONDA_GET_WEATHER) { client, key, token ->
                            check()
                            client.getWeather(WeatherWire.GetWeatherRequest.newBuilder().setCoord(location.coordinate())
                                .setLang(weatherLanguage(locale)).build(), key, token)
                        })
                    }
                    override suspend fun forecast(location: WeatherLocation, locale: Locale, check: () -> Unit) = runInterruptible {
                        check()
                        forecastWeather(reader.read(Event.HONDA_GET_WEATHER_FORECAST) { client, key, token ->
                            check()
                            client.getWeatherForecast(weatherForecastRequest(location.coordinate(), locale), key, token)
                        })
                    }
                })
            }
        }
    }
}
