package dz.hirz.app.guardian

import dz.hirz.shared.api.TrackerClaimDto
import dz.hirz.shared.devices.DeviceModel
import dz.hirz.shared.devices.SupportLevel

enum class WizardStep { PICK_MODEL, ENTER_IDS, SEND_COMMANDS, WAITING, DONE, EXPIRED }

private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

/** The add-a-tracker wizard (design §3): pick the model, send its setup SMS yourself, wait for the first report. */
object TrackerWizardLogic {
    /** null when valid; "length" or "checksum" otherwise. Same rule as the server (15 digits, Luhn). */
    fun imeiError(raw: String): String? {
        val imei = raw.trim()
        if (imei.length != 15 || !imei.all { it in '0'..'9' }) return "length"
        val sum = imei.reversed().mapIndexed { i, ch ->
            val d = ch - '0'
            if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
        }.sum()
        return if (sum % 10 == 0) null else "checksum"
    }

    /** [prefix] (14 digits) with its Luhn check digit. */
    fun withCheckDigit(prefix: String): String = (0..9).map { prefix + it }.first { imeiError(it) == null }

    fun step(model: DeviceModel?, claim: TrackerClaimDto?, commandsSent: Boolean): WizardStep = when {
        model == null -> WizardStep.PICK_MODEL
        claim == null -> WizardStep.ENTER_IDS
        claim.state == "active" -> WizardStep.DONE
        claim.state == "expired" -> WizardStep.EXPIRED
        !commandsSent -> WizardStep.SEND_COMMANDS
        else -> WizardStep.WAITING
    }

    fun selectable(models: List<DeviceModel>): List<DeviceModel> = models.filter { it.level != SupportLevel.REJECTED }

    /** One plain line per thing the model cannot report, said before the owner commits. */
    fun capabilitySummary(model: DeviceModel, lang: String): List<String> = model.missing.sorted().map { m ->
        when (m) {
            "removal" -> t(lang, "هذا الجهاز لا يكشف نزعه", "Cet appareil ne détecte pas son retrait", "This device does not detect its removal")
            "battery_level" -> t(lang, "لا يرسل نسبة البطارية", "N'envoie pas le niveau de batterie", "Does not report its battery level")
            else -> t(lang, "لا يدعم: $m", "Non pris en charge : $m", "Not supported: $m")
        }
    }

    fun untestedWarning(model: DeviceModel, lang: String): String? =
        if (model.level == SupportLevel.CERTIFIED) null
        else t(lang, "لم نختبر هذا الطراز بعد: قد لا تعمل بعض الأوامر", "Modèle pas encore testé : certaines commandes peuvent échouer",
            "This model is not tested yet: some commands may not work")
}
