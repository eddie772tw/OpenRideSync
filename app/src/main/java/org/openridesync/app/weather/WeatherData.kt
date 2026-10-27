package org.openridesync.app.weather

import org.openridesync.honda.wire.WeatherWire
import java.util.Locale
import kotlin.math.roundToInt

internal enum class TemperatureUnit { CELSIUS, FAHRENHEIT }
internal fun defaultTemperatureUnit(locale: Locale): TemperatureUnit =
    if (locale.country.uppercase(Locale.US) == "US") TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS

// One shared contract for the request and response; no inferred or interpolated periods.
internal val weatherForecastHours = listOf(1, 3, 5)
internal fun weatherForecastRequest(location: WeatherWire.Coord, locale: Locale): WeatherWire.GetWeatherForecastRequest =
    WeatherWire.GetWeatherForecastRequest.newBuilder().setCoord(location).setLang(weatherLanguage(locale))
        .addAllIntervals(weatherForecastHours.map(Int::toString)).build() // WeatherLoadingPage.Provider.N1.
internal enum class WeatherKind {
    SUNNY, CLEAR, FEW_CLOUDS_DAY, FEW_CLOUDS_NIGHT, SCATTERED_CLOUDS_DAY, SCATTERED_CLOUDS_NIGHT,
    BROKEN_CLOUDS, DRIZZLE, RAIN, THUNDERSTORM, SNOW, ATMOSPHERE, UNKNOWN,
}
internal class WeatherDataException : Exception("Invalid weather payload")
internal data class WeatherReading(val celsius: Float, val condition: Int, val place: String,
    val description: String, val main: String, val daytime: Boolean?) {
    fun temperature(unit: TemperatureUnit): String =
        if (unit == TemperatureUnit.CELSIUS) "${celsius.roundToInt()}°C"
        else "${(celsius * 1.8f + 32f).roundToInt()}°F"
    val kind: WeatherKind get() = when (condition) {
        in 200..299 -> WeatherKind.THUNDERSTORM
        in 300..399, in 520..599 -> WeatherKind.DRIZZLE
        in 500..504 -> WeatherKind.RAIN
        511, in 600..699 -> WeatherKind.SNOW
        in 700..799 -> WeatherKind.ATMOSPHERE
        800 -> when (daytime) { true -> WeatherKind.SUNNY; false -> WeatherKind.CLEAR; null -> WeatherKind.UNKNOWN }
        801 -> when (daytime) { true -> WeatherKind.FEW_CLOUDS_DAY; false -> WeatherKind.FEW_CLOUDS_NIGHT; null -> WeatherKind.UNKNOWN }
        802 -> when (daytime) { true -> WeatherKind.SCATTERED_CLOUDS_DAY; false -> WeatherKind.SCATTERED_CLOUDS_NIGHT; null -> WeatherKind.UNKNOWN }
        in 803..899 -> WeatherKind.BROKEN_CLOUDS
        else -> WeatherKind.UNKNOWN
    }
}
internal data class HourlyWeather(val hour: Int, val weather: WeatherReading)

/** Retain the wire response with its unknown fields; domain checks never turn missing Main into0°C. */
internal data class CurrentWeather(val raw: WeatherWire.Weather, val weather: WeatherReading)
internal data class ForecastWeather(val raw: WeatherWire.WeatherForecast, val hours: List<HourlyWeather>)

internal fun currentWeather(raw: WeatherWire.Weather): CurrentWeather {
    val day = if (!raw.hasSys()) null else {
        if (!raw.sys.sunrise.isFinite() || !raw.sys.sunset.isFinite() || raw.dt <= 0 ||
            raw.sys.sunrise <= 0 || raw.sys.sunset <= raw.sys.sunrise) null else {
            // OEM casts the legacy float timestamps to int and excludes both boundaries.
            raw.dt.toLong() >= raw.sys.sunrise.toInt().toLong() + 1 && raw.dt < raw.sys.sunset.toInt()
        }
    }
    return CurrentWeather(raw, weatherReading(raw.hasMain(), raw.main, raw.weatherList, raw.name, day))
}

internal fun forecastWeather(raw: WeatherWire.WeatherForecast): ForecastWeather {
    // A nonempty response alone does not establish a complete 1/3/5-hour report.
    // Reject missing, duplicate and unrequested periods before any display/speech succeeds.
    if (raw.forecastsCount != weatherForecastHours.size ||
        raw.forecastsList.map { it.hour }.toSet() != weatherForecastHours.toSet()) throw WeatherDataException()
    return ForecastWeather(raw, raw.forecastsList.sortedBy { it.hour }.map {
        val value = it.forecast
        HourlyWeather(it.hour, weatherReading(value.hasMain(), value.main, value.weatherList,
            raw.city.name, if (value.hasSys()) when (value.sys.pod) { "d" -> true; "n" -> false; else -> null } else null))
    })
}

private fun weatherReading(present: Boolean, main: WeatherWire.Main, conditions: List<WeatherWire.WeatherCondition>,
    name: String, daytime: Boolean?): WeatherReading {
    val condition = conditions.firstOrNull() ?: throw WeatherDataException()
    if (!present || !main.temp.isFinite() || !(main.temp * 1.8f + 32f).isFinite()) throw WeatherDataException()
    return WeatherReading(main.temp, condition.id, name, condition.description, condition.main, daytime)
}

/** SabWeatherProvider.u uses country only for zh; pt-BR stays pt and script-only zh-Hant stays zh_cn. */
internal fun weatherLanguage(locale: Locale): String {
    val code = when (locale.language) {
        "cs" -> "cz"
        "ko" -> "kr"
        "nb" -> "no"
        "zh" -> if (locale.country in setOf("TW", "HK", "MO")) "zh_tw" else "zh_cn"
        else -> locale.language
    }
    return code.takeIf { it in weatherLanguages } ?: "en"
}

private val weatherLanguages = setOf("af", "al", "ar", "az", "bg", "ca", "cz", "da", "de", "el", "en", "eu",
    "fa", "fi", "fr", "gl", "he", "hi", "hr", "hu", "id", "it", "ja", "kr", "la", "lt", "mk", "no", "nl",
    "pl", "pt", "pt_br", "ro", "ru", "sv", "se", "sk", "sl", "es", "sp", "sr", "th", "tr", "ua", "uk",
    "vi", "zh_cn", "zh_tw", "zu")
