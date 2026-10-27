package org.openridesync.app.weather

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.honda.wire.WeatherWire
import java.util.Locale

class ForecastCompletenessTest {
    private fun forecast(hour: Int) = WeatherWire.HourlyForecast.newBuilder().setHour(hour)
        .setForecast(WeatherWire.Forecast.newBuilder()
            .setMain(WeatherWire.Main.newBuilder().setTemp(hour.toFloat()))
            .addWeather(WeatherWire.WeatherCondition.newBuilder().setId(800).setDescription("Synthetic")))
        .build()
    private fun response(vararg hours: Int) = WeatherWire.WeatherForecast.newBuilder()
        .addAllForecasts(hours.map(::forecast)).build()
    private fun reject(vararg hours: Int) {
        assertThrows(WeatherDataException::class.java) { forecastWeather(response(*hours)) }
    }

    @Test fun exactRequestAndCompleteResponseShareTheConfirmedThreeHours() {
        val request = weatherForecastRequest(WeatherWire.Coord.newBuilder().setLat(0f).setLon(0f).build(), Locale.US)
        assertEquals(listOf("1", "3", "5"), request.intervalsList)
        assertEquals(listOf(1, 3, 5), forecastWeather(response(1, 3, 5)).hours.map { it.hour })
    }

    @Test fun everyLegalOrderingIsAcceptedAndOnlyDomainOrderIsNormalized() {
        for (hours in listOf(intArrayOf(1, 3, 5), intArrayOf(1, 5, 3), intArrayOf(3, 1, 5),
                intArrayOf(3, 5, 1), intArrayOf(5, 1, 3), intArrayOf(5, 3, 1))) {
            val raw = response(*hours)
            val value = forecastWeather(raw)
            assertSame(raw, value.raw)
            assertEquals(hours.toList(), value.raw.forecastsList.map { it.hour })
            assertEquals(listOf(1, 3, 5), value.hours.map { it.hour })
            assertEquals(listOf(1f, 3f, 5f), value.hours.map { it.weather.celsius })
        }
    }

    @Test fun emptyAndEveryNonemptyProperSubsetAreIncomplete() {
        for (hours in listOf(intArrayOf(), intArrayOf(1), intArrayOf(3), intArrayOf(5),
                intArrayOf(1, 3), intArrayOf(1, 5), intArrayOf(3, 5))) reject(*hours)
    }

    @Test fun duplicatesCannotSubstituteForAMissingPeriodOrAddAnotherReportLine() {
        reject(1, 1, 5)
        reject(1, 3, 3)
        reject(5, 5, 5)
        reject(1, 3, 5, 5)
        reject(1, 3, 5, 1, 3, 5)
    }

    @Test fun unrequestedAndInvalidHorizonsAreRejectedWithoutInterpolation() {
        for (hour in listOf(Int.MIN_VALUE, -1, 0, 2, 4, 6, 24, Int.MAX_VALUE)) {
            reject(1, 3, hour)
            reject(1, 3, 5, hour)
        }
    }

    @Test fun completeHoursDoNotBypassExistingPayloadValidation() {
        val valid = response(1, 3, 5)
        val first = valid.getForecasts(0)
        for (bad in listOf(first.toBuilder().clearForecast().build(),
                first.toBuilder().setForecast(first.forecast.toBuilder().clearMain()).build(),
                first.toBuilder().setForecast(first.forecast.toBuilder().clearWeather()).build(),
                first.toBuilder().setForecast(first.forecast.toBuilder()
                    .setMain(WeatherWire.Main.newBuilder().setTemp(Float.NaN))).build())) {
            assertThrows(WeatherDataException::class.java) { forecastWeather(valid.toBuilder().setForecasts(0, bad).build()) }
        }
    }

    @Test fun acceptedWirePayloadRetainsUnknownFields() {
        val raw = WeatherWire.WeatherForecast.parseFrom(response(5, 1, 3).toByteArray() + byteArrayOf(0x7a, 1, 120))
        val value = forecastWeather(raw)
        assertSame(raw, value.raw)
        assertTrue(value.raw.unknownFields.hasField(15))
        assertArrayEquals(raw.toByteArray(), value.raw.toByteArray())
    }

    @Test fun forecastFailurePolicyTargetsForecastOnlyWhenCurrentExists() {
        assertTrue(weatherFailureIsForecastOnly(hasCurrent = true, code = "DATA_LOSS"))
        assertFalse(weatherFailureIsForecastOnly(hasCurrent = false, code = "DATA_LOSS"))
    }

    @Test fun existingTransportAndLocationErrorPoliciesRemainDistinct() {
        for (code in listOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "UNAUTHENTICATED", "CANCELLED")) {
            assertTrue(weatherFailureIsForecastOnly(true, code))
            assertFalse(weatherFailureIsForecastOnly(false, code))
        }
        for (code in listOf("LOCATION_PERMISSION", "LOCATION_DISABLED", "LOCATION_UNAVAILABLE")) {
            assertFalse(weatherFailureIsForecastOnly(true, code))
            assertFalse(weatherFailureIsForecastOnly(false, code))
        }
    }
}
