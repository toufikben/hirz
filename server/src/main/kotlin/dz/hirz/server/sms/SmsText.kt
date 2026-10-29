package dz.hirz.server.sms

import java.util.UUID

/** Contact SMS texts (design §12.3.3). Never coordinates, never the code word; a short reference ties replies to the incident. */
object SmsText {
    /** At most this many SMS parts per alert; a long asset name is shortened to fit. */
    const val MAX_PARTS = 2

    private const val GSM7 =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** "H-" + 4 characters derived from the incident id; contacts quote nothing, the server matches by sender. */
    fun ref(incidentId: UUID): String = "H-" + incidentId.toString().replace("-", "").takeLast(4).uppercase()

    fun alert(lang: String, assetName: String, tier: String, ref: String): String {
        var name = assetName.trim().take(40)
        var text = template(lang, name, tier, ref)
        while (parts(text) > MAX_PARTS && name.length > 4) {
            name = name.dropLast(2).trimEnd() + "…"
            text = template(lang, name, tier, ref)
        }
        return text
    }

    private fun template(lang: String, assetName: String, tier: String, ref: String): String {
        val urgent = tier == "P0"
        return when (lang) {
            "fr" -> "Hirz $ref : alerte${if (urgent) " urgente" else ""} pour $assetName. Appelez la famille. Répondez 1 = je m'en occupe, 2 = je ne peux pas."
            "en" -> "Hirz $ref: ${if (urgent) "urgent " else ""}alert for $assetName. Call the family. Reply 1 = I'll handle it, 2 = I can't."
            else -> "حِرز $ref: تنبيه${if (urgent) " عاجل" else ""} بخصوص $assetName. اتصل بالعائلة. للرد: 1 = أتولّى، 2 = لا أستطيع."
        }
    }

    /** SMS parts: GSM-7 is 160 alone or 153 per concatenated part; anything else is UCS-2, 70 or 67. */
    fun parts(text: String): Int {
        val gsm = text.all { it in GSM7 }
        val (single, multi) = if (gsm) 160 to 153 else 70 to 67
        return if (text.length <= single) 1 else (text.length + multi - 1) / multi
    }
}
