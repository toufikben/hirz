package dz.hirz.app.guardian

import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.api.VehicleStateDto

/** What the vehicle card says and offers (design §12). Pure, so every language and refusal is tested. */
object VehicleLogic {
    private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

    private val THEFT = setOf(RuleId.ARMED_MOVEMENT, RuleId.TOWING)

    fun modeName(mode: VehicleMode, lang: String): String = when (mode) {
        VehicleMode.ARMED -> t(lang, "مسلّحة", "Armée", "Armed")
        VehicleMode.DISARMED -> t(lang, "غير مسلّحة", "Désarmée", "Disarmed")
        VehicleMode.AUTHORIZED -> t(lang, "قيادة مأذونة", "Conduite autorisée", "Authorized drive")
        VehicleMode.MAINTENANCE -> t(lang, "صيانة", "Entretien", "Maintenance")
    }

    private fun why(reason: String, lang: String): String? = when (reason) {
        "manual" -> t(lang, "يدويًا", "manuellement", "manually")
        "authorized" -> t(lang, "بإذن قيادة", "par autorisation", "by authorization")
        "schedule" -> t(lang, "حسب الجدول", "selon l'horaire", "by schedule")
        "auto_arm" -> t(lang, "تلقائيًا بعد الركن", "automatiquement après stationnement", "automatically after parking")
        "expired" -> t(lang, "بعد انتهاء الإذن", "après la fin de l'autorisation", "after the authorization ended")
        else -> null
    }

    /** "Armed — automatically after parking, 14 min ago": the mode, why, and since when. */
    fun modeLine(s: VehicleStateDto, nowMs: Long, lang: String): String {
        val parts = listOfNotNull(why(s.reason, lang), s.sinceMs?.let { Captions.age(nowMs - it, null, lang) })
        return if (parts.isEmpty()) modeName(s.mode, lang) else modeName(s.mode, lang) + " — " + parts.joinToString(", ")
    }

    /** The stop button shows only to the owner, during a theft (or with the vehicle missing), on a relay-capable tracker. */
    fun engineStopOffered(isOwner: Boolean, openRules: Set<RuleId>, missing: Boolean, engineCut: Boolean): Boolean =
        isOwner && engineCut && (missing || openRules.any { it in THEFT })

    fun confirmWord(lang: String): String = t(lang, "أوقف", "arrêter", "stop")

    /** The typed confirmation: the word for the current language, spaces and case ignored. */
    fun confirmMatches(input: String, lang: String): Boolean = input.trim().lowercase() == confirmWord(lang)

    /** The server's refusal, said plainly; an unknown code never reads as success. */
    fun refusal(reason: String?, lang: String): String = when (reason) {
        "not_owner" -> t(lang, "إيقاف المحرّك للمالك فقط", "Seul le propriétaire peut couper le moteur", "Only the owner can stop the engine")
        "no_theft_incident" -> t(lang, "لا يوجد إنذار سرقة مفتوح", "Aucune alerte de vol ouverte", "There is no open theft alert")
        "not_supported" -> t(lang, "جهاز التتبع لا يدعم إيقاف المحرّك", "Ce traceur ne peut pas couper le moteur", "This tracker cannot stop the engine")
        "device_offline" -> t(lang, "جهاز التتبع غير متصل الآن", "Le traceur n'est pas connecté", "The tracker is not connected right now")
        "stale_fix" -> t(lang, "آخر موقع قديم (أكثر من دقيقة)", "Dernière position trop ancienne (plus d'une minute)", "The last position is over a minute old")
        "too_fast" -> t(lang, "المركبة تسير بسرعة؛ الإيقاف خطر الآن", "Le véhicule roule trop vite ; couper est dangereux", "The vehicle is moving too fast to stop safely")
        "queued_withdrawn" -> t(lang, "انقطع الجهاز أثناء الإرسال؛ أُلغي الأمر", "Le traceur s'est déconnecté ; commande annulée", "The tracker dropped while sending; the command was cancelled")
        "traccar_unavailable" -> t(lang, "خادم الأجهزة غير متاح؛ حاول لاحقًا", "Serveur des traceurs indisponible ; réessayez", "The device server is unavailable; try again")
        else -> t(lang, "رُفض الأمر", "Commande refusée", "Command refused")
    }

    /** Night hours every day, 22:00–06:00 Algiers time, as two windows. */
    val NIGHT_PRESET = listOf(ActiveWindow((1..7).toSet(), 22 * 60, 1440), ActiveWindow((1..7).toSet(), 0, 6 * 60))
}
