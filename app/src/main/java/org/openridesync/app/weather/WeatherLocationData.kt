package org.openridesync.app.weather

import org.openridesync.honda.wire.WeatherWire

internal data class WeatherLocation(val latitude: Double, val longitude: Double, val lastKnown: Boolean, val ageMillis: Long?) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun coordinate(): WeatherWire.Coord = WeatherWire.Coord.newBuilder().setLat(latitude.toFloat()).setLon(longitude.toFloat()).build()
}
