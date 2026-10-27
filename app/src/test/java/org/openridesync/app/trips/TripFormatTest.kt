package org.openridesync.app.trips

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class TripFormatTest {
    @Test fun wireUnitsAndOemDisplayBoundaries() {
        assertEquals("1.2 km", TripFormat.distance(1299f, locale = Locale.US))
        assertEquals("1 km", TripFormat.distance(1999f, summary = true, locale = Locale.US))
        assertEquals("100.0 千 km", TripFormat.distance(100000000f, summary = true, locale = Locale.US))
        assertEquals("36 km/h", TripFormat.speed(10f, Locale.US))
        assertEquals("999 km/h", TripFormat.speed(1000f, Locale.US))
        assertEquals("未提供", TripFormat.speed(Float.NaN))
        assertEquals("00:01", TripFormat.duration(119))
        assertEquals("99:59", TripFormat.duration(360000))
        assertEquals("100 小時", TripFormat.duration(360000, summary = true))
        assertEquals("0", TripFormat.duration(59, summary = true))
        assertEquals("未提供", TripFormat.duration(-1L, summary = true))
        assertEquals("99,999 小時", TripFormat.duration(Long.MAX_VALUE, summary = true))
        // Month grouping follows local trip start time, including UTC month boundaries.
        assertEquals(YearMonth.of(2026, 2), TripFormat.monthKey(
            Instant.parse("2026-01-31T18:00:00Z").epochSecond, ZoneId.of("Asia/Taipei")))
    }
}
