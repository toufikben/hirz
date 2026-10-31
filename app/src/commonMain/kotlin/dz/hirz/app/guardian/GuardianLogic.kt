package dz.hirz.app.guardian

import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.rules.Freshness
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ColorRole { FRESH, STALE, SILENT }

/** How an asset marker looks. Freshness is never told by colour alone (design §6.1, accessibility). */
data class MarkerStyle(val color: ColorRole, val hollow: Boolean, val glyph: String?)

fun markerStyle(f: Freshness): MarkerStyle = when (f) {
    Freshness.FRESH -> MarkerStyle(ColorRole.FRESH, hollow = false, glyph = null)
    Freshness.STALE -> MarkerStyle(ColorRole.STALE, hollow = true, glyph = null)
    Freshness.SILENT -> MarkerStyle(ColorRole.SILENT, hollow = true, glyph = "?")
}

/** "55 s ago ±25 m": every position on screen carries its age and accuracy (design §4, v2 §4). */
object Captions {
    fun age(ageMs: Long?, accuracyM: Double?, lang: String): String {
        if (ageMs == null) return when (lang) { "ar" -> "لا بيانات"; "fr" -> "Aucune donnée"; else -> "No data" }
        val seconds = (ageMs / 1000).coerceAtLeast(0)
        val (n, unitAr, unitFr, unitEn) = when {
            seconds < 60 -> listOf(seconds.toString(), "ث", "s", "s")
            seconds < 3600 -> listOf((seconds / 60).toString(), "د", "min", "min")
            else -> listOf((seconds / 3600).toString(), "سا", "h", "h")
        }
        val acc = accuracyM?.let { " ±${it.roundToInt()} " + if (lang == "ar") "م" else "m" }.orEmpty()
        return when (lang) {
            "ar" -> "منذ $n $unitAr$acc"
            "fr" -> "il y a $n $unitFr$acc"
            else -> "$n $unitEn ago$acc"
        }
    }
}

/** Renders a server reason code; unknown codes fall back to a neutral line, never to an alarming one. */
object ReasonText {
    /** The reason, plus "reported N min late" when it came from a stored track (P6). */
    fun render(r: Reason, lang: String): String {
        val base = renderBase(r, lang)
        val late = r.args["lateMin"] ?: return base
        return base + when (lang) { "ar" -> " — وصل متأخرًا بـ $late د"; "fr" -> " — reçu avec $late min de retard"; else -> " — reported $late min late" }
    }

