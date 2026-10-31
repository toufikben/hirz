package dz.hirz.server.calendar

import java.time.LocalDate

/**
 * The Algerian calendar's effect on rules (design §12). Eid al-Adha is the peak of livestock theft, so livestock rules
 * are more sensitive for the two weeks before it.
 *
 * The dates are astronomical estimates of 10 Dhu al-Hijjah; the Ministry of Religious Affairs announces the real one
 * after the moon sighting, and it can differ by a day. A herd's owner can set the announced date, which wins.
 */
object AlgerianCalendar {
    private val EID_AL_ADHA = mapOf(
        2026 to LocalDate.of(2026, 5, 27),
        2027 to LocalDate.of(2027, 5, 16),
        2028 to LocalDate.of(2028, 5, 5),
        2029 to LocalDate.of(2029, 4, 24),
        2030 to LocalDate.of(2030, 4, 13),
    )

    /** How many days before Eid the livestock profile is raised. */
    const val SEASON_DAYS = 14L

    fun eidAlAdha(year: Int): LocalDate? = EID_AL_ADHA[year]

    /** True from [SEASON_DAYS] days before Eid al-Adha through the day itself; [override] is the owner's announced date. */
    fun inEidSeason(day: LocalDate, override: LocalDate?): Boolean {
        val estimate = eidAlAdha(day.year)
        // The owner's announced date is for one Eid: a date from another year (more than 3 days off) is ignored.
        val eid = override?.takeIf { estimate == null || kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(estimate, it)) <= 3 }
            ?: estimate ?: return false
        return !day.isBefore(eid.minusDays(SEASON_DAYS)) && !day.isAfter(eid)
    }

    /** Fixed national holidays: New Year, Yennayer, Labour Day, Independence Day, Revolution Day. */
    private val FIXED = listOf(1 to 1, 1 to 12, 5 to 1, 7 to 5, 11 to 1)

    /** Eid al-Fitr, estimated like Eid al-Adha (two days off each). */
    private val EID_AL_FITR = mapOf(
        2026 to LocalDate.of(2026, 3, 20), 2027 to LocalDate.of(2027, 3, 10), 2028 to LocalDate.of(2028, 2, 27),
        2029 to LocalDate.of(2029, 2, 15), 2030 to LocalDate.of(2030, 2, 5),
    )

    /**
     * School holidays, estimates of the Ministry of National Education's calendar (autumn, winter, spring, summer); a
     * circle corrects them with `calendar_overrides`.
     */
    private val SCHOOL_HOLIDAYS = listOf(
        LocalDate.of(2026, 6, 15) to LocalDate.of(2026, 9, 20),
        LocalDate.of(2026, 10, 29) to LocalDate.of(2026, 11, 7),
        LocalDate.of(2026, 12, 17) to LocalDate.of(2027, 1, 3),
        LocalDate.of(2027, 3, 18) to LocalDate.of(2027, 4, 4),
        LocalDate.of(2027, 6, 10) to LocalDate.of(2027, 9, 19),
        LocalDate.of(2027, 10, 28) to LocalDate.of(2027, 11, 6),
        LocalDate.of(2027, 12, 16) to LocalDate.of(2028, 1, 2),
        LocalDate.of(2028, 3, 16) to LocalDate.of(2028, 4, 2),
        LocalDate.of(2028, 6, 8) to LocalDate.of(2028, 9, 17),
    )

    fun isNationalHoliday(day: LocalDate): Boolean {
        if (FIXED.any { (m, d) -> day.monthValue == m && day.dayOfMonth == d }) return true
        val eids = listOfNotNull(EID_AL_FITR[day.year], eidAlAdha(day.year))
        return eids.any { !day.isBefore(it) && !day.isAfter(it.plusDays(1)) }
    }

    /**
     * Sunday to Thursday, not a national holiday, not in the school holidays, unless the circle said otherwise
     * ([overrides]: "school_day_off" / "school_day_on" with their date).
     */
    fun isSchoolDay(day: LocalDate, overrides: List<Pair<String, LocalDate>>): Boolean {
        if (overrides.any { it.first == "school_day_off" && it.second == day }) return false
        if (overrides.any { it.first == "school_day_on" && it.second == day }) return true
        if (day.dayOfWeek.value == 5 || day.dayOfWeek.value == 6) return false
        if (isNationalHoliday(day)) return false
        return SCHOOL_HOLIDAYS.none { (from, to) -> !day.isBefore(from) && !day.isAfter(to) }
    }
}
