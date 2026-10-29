package dz.hirz.app.push

/** A decoded FCM data message. The server never puts coordinates or reasons in it (design §12.3). */
sealed interface Push {
    data class Incident(val incidentId: String, val tier: String, val assetName: String, val rule: String?) : Push
    data class CheckIn(val assetName: String) : Push
}

object PushPayload {
    fun parse(data: Map<String, String>): Push? = when (data["t"]) {
        "incident" -> {
            val id = data["id"]; val tier = data["tier"]; val asset = data["asset"]
            if (id == null || tier == null || asset == null) null else Push.Incident(id, tier, asset, data["rule"])
        }
        "checkin" -> data["asset"]?.let { Push.CheckIn(it) }
        else -> null
    }
}

/** Only P0 and P1 ring; P2 is in-app only (design §5). */
object AlarmPolicy {
    fun isAlarm(p: Push): Boolean = p is Push.Incident && (p.tier == "P0" || p.tier == "P1")
}

/** Lock-screen text: the asset's name and the urgency, nothing else (no place, no coordinates, no alarming words). */
object AlarmText {
    fun of(p: Push.Incident, lang: String): Pair<String, String> {
        val urgent = p.tier == "P0"
        return when (lang) {
            "ar" -> (if (urgent) "تنبيه عاجل: ${p.assetName}" else "تنبيه: ${p.assetName}") to "افتح حِرز للتفاصيل"
            "fr" -> (if (urgent) "Alerte urgente : ${p.assetName}" else "Alerte : ${p.assetName}") to "Ouvrez Hirz pour les détails"
            else -> (if (urgent) "Urgent alert: ${p.assetName}" else "Alert: ${p.assetName}") to "Open Hirz for details"
        }
    }
}
