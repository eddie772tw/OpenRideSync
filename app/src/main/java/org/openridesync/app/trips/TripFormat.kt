package org.openridesync.app.trips

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/** Wire distances are metres, speed is metres/second, timestamps and durations are seconds. */
internal object TripFormat {
    fun distance(metres: Float, summary: Boolean = false, locale: Locale = Locale.getDefault()): String {
        if (!metres.isFinite() || metres < 0) return "未提供"
        val km = metres.toDouble() / 1000
        val value = if (summary) {
            if (km < 100000) number(floor(km), 0, locale)
            else number(floor(km.coerceAtMost(999999.0) / 1000 * 10) / 10, 1, locale) + " 千"
        } else number((floor(km * 10) / 10).coerceAtMost(9999.9), 1, locale)
        return "$value km"
    }

    fun speed(metresPerSecond: Float, locale: Locale = Locale.getDefault()): String =
        if (!metresPerSecond.isFinite() || metresPerSecond < 0) "未提供"
        else number((metresPerSecond.toDouble() * 3.6).coerceAtMost(999.0), 0, locale) + " km/h"

    fun duration(seconds: Long, summary: Boolean = false): String {
        if (seconds < 0) return "未提供"
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        if (summary && hours == 0L && minutes == 0L) return "0"
        if (summary && hours >= 100) return String.format(Locale.US, "%,d", hours.coerceAtMost(99999)) + " 小時"
        if (!summary && hours >= 100) return "99:59"
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes)
    }

    fun date(seconds: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(), includeYear: Boolean = false): String =
        Instant.ofEpochSecond(seconds).atZone(zone).format(DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(locale, if (includeYear) "yMMMd" else "MMMd"), locale))

    fun month(seconds: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochSecond(seconds).atZone(zone).format(DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale))

    fun monthKey(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): java.time.YearMonth =
        java.time.YearMonth.from(Instant.ofEpochSecond(seconds).atZone(zone))

    fun timeRange(start: Long, end: Long, is24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val first = Instant.ofEpochSecond(start).atZone(zone)
        val last = Instant.ofEpochSecond(end).atZone(zone)
        val date = if (first.toLocalDate() == last.toLocalDate()) "" else if (first.year == last.year) "MMMd" else "yMMMd"
        return android.icu.text.DateIntervalFormat.getInstance(date + if (is24Hour) "Hm" else "hm", locale).apply {
            timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
        }.format(android.icu.util.DateInterval(first.toInstant().toEpochMilli(), last.toInstant().toEpochMilli()))
    }

    private fun number(value: Double, digits: Int, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = digits; maximumFractionDigits = digits
    }.format(value)
}
