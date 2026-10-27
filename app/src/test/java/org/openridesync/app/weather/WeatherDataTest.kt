package org.openridesync.app.weather

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.honda.wire.WeatherWire
import java.util.Locale

class WeatherDataTest {
    private fun main(temp: Float) = WeatherWire.Main.newBuilder().setTemp(temp)
    private fun condition(id: Int = 800) = WeatherWire.WeatherCondition.newBuilder().setId(id).setDescription("Clear")
    private fun current(temp: Float = 25f) = WeatherWire.Weather.newBuilder().setMain(main(temp)).addWeather(condition())
        .setName("Synthetic place").setDt(150).setSys(WeatherWire.Sys.newBuilder().setSunrise(100f).setSunset(200f)).build()

    @Test fun celsiusIsNotKelvinAndSunriseSunsetAreExclusiveWithUnknownFieldsRetained() {
        val raw = WeatherWire.Weather.parseFrom(current().toByteArray() + byteArrayOf(0x7a, 1, 120))
        val value = currentWeather(raw)
        assertSame(raw, value.raw)
        assertTrue(value.raw.unknownFields.hasField(15))
        assertEquals("25°C", value.weather.temperature(TemperatureUnit.CELSIUS))
        assertEquals("77°F", value.weather.temperature(TemperatureUnit.FAHRENHEIT))
        assertEquals("0°C", currentWeather(current(-0.5f)).weather.temperature(TemperatureUnit.CELSIUS))
        assertTrue(value.weather.daytime == true)
        for (time in listOf(100, 200)) assertEquals(false, currentWeather(raw.toBuilder().setDt(time).build()).weather.daytime)
        assertEquals(true, currentWeather(raw.toBuilder().setDt(101).build()).weather.daytime)
        val unknownDay = currentWeather(raw.toBuilder().clearSys().build()).weather
        assertNull(unknownDay.daytime); assertEquals(WeatherKind.UNKNOWN, unknownDay.kind)
        assertNull(currentWeather(raw.toBuilder().setSys(WeatherWire.Sys.getDefaultInstance()).build()).weather.daytime)
        assertNull(currentWeather(raw.toBuilder().setSys(raw.sys.toBuilder().setSunrise(Float.NaN)).build()).weather.daytime)
        assertEquals("0°C", currentWeather(current(0f)).weather.temperature(TemperatureUnit.CELSIUS))
    }

    @Test fun malformedResponsesAreNotSuccessAndForecastHoursRetainSortedRawPayload() {
        for (bad in listOf(WeatherWire.Weather.getDefaultInstance(), current().toBuilder().clearMain().build(),
            current().toBuilder().clearWeather().build(), current(Float.NaN), current(Float.POSITIVE_INFINITY))) {
            assertThrows(WeatherDataException::class.java) { currentWeather(bad) }
        }
        val forecast = WeatherWire.Forecast.newBuilder().setMain(main(12f)).addWeather(condition(511))
            .setSys(WeatherWire.Sys.newBuilder().setPod("d")).build()
        val raw = WeatherWire.WeatherForecast.newBuilder().setCity(WeatherWire.City.newBuilder().setName("Synthetic city"))
            .addForecasts(WeatherWire.HourlyForecast.newBuilder().setHour(5).setForecast(forecast))
            .addForecasts(WeatherWire.HourlyForecast.newBuilder().setHour(1).setForecast(forecast))
            .addForecasts(WeatherWire.HourlyForecast.newBuilder().setHour(3).setForecast(forecast)).build()
        val mapped = forecastWeather(raw)
        assertSame(raw, mapped.raw); assertEquals(listOf(1, 3, 5), mapped.hours.map { it.hour })
        assertEquals(WeatherKind.SNOW, mapped.hours[0].weather.kind)
        assertEquals("Synthetic city", mapped.hours[0].weather.place)
        assertThrows(WeatherDataException::class.java) { forecastWeather(WeatherWire.WeatherForecast.getDefaultInstance()) }
        assertThrows(WeatherDataException::class.java) { forecastWeather(raw.toBuilder().setForecasts(0,
            WeatherWire.HourlyForecast.getDefaultInstance()).build()) }
        for ((id, kind) in mapOf(299 to WeatherKind.THUNDERSTORM, 399 to WeatherKind.DRIZZLE,
            504 to WeatherKind.RAIN, 505 to WeatherKind.UNKNOWN, 520 to WeatherKind.DRIZZLE,
            701 to WeatherKind.ATMOSPHERE, 899 to WeatherKind.BROKEN_CLOUDS, 900 to WeatherKind.UNKNOWN))
            assertEquals(kind, mapped.hours[0].weather.copy(condition = id).kind)
    }

    @Test fun providerLanguageUsesExactOemAliasesAndChineseCountryRule() {
        for ((locale, expected) in mapOf("zh-TW" to "zh_tw", "zh-HK" to "zh_tw", "zh-MO" to "zh_tw",
            "zh-Hant" to "zh_cn", "zh-SG" to "zh_cn", "cs-CZ" to "cz", "ko-KR" to "kr", "nb-NO" to "no",
            "pt-BR" to "pt", "ja-JP" to "ja", "tl-PH" to "en"))
            assertEquals(expected, weatherLanguage(Locale.forLanguageTag(locale)))
    }

    @Test fun requestUsesActualCoordinatesAndOemIntervalsAndRegionDefaults() {
        val coordinate = WeatherLocation(0.0, 0.0, false, 0).coordinate()
        val request = weatherForecastRequest(coordinate, Locale.TAIWAN)
        assertEquals(coordinate, request.coord)
        assertEquals(listOf("1", "3", "5"), request.intervalsList)
        assertEquals("zh_tw", request.lang)
        assertEquals(TemperatureUnit.FAHRENHEIT, defaultTemperatureUnit(Locale.US))
        for (locale in listOf(Locale.TAIWAN, Locale.UK, Locale.ROOT))
            assertEquals(TemperatureUnit.CELSIUS, defaultTemperatureUnit(locale))
        for ((lat, lon) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, 90.01 to 0.0, 0.0 to -180.01))
            assertThrows(IllegalArgumentException::class.java) { WeatherLocation(lat, lon, false, 0) }
    }
}
