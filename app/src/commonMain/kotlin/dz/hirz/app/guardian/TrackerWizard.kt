package dz.hirz.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.hirz.app.api.HirzApiException
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.TrackerClaimDto
import dz.hirz.shared.api.TrackerClaimRequest
import dz.hirz.shared.devices.DeviceModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

/**
 * Add a GSM tracker to [assetId] (design §3): each setup SMS is sent from the owner's own phone by their own tap,
 * then the wizard waits for the device's first report, which proves the owner controls it (§12.5.1).
 */
@Composable
fun TrackerWizard(client: HirzClient, assetId: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lang = currentLang()
    var models by remember { mutableStateOf<List<DeviceModel>>(emptyList()) }
    var model by remember { mutableStateOf<DeviceModel?>(null) }
    var imei by remember { mutableStateOf("") }
    var sim by remember { mutableStateOf("") }
    var apn by remember { mutableStateOf("") }
    var claim by remember { mutableStateOf<TrackerClaimDto?>(null) }
    var commands by remember { mutableStateOf(claim?.commands.orEmpty()) }
    val sent = remember { mutableStateListOf<Int>() }
    var error by remember { mutableStateOf<String?>(null) }
    val step = TrackerWizardLogic.step(model, claim, commandsSent = commands.isNotEmpty() && sent.size == commands.size)

    LaunchedEffect(Unit) { models = runCatching { TrackerWizardLogic.selectable(client.deviceModels()) }.getOrDefault(emptyList()) }
    LaunchedEffect(claim?.claimId, step) {
        val id = claim?.claimId ?: return@LaunchedEffect
        while (step == WizardStep.WAITING || step == WizardStep.SEND_COMMANDS) {
            delay(5_000)
            if (appInForeground()) runCatching { client.trackerClaim(id) }.onSuccess { claim = it.copy(commands = commands) }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text(t(lang, "إغلاق", "Fermer", "Close")) } },
        title = { Text(t(lang, "إضافة جهاز تتبع", "Ajouter un traceur", "Add a tracker")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (step) {
                    WizardStep.PICK_MODEL -> {
                        Text(t(lang, "اختر الطراز", "Choisissez le modèle", "Choose the model"))
                        models.forEach { m -> OutlinedButton({ model = m }) { Text(m.name) } }
                        Text(t(lang, "لا تعرف الطراز؟ أرسل صورة الجهاز وعلبته للدعم.", "Modèle inconnu ? Envoyez une photo de l'appareil et de sa boîte au support.",
                            "Don't know the model? Send a photo of the device and its box to support."), style = MaterialTheme.typography.bodySmall)
                    }
                    WizardStep.ENTER_IDS -> {
                        val m = model!!
                        Text(m.name, style = MaterialTheme.typography.titleMedium)
                        TrackerWizardLogic.untestedWarning(m, lang)?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                        TrackerWizardLogic.capabilitySummary(m, lang).forEach { Text("• $it") }
                        OutlinedTextField(imei, { imei = it.filter(Char::isDigit).take(15) }, label = { Text("IMEI") }, singleLine = true)
                        TrackerWizardLogic.imeiError(imei)?.takeIf { imei.isNotEmpty() }?.let {
                            Text(t(lang, "IMEI غير صحيح", "IMEI invalide", "Invalid IMEI"), color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedTextField(sim, { sim = it.filter { c -> c.isDigit() || c == '+' }.take(16) },
                            label = { Text(t(lang, "رقم شريحة الجهاز", "Numéro de la carte SIM", "Tracker SIM number")) }, singleLine = true)
                        OutlinedTextField(apn, { apn = it.trim().take(60) },
                            label = { Text(t(lang, "APN (اسأل مشغّلك)", "APN (demandez à l'opérateur)", "APN (ask your operator)")) }, singleLine = true)
                        Button({
                            scope.launch {
                                runCatching { client.claimTracker(assetId, TrackerClaimRequest(m.id, imei, sim, apn)) }
                                    .onSuccess { claim = it; commands = it.commands; error = null }
                                    .onFailure { e ->
                                        error = when ((e as? HirzApiException)?.code) {
                                            "imei_in_use" -> t(lang, "هذا الجهاز مرتبط بدائرة أخرى؛ يجب أن يحرّره مالكه أولًا.",
                                                "Cet appareil est lié à un autre cercle ; son propriétaire doit le libérer.",
                                                "This device is linked to another circle; its owner must release it first.")
                                            else -> t(lang, "تعذّرت الإضافة", "Échec de l'ajout", "Could not add")
                                        }
                                    }
                            }
                        }, enabled = TrackerWizardLogic.imeiError(imei) == null && sim.length >= 9 && apn.isNotBlank()) {
                            Text(t(lang, "التالي", "Suivant", "Next"))
                        }
                    }
                    WizardStep.SEND_COMMANDS, WizardStep.WAITING -> {
                        Text(t(lang, "أرسل كل رسالة من هاتفك إلى شريحة الجهاز، بالترتيب. احفظ كلمة السر الجديدة: لن تُعرض مرة أخرى.",
                            "Envoyez chaque SMS depuis votre téléphone vers la SIM du traceur, dans l'ordre. Notez le nouveau mot de passe : il ne sera plus affiché.",
                            "Send each SMS from your phone to the tracker's SIM, in order. Keep the new password: it is shown only once."))
                        commands.forEachIndexed { i, c ->
                            FilterChip(i in sent, { composeSms(c.to, c.text); if (i !in sent) sent += i }, { Text("${i + 1}. ${c.text}") })
                        }
                        if (step == WizardStep.WAITING) Text(t(lang, "بانتظار أول إشارة من الجهاز…", "En attente du premier signal…", "Waiting for the device's first report…"))
                    }
                    WizardStep.DONE -> Text(t(lang, "تم: الجهاز يرسل إلى حِرز.", "C'est fait : le traceur envoie à Hirz.", "Done: the tracker reports to Hirz."))
                    WizardStep.EXPIRED -> {
                        Text(t(lang, "لم تصل إشارة خلال 30 دقيقة. تحقق من الرسائل والرصيد وأعد المحاولة.",
                            "Aucun signal en 30 minutes. Vérifiez les SMS et le crédit, puis réessayez.",
                            "No report within 30 minutes. Check the SMS replies and credit, then try again."))
                        TextButton({ claim = null; commands = emptyList(); sent.clear() }) { Text(t(lang, "إعادة", "Réessayer", "Try again")) }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}