    private fun renderBase(r: Reason, lang: String): String {
        val a = r.args
        fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
        return when (r.code) {
            "sos" -> t("ضغط زر الاستغاثة", "Bouton SOS pressé", "SOS button pressed")
            "checkin_help" -> t("أجاب «أحتاج مساعدة»", "A répondu « besoin d'aide »", "Answered \"I need help\"")
            "duress" -> t("رمز الإكراه أُدخل (إنذار صامت)", "Code de contrainte saisi (alerte silencieuse)", "Duress PIN entered (silent alert)")
            "left_place" -> t(
                "خرج من «${a["place"]}» — ${a["readings"]} قراءات، ${a["distanceM"]} م خارج الحد، دقة ±${a["accuracyM"]} م، منذ ${a["ageS"]} ث",
                "A quitté « ${a["place"]} » — ${a["readings"]} relevés, ${a["distanceM"]} m au-delà, ±${a["accuracyM"]} m, il y a ${a["ageS"]} s",
                "Left \"${a["place"]}\" — ${a["readings"]} readings, ${a["distanceM"]} m outside, ±${a["accuracyM"]} m, ${a["ageS"]} s ago",
            )
            "entered_place" -> t(
                "دخل «${a["place"]}» — ${a["readings"]} قراءات، دقة ±${a["accuracyM"]} م",
                "Est entré dans « ${a["place"]} » — ${a["readings"]} relevés, ±${a["accuracyM"]} m",
                "Entered \"${a["place"]}\" — ${a["readings"]} readings, ±${a["accuracyM"]} m",
            )
            "silent_dead" -> t("لا إشارة من الجهاز منذ ${a["minutes"]} د", "Aucun signal de l'appareil depuis ${a["minutes"]} min", "No signal from the device for ${a["minutes"]} min")
            "silent_alive" -> t("الجهاز يعمل لكن بلا موقع منذ ${a["minutes"]} د", "Appareil actif mais sans position depuis ${a["minutes"]} min", "Device alive but no position for ${a["minutes"]} min")
            "silence_during_incident" -> t("انقطاع الإشارة أثناء الحادث (${a["minutes"]} د)", "Signal perdu pendant l'incident (${a["minutes"]} min)", "Signal lost during the incident (${a["minutes"]} min)")
            "armed_moved" -> t(
                "المركبة المسلّحة تحرّكت ${a["distanceM"]} م (${a["speedKmh"]} كم/سا)",
                "Véhicule armé déplacé de ${a["distanceM"]} m (${a["speedKmh"]} km/h)",
                "Armed vehicle moved ${a["distanceM"]} m (${a["speedKmh"]} km/h)",
            )
            "armed_ignition_on" -> t("تشغيل المحرّك والمركبة مسلّحة", "Contact mis alors que le véhicule est armé", "Ignition on while armed")
            "towed" -> t(
                "المركبة تتحرّك والمحرّك مطفأ (سحب؟) ${a["distanceM"]} م",
                "Véhicule déplacé moteur coupé (remorquage ?) ${a["distanceM"]} m",
                "Vehicle moving with the engine off (towed?) ${a["distanceM"]} m",
            )
            "power_cut" -> t("قُطعت تغذية جهاز التتبع", "Alimentation du traceur coupée", "Tracker power was cut")
            "jamming" -> t(
                "انقطاع الإشارة والمحرّك يعمل منذ ${a["silentS"]} ث (تشويش محتمل)",
                "Plus de signal moteur en marche depuis ${a["silentS"]} s (brouillage possible)",
                "No signal with the engine running for ${a["silentS"]} s (possible jamming)",
            )
            "jamming_alarm" -> t("جهاز التتبع أبلغ عن تشويش", "Le traceur signale un brouillage", "Tracker reported jamming")
            "parked_tilt" -> t("اهتزاز أو إمالة والمركبة مركونة ومسلّحة", "Vibration ou inclinaison du véhicule armé à l'arrêt", "Vibration or tilt while parked and armed")
            "door_open_armed" -> t("فُتح باب والمركبة مسلّحة", "Portière ouverte alors que le véhicule est armé", "Door opened while armed")
            "antenna_cut" -> t("قُطع هوائي GPS", "Antenne GPS coupée", "GPS antenna cut")
            "night_exit" -> t("خرج من البيت ليلًا", "Sorti de la maison la nuit", "Left home at night")
            "night_exit_beyond" -> t(
                "تجاوز «${a["place"]}» بمسافة ${a["distanceM"]} م من البيت ليلًا",
                "Au-delà de « ${a["place"]} », à ${a["distanceM"]} m de la maison, la nuit",
                "Went past \"${a["place"]}\", ${a["distanceM"]} m from home, at night",
            )
            "hazard_entered" -> t(
                "دخل منطقة خطر «${a["place"]}» — ${a["readings"]} قراءات، دقة ±${a["accuracyM"]} م",
                "Entré dans la zone dangereuse « ${a["place"]} » — ${a["readings"]} relevés, ±${a["accuracyM"]} m",
                "Entered the hazard \"${a["place"]}\" — ${a["readings"]} readings, ±${a["accuracyM"]} m",
            )
            "running_away" -> t(
                "يبتعد عن البيت بسرعة ${a["speedKmh"]} كم/سا (${a["distanceM"]} م)",
                "S'éloigne de la maison à ${a["speedKmh"]} km/h (${a["distanceM"]} m)",
                "Moving away from home at ${a["speedKmh"]} km/h (${a["distanceM"]} m)",
            )
            "heading_past_address" -> t(
                "يتجه نحو «${a["place"]}» (باقي ${a["distanceM"]} م)",
                "Se dirige vers « ${a["place"]} » (encore ${a["distanceM"]} m)",
                "Heading toward \"${a["place"]}\" (${a["distanceM"]} m left)",
            )
            "curfew" -> t("خارج البيت بعد الساعة ${a["curfew"]}", "Dehors après ${a["curfew"]}", "Out after ${a["curfew"]}")
            "coastal_night" -> t("عند «${a["place"]}» ليلًا", "À « ${a["place"]} » la nuit", "At \"${a["place"]}\" at night")
            "herd_night_move" -> t(
                "قطيع «${a["herd"]}» خارج الحظيرة ليلًا (${a["out"]} من ${a["heads"]})",
                "Troupeau « ${a["herd"]} » hors de l'enclos la nuit (${a["out"]} sur ${a["heads"]})",
                "Herd \"${a["herd"]}\" out of the barn at night (${a["out"]} of ${a["heads"]})",
            )
            "herd_dispersed" -> t(
                "تشتت القطيع فجأة (${a["spreadM"]} م، ${a["heads"]} رأس)",
                "Troupeau soudain dispersé (${a["spreadM"]} m, ${a["heads"]} têtes)",
                "Herd suddenly scattered (${a["spreadM"]} m, ${a["heads"]} heads)",
            )
            "animal_still" -> t("رأس ساكن منذ ${a["hours"]} ساعات (مرض؟)", "Animal immobile depuis ${a["hours"]} h (malade ?)", "Animal still for ${a["hours"]} h (sick?)")
            "collar_cut" -> t("نُزع الطوق أو قُطع", "Collier retiré ou coupé", "Collar removed or cut")
            "truck_speed" -> t("رأس يسير بسرعة مركبة (${a["speedKmh"]} كم/سا)", "Animal à vitesse de véhicule (${a["speedKmh"]} km/h)", "Animal moving at vehicle speed (${a["speedKmh"]} km/h)")
            "leash_break" -> t(
                "ابتعد عن مرافقه ${a["distanceM"]} م منذ ${a["seconds"]} ث",
                "Séparé de l'accompagnant : ${a["distanceM"]} m depuis ${a["seconds"]} s",
                "Separated from the guardian: ${a["distanceM"]} m for ${a["seconds"]} s",
            )
            "keyless_no_owner" -> t("شُغّل المحرّك ولا هاتف من العائلة قربه", "Moteur démarré sans téléphone de la famille à proximité", "Engine started with no family phone near")
            else -> t("حدث", "Événement", "Event")
        }
    }
}

data class CircleDraft(val center: LatLon, val radiusM: Double, val marginM: Int) {
    val isValid get() = radiusM >= 30.0 && marginM >= 50
    fun shape() = ShapeDto.Circle(center, radiusM)
}

data class PolygonDraft(val points: List<LatLon>) {
    val isValid get() = points.distinct().size >= 3
    fun shape() = ShapeDto.Polygon(points)
}

/** A closed ring of [points] + 1 coordinates, [radiusM] around [center] (equirectangular, fine below a few km). */
fun accuracyRing(center: LatLon, radiusM: Double, points: Int = 48): List<LatLon> {
    val dLat = radiusM / 111_320.0
    val dLon = radiusM / (111_320.0 * cos(center.lat * PI / 180))
    val ring = (0 until points).map { i ->
        val a = 2 * PI * i / points
        LatLon(center.lat + dLat * cos(a), center.lon + dLon * sin(a))
    }
    return ring + ring.first()
}
