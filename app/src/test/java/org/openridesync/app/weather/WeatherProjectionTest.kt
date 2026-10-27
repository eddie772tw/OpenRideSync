package org.openridesync.app.weather

import org.junit.Assert.*
import org.junit.Test

class WeatherProjectionTest {
    @Test fun absentWeatherUsesUnavailableAndForecastKeepsCancelAction() {
        val menu = WeatherProjection.menu(RideWeatherState(), "Weather", "Loading", "Unavailable", false, true)
        assertEquals(91, menu.contentIcon)
        assertNull(menu.line2)
        assertNotNull(menu.up)
        assertNull(menu.down)
        assertEquals(90, WeatherProjection.live("Forecast", false).contentIcon)
        assertEquals(1, WeatherProjection.live("Forecast", false).left.contentIcon)
        assertEquals(89, WeatherProjection.live("Forecast", true).contentIcon)
    }
}
