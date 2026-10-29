package dz.hirz.app.push

/** A decoded FCM data message. The server never puts coordinates or reasons in it (design §12.3). */
sealed interface Push {
    data class Incident(val incidentId: String, val tier: String, val assetName: String, val rule: String?) : Push
    data class CheckIn(val assetName: String) : Push
    /** Companion: sound the alarm for 30 s even when silenced (design §6.2). */
    data class Ring(val assetName: String) : Push
    /** Companion: report every 15 s ([high]) or back to normal; sent when a P0 opens and closes. */
    data class Rate(val high: Boolean) : Push
    /** SMS gateway: the outbox has items; fetch now instead of at the next poll. */
    data object SmsPending : Push
    /** The only guardian asked to close a P0: confirm with a second tap (design §5.7). */
    data class CloseConfirm(val incidentId: String, val assetName: String) : Push
    /** A quiet in-app notice (custody changed, gateway silent): never an alarm. */
    data class Notice(val kind: String, val subject: String) : Push
}

object PushPayload {
    fun parse(data: Map<String, String>): Push? = when (data["t"]) {
        "incident" -> {
            val id = data["id"]; val tier = data["tier"]; val asset = data["asset"]
            if (id == null || tier == null || asset == null) null else Push.Incident(id, tier, asset, data["rule"])
        }
        "checkin" -> data["asset"]?.let { Push.CheckIn(it) }
        "ring" -> Push.Ring(data["asset"].orEmpty())
        "high_rate" -> Push.Rate(high = true)
        "normal_rate" -> Push.Rate(high = false)
        "sms_pending" -> Push.SmsPending
        "close_confirm" -> data["id"]?.let { Push.CloseConfirm(it, data["asset"].orEmpty()) }
        "custody_changed", "gateway_silent" -> Push.Notice(data["t"]!!, data["asset"].orEmpty())
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
