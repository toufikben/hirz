package dz.hirz.server.sms

import java.util.UUID

/** Contact SMS texts (design §12.3.3). Never coordinates, never the code word; a short reference ties replies to the incident. */
object SmsText {
    /** "H-" + 4 characters derived from the incident id; contacts quote nothing, the server matches by sender. */
    fun ref(incidentId: UUID): String = "H-" + incidentId.toString().replace("-", "").takeLast(4).uppercase()

    fun alert(lang: String, assetName: String, tier: String, ref: String): String {
        val urgent = tier == "P0"
        return when (lang) {
            "fr" -> "Hirz $ref : alerte${if (urgent) " urgente" else ""} pour $assetName. Appelez la famille. Répondez 1 = je m'en occupe, 2 = je ne peux pas."
            "en" -> "Hirz $ref: ${if (urgent) "urgent " else ""}alert for $assetName. Call the family. Reply 1 = I'll handle it, 2 = I can't."
            else -> "حِرز $ref: تنبيه${if (urgent) " عاجل" else ""} بخصوص $assetName. اتصل بالعائلة. للرد: 1 = أتولّى، 2 = لا أستطيع."
        }
    }
}
