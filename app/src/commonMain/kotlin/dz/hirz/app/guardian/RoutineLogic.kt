package dz.hirz.app.guardian

import dz.hirz.shared.api.DigestItem
import dz.hirz.shared.api.RoutineDto
import dz.hirz.shared.api.RoutineTemplate
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.asset.AssetType

private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

private fun clock(m: Int) = "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"

/** What the routine card says and offers (design §6.3). Pure, so it is tested. */
object RoutineLogic {
    /** "Ecole · Sun, Mon, … · by 08:00 · out 16:30": the Algerian week starts on Sunday. */
    fun label(r: RoutineDto, placeName: String, lang: String): String {
        val days = r.days.sortedBy { it % 7 }.joinToString(", ") { HerdLogic.dayName(it, lang) }
        val parts = buildList {
            add(placeName)
            if (days.isNotEmpty()) add(days)
            r.arriveByMin?.let { add(t(lang, "قبل ", "avant ", "by ") + clock(it)) }
            r.dismissalMin?.let { add(t(lang, "خروج ", "sortie ", "out ") + clock(it)) }
            if (!r.active) add(t(lang, "موقوف", "en pause", "paused"))
        }
        return parts.joinToString(" · ")
    }

    fun templatesFor(type: AssetType): List<RoutineTemplate> =
        if (type == AssetType.LIVESTOCK) listOf(RoutineTemplate.PASTURE_DAY, RoutineTemplate.BARN_NIGHT)
        else listOf(RoutineTemplate.SCHOOL, RoutineTemplate.MOSQUE, RoutineTemplate.LESSONS)

    fun templateName(tpl: RoutineTemplate, lang: String): String = when (tpl) {
        RoutineTemplate.SCHOOL -> t(lang, "المدرسة", "École", "School")
        RoutineTemplate.MOSQUE -> t(lang, "المسجد", "Mosquée", "Mosque")
        RoutineTemplate.LESSONS -> t(lang, "الدروس الخصوصية", "Cours", "Lessons")
        RoutineTemplate.PASTURE_DAY -> t(lang, "يوم المرعى", "Journée au pâturage", "Pasture day")
        RoutineTemplate.BARN_NIGHT -> t(lang, "ليلة الحظيرة", "Nuit à l'étable", "Barn night")
    }
}

/** One line of the evening digest. A condition rule arrives as PERSON_ALERT and is never named. */
object DigestLogic {
    fun line(item: DigestItem, lang: String): String {
        val rule = RuleId.entries.firstOrNull { it.name == item.rule }?.let { ProfileLogic.ruleName(it, lang) }
            ?: t(lang, "تنبيه", "Alerte", "Alert")
        return "${item.assetName} — $rule" + if (item.count > 1) " ×${item.count}" else ""
    }
}
