package dz.hirz.server.calendar

import dz.hirz.shared.api.LatLon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset for a place and date (design §12: night from the real sun, not a fixed hour), by the classic
 * almanac algorithm used by NOAA's simple calculator: within a few minutes, which is all a night window needs.
 */
object SolarTimes {
    data class Times(val sunrise: Instant, val sunset: Instant)

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun mod(x: Double, m: Double) = ((x % m) + m) % m

    /** UTC hour of sunrise ([rising]) or sunset on [day] at [at]; null in polar day or night (never in Algeria). */
    private fun utcHour(day: LocalDate, at: LatLon, rising: Boolean): Double? {
        val n = day.dayOfYear
        val lngHour = at.lon / 15.0
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = mod(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = mod(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(at.lat))) / (cosDec * cos(rad(at.lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val localT = h + ra - 0.06571 * t - 6.622
        return mod(localT - lngHour, 24.0)
    }

    fun of(day: LocalDate, at: LatLon): Times {
        val base = day.atStartOfDay().toInstant(ZoneOffset.UTC)
        fun instant(h: Double?) = base.plusSeconds(((h ?: 12.0) * 3600).toLong())
        return Times(instant(utcHour(day, at, true)), instant(utcHour(day, at, false)))
    }

    /** Night at [now] for a place: after sunset + [afterSunsetMin] or before sunrise − [beforeSunriseMin], Africa/Algiers dates. */
    fun isNight(now: Instant, at: LatLon, afterSunsetMin: Int = 0, beforeSunriseMin: Int = 0): Boolean {
        val today = now.atZone(java.time.ZoneId.of("Africa/Algiers")).toLocalDate()
        val t = of(today, at)
        return now.isAfter(t.sunset.plusSeconds(afterSunsetMin * 60L)) || now.isBefore(t.sunrise.minusSeconds(beforeSunriseMin * 60L))
    }
}
