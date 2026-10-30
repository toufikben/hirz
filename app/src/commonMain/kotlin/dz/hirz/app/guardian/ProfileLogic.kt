package dz.hirz.app.guardian

import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.RuleSettingDto
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.Condition

/** What the profile screen shows and allows (design §4.4). The condition is named here and nowhere else. */
object ProfileLogic {
    private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

    /** A default-P0 rule has no off switch: nobody may switch it off. */
    fun showSwitch(s: RuleSettingDto): Boolean = !s.locked

    /** Tiers on offer: the owner may pick any; a guardian may only keep or raise (P0 is the most urgent). */
    fun tierChoices(s: RuleSettingDto, isOwner: Boolean): List<Tier> =
        if (isOwner) Tier.entries.toList() else Tier.entries.filter { it.ordinal <= s.tier.ordinal }

    /** "22:30" → minutes from midnight; null when not a valid time. */
    fun parseClock(text: String): Int? {
        val (h, m) = text.trim().split(':').takeIf { it.size == 2 }?.map { it.toIntOrNull() ?: return null } ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    fun conditionLabel(c: Condition, lang: String): String = when (c) {
        Condition.NONE -> t(lang, "بلا حالة خاصة", "Aucune condition", "No condition")
        Condition.DEMENTIA -> t(lang, "زهايمر أو تيه", "Alzheimer ou désorientation", "Dementia or wandering")
        Condition.AUTISM -> t(lang, "توحد", "Autisme", "Autism")
    }

    fun ruleName(r: RuleId, lang: String): String = when (r) {
        RuleId.SOS -> t(lang, "زر الاستغاثة", "Bouton SOS", "SOS button")
        RuleId.DURESS -> t(lang, "رمز الإكراه", "Code de contrainte", "Duress PIN")
        RuleId.PLACE_EXIT -> t(lang, "الخروج من مكان", "Sortie d'un lieu", "Leaving a place")
        RuleId.FORBIDDEN_ENTRY -> t(lang, "دخول مكان ممنوع", "Entrée dans un lieu interdit", "Entering a forbidden place")
        RuleId.SILENT_SOURCE -> t(lang, "انقطاع الجهاز", "Appareil silencieux", "Device gone silent")
        RuleId.NO_RESPONSE_CHECKIN -> t(lang, "لا رد على الاطمئنان", "Pas de réponse au check-in", "No answer to a check-in")
        RuleId.ARMED_MOVEMENT -> t(lang, "تحرك المركبة المسلّحة", "Véhicule armé déplacé", "Armed vehicle moved")
        RuleId.TOWING -> t(lang, "سحب المركبة", "Remorquage", "Towing")
        RuleId.POWER_CUT -> t(lang, "قطع التغذية", "Alimentation coupée", "Power cut")
        RuleId.JAMMING -> t(lang, "تشويش", "Brouillage", "Jamming")
        RuleId.PARKED_TILT -> t(lang, "اهتزاز المركبة المركونة", "Vibration à l'arrêt", "Tilt while parked")
        RuleId.DOOR_OPEN_ARMED -> t(lang, "فتح باب والمركبة مسلّحة", "Portière ouverte armé", "Door opened while armed")
        RuleId.ANTENNA_CUT -> t(lang, "قطع الهوائي", "Antenne coupée", "Antenna cut")
        RuleId.HAZARD_PROXIMITY -> t(lang, "دخول منطقة خطر", "Entrée dans une zone dangereuse", "Entering a hazard")
        RuleId.NIGHT_EXIT -> t(lang, "الخروج ليلًا", "Sortie de nuit", "Leaving home at night")
        RuleId.RUN_AWAY_SPEED -> t(lang, "الركض مبتعدًا عن البيت", "Fuite en courant", "Running away from home")
        RuleId.PAST_ADDRESS_HEADING -> t(lang, "التوجه نحو عنوان قديم", "Vers une ancienne adresse", "Heading to a past address")
        RuleId.CURFEW -> t(lang, "خارج البيت بعد الموعد", "Dehors après l'heure", "Out after curfew")
        RuleId.COASTAL_NIGHT -> t(lang, "الشاطئ ليلًا", "Le rivage la nuit", "The shore at night")
    }
}
