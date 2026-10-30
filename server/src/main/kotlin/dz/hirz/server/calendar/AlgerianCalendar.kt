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
        val eid = override ?: eidAlAdha(day.year) ?: return false
        return !day.isBefore(eid.minusDays(SEASON_DAYS)) && !day.isAfter(eid)
    }
}
