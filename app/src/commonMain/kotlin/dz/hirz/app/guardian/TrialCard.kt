package dz.hirz.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dz.hirz.app.api.HirzClient
import dz.hirz.app.appInfo
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.CONSENT_VERSION
import dz.hirz.shared.api.CircleTrialDto
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.Outcome
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.api.TrialDto
import kotlinx.coroutines.launch

/** Rules a family can test for real in a trial (P8); each guided test must ring within 15 min. */
private val GUIDED_RULES = listOf(RuleId.SOS, RuleId.PLACE_EXIT, RuleId.NIGHT_EXIT, RuleId.HAZARD_PROXIMITY, RuleId.MISSED_ARRIVAL)

/**
 * The field trial (P8): the owner joins an open trial after reading the consent text, or leaves it (two taps); while in
 * it, owners and guardians announce guided tests so a test that does not ring is counted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrialCard(client: HirzClient, circleId: String, assets: List<AssetDto>, isOwner: Boolean, canTest: Boolean) {
    val lang = currentLang()
    fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
    val scope = rememberCoroutineScope()
    var mine by remember(circleId) { mutableStateOf<CircleTrialDto?>(null) }
    var open by remember(circleId) { mutableStateOf<List<TrialDto>>(emptyList()) }
    var reading by remember { mutableStateOf<TrialDto?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        mine = runCatching { client.circleTrial(circleId) }.getOrNull()
        if (mine == null) open = runCatching { client.openTrials() }.getOrDefault(emptyList())
    }
    LaunchedEffect(circleId) { reload() }

    val joined = mine
    if (joined == null) {
        if (!isOwner || open.isEmpty()) return
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("تجربة ميدانية", "Essai terrain", "Field trial"), style = MaterialTheme.typography.titleMedium)
            open.forEach { tr -> OutlinedButton({ reading = tr }) { Text("${tr.name} (${tr.startsOn} → ${tr.endsOn})") } }
            reading?.let { tr ->
                Text(TrialLogic.consentText(lang), style = MaterialTheme.typography.bodySmall)
                Button({ scope.launch { runCatching { client.joinTrial(circleId, tr.id, CONSENT_VERSION) }; reading = null; reload() } }) {
                    Text(t("أوافق وأنضم", "J'accepte et je rejoins", "I agree, join"))
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("في التجربة: ", "Dans l'essai : ", "In the trial: ") + joined.trial.name, style = MaterialTheme.typography.titleMedium)
        if (canTest) {
            Text(t("اختبار موجّه: يجب أن يرنّ خلال 15 دقيقة", "Test guidé : doit sonner sous 15 min", "Guided test: must ring within 15 min"),
                style = MaterialTheme.typography.bodySmall)
            assets.forEach { a ->
                Text(a.name, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GUIDED_RULES.forEach { r ->
                        OutlinedButton({ scope.launch {
                            note = runCatching { client.guidedTest(circleId, a.id, r) }.fold(
                                { t("بدأ الاختبار", "Test lancé", "Test started") + ": " + ProfileLogic.ruleName(r, lang) },
                                { t("تعذّر بدء الاختبار", "Impossible de lancer le test", "Could not start the test") })
                        } }) { Text(ProfileLogic.ruleName(r, lang)) }
                    }
                }
            }
        }
        if (isOwner) TextButton({
            when (TrialLogic.leaveTap(confirming)) {
                TrialLogic.LeaveStep.CONFIRM -> confirming = true
                TrialLogic.LeaveStep.LEAVE -> scope.launch { runCatching { client.leaveTrial(circleId) }; confirming = false; reload() }
            }
        }) {
            Text(if (confirming) t("تأكيد الانسحاب وحذف بيانات التجربة", "Confirmer : quitter et supprimer les données d'essai", "Confirm: leave and delete trial data")
                 else t("الانسحاب من التجربة", "Quitter l'essai", "Leave the trial"))
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** "Report a problem" (P8): free text, sent with the app version and device model; never a location. */
@Composable
fun ProblemReportCard(client: HirzClient, circleId: String) {
    val lang = currentLang()
    fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf<Boolean?>(null) }
    OutlinedButton({ open = !open }) { Text(t("أبلغ عن مشكلة", "Signaler un problème", "Report a problem")) }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(text, { text = it.take(2000) }, minLines = 3, label = { Text(t("ماذا حدث؟", "Que s'est-il passé ?", "What happened?")) })
        Text(t("يُرسل مع نسخة التطبيق ونوع الهاتف فقط، بلا موقع.", "Envoyé avec la version de l'app et le modèle du téléphone, sans localisation.",
            "Sent with the app version and phone model only, no location."), style = MaterialTheme.typography.bodySmall)
        Button({
            val (version, device) = appInfo()
            scope.launch { sent = runCatching { client.reportProblem(TrialLogic.report(circleId, text, version, device)) }.isSuccess; if (sent == true) text = "" }
        }, enabled = text.isNotBlank()) { Text(t("إرسال", "Envoyer", "Send")) }
        sent?.let { Text(if (it) t("أُرسل، شكرًا", "Envoyé, merci", "Sent, thank you") else t("تعذّر الإرسال", "Échec de l'envoi", "Could not send")) }
    }
}

/** After an alarm closes (P8): what was it? The answer feeds the false-alarm budget; a test is never a false alarm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutcomePrompt(client: HirzClient, inc: IncidentDto, onDone: () -> Unit) {
    val lang = currentLang()
    val scope = rememberCoroutineScope()
    if (inc.state != "closed" || (inc.tier != Tier.P0 && inc.tier != Tier.P1)) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${inc.tier} — ${inc.assetName}: " + ProfileLogic.ruleName(inc.rule, lang), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TrialLogic.OUTCOMES.forEach { o: Outcome ->
                OutlinedButton({ scope.launch { if (runCatching { client.reviewOutcome(inc.id, o) }.isSuccess) onDone() } }) { Text(TrialLogic.outcomeLabel(o, lang)) }
            }
        }
    }
}
