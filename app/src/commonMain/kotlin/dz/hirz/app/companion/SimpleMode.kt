package dz.hirz.app.companion

import dz.hirz.shared.asset.AssetType

/** The elder's companion screen (design §12.4): one very large SOS, one "I'm fine", and a spoken answer. */
object SimpleMode {
    enum class Button { SOS, FINE }

    val buttons = listOf(Button.SOS, Button.FINE)

    fun isSimple(type: AssetType?): Boolean = type == AssetType.ELDER

    fun spokenAfterSos(lang: String): String = when (lang) {
        "fr" -> "L'alerte est envoyée. Votre famille arrive."
        "en" -> "Your alert is sent. Your family is on the way."
        else -> "أُرسل النداء. عائلتك في الطريق."
    }

    fun spokenAfterFine(lang: String): String = when (lang) {
        "fr" -> "Merci. Votre famille est rassurée."
        "en" -> "Thank you. Your family knows you are fine."
        else -> "شكرًا. عائلتك اطمأنت عليك."
    }
}
