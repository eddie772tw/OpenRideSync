package org.openridesync.app.weather

import org.openridesync.app.ble.Gen1Projection

internal object WeatherProjection {
    fun menu(state: RideWeatherState, title: String, loading: String, unavailable: String,
        first: Boolean, last: Boolean): Gen1Projection.Menu {
        val reading = state.current?.weather
        val busy = state.stage == RideWeatherStage.LOADING
        return Gen1Projection.Menu(17, 16, header = if (busy) loading else if (reading == null) unavailable else title,
            up = if (first) null else Gen1Projection.Arrow.UP,
            down = if (last) null else Gen1Projection.Arrow.DOWN,
            right = if (reading == null) null else Gen1Projection.Arrow.RIGHT_BIG,
            contentIcon = if (busy) 89 else reading?.let { icon(it.kind) } ?: 91,
            line1 = reading?.temperature(state.unit))
    }
    // Original WeatherForecastPage shows a forecast icon/cancel action while speaking the report.
    fun live(title: String, loading: Boolean) = Gen1Projection.Live(17, 18, header = title,
        contentIcon = if (loading) 89 else 90,
        left = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 1, 16))
    fun icon(kind: WeatherKind): Int = when (kind) {
        WeatherKind.SUNNY -> 10
        WeatherKind.CLEAR, WeatherKind.UNKNOWN -> 11
        WeatherKind.FEW_CLOUDS_DAY, WeatherKind.FEW_CLOUDS_NIGHT, WeatherKind.BROKEN_CLOUDS -> 13
        WeatherKind.SCATTERED_CLOUDS_DAY, WeatherKind.SCATTERED_CLOUDS_NIGHT -> 12
        WeatherKind.DRIZZLE, WeatherKind.RAIN -> 14
        WeatherKind.THUNDERSTORM -> 15
        WeatherKind.SNOW -> 16
        WeatherKind.ATMOSPHERE -> 17
    }
}
