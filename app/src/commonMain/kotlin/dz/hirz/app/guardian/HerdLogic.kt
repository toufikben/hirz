package dz.hirz.app.guardian

import dz.hirz.shared.api.ActiveWindow

/** What the herd card says and builds (design §1 groups, §12 market days). Pure, so it is tested. */
object HerdLogic {
    private val DAYS = mapOf(
        "ar" to listOf("الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد"),
        "fr" to listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"),
        "en" to listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
    )

    /** One weekly market window, ISO day 1–7 and "HH:MM" times; null when it is not a valid window. */
    fun marketWindow(day: Int, from: String, to: String): List<ActiveWindow>? {
        if (day !in 1..7) return null
        val start = ProfileLogic.parseClock(from) ?: return null
        val end = ProfileLogic.parseClock(to) ?: return null
        if (start >= end) return null
        return listOf(ActiveWindow(setOf(day), start, end))
    }

    private fun clock(m: Int) = "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"

    fun marketLabel(w: ActiveWindow, lang: String): String {
        val names = DAYS[lang] ?: DAYS.getValue("en")
        val days = w.daysOfWeek.sorted().joinToString(", ") { names[it - 1] }
        return "$days ${clock(w.startMinute)}–${clock(w.endMinute)}"
    }

    fun dayName(day: Int, lang: String): String = (DAYS[lang] ?: DAYS.getValue("en"))[day - 1]
}
