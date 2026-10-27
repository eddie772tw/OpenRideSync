package org.openridesync.app.weather

import android.content.Context
import kotlinx.coroutines.*
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.runtime.*
import org.openridesync.app.R
import org.openridesync.diagnostics.DiagnosticJournal

internal fun weatherReportLines(context: Context, forecast: ForecastWeather, unit: TemperatureUnit): List<String> =
    listOf(context.getString(R.string.weather_report_intro, forecast.hours.first().weather.place)) +
        forecast.hours.map { context.getString(R.string.weather_report_line,
            context.resources.getQuantityString(R.plurals.weather_report_hours, it.hour, it.hour),
            it.weather.description, it.weather.temperature(unit)) }

internal suspend fun speakWeatherReport(context: Context, forecast: ForecastWeather, unit: TemperatureUnit,
    journal: DiagnosticJournal?, operation: String, valid: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
    val mode = (context.applicationContext as? OpenRideSyncApplication)?.ridePreferences?.weatherSpeech?.value
        ?: WeatherSpeechMode.FULL
    currentCoroutineContext().ensureActive()
    if (!valid()) throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
    // OFF intentionally avoids both a TTS engine and audio focus. This setting applies only to weather.
    if (mode != WeatherSpeechMode.OFF)
        speakRideLines(context, weatherSpeechLines(weatherReportLines(context, forecast, unit), mode), journal, operation, valid)
}
