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
    fun render(r: Reason, lang: String): String {
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
