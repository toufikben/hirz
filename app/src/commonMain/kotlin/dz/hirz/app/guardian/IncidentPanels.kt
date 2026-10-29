package dz.hirz.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.hirz.app.api.HirzApiException
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.EmergencyNumberDto
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.SheetDto
import dz.hirz.shared.api.Tier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

/** Who is who in the circle, as the incident screens need it. */
data class CircleView(val names: Map<String, String>, val editors: Int, val myMemberId: String?)

/**
 * One open incident (design §5): ladder line, owner, close request, shared actions, sheet, share link and police
 * pack. While this card is on screen and the app is in front, the guardian's own position is reported every 30 s.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncidentCard(client: HirzClient, inc: IncidentDto, view: CircleView, numbers: List<EmergencyNumberDto>, onUpdate: (IncidentDto) -> Unit) {
    val scope = rememberCoroutineScope()
    val lang = currentLang()
    var message by remember(inc.id) { mutableStateOf<String?>(null) }
    var sheetOpen by remember(inc.id) { mutableStateOf(false) }
    var shareUrl by remember(inc.id) { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> IncidentDto?) = scope.launch {
        runCatching { block() }.onSuccess { it?.let(onUpdate) }.onFailure { e ->
            message = when ((e as? HirzApiException)?.code) {
                "already_done" -> t(lang, "قام به عضو آخر قبل قليل", "Déjà fait par un autre membre", "Already done by another member")
                "needs_second_guardian" -> t(lang, "يلزم وليّ آخر للتأكيد", "Un autre tuteur doit confirmer", "Another guardian must confirm")
                "close_request_expired" -> t(lang, "انتهت مهلة طلب الإغلاق", "La demande de fermeture a expiré", "The close request expired")
                else -> t(lang, "تعذّر التنفيذ", "Échec", "Failed")
            }
        }
    }

    LaunchedEffect(inc.id, inc.state) {
        LocationReporter.run(
            active = { isActive && LocationReporter.shouldRun(inc.state == "open", appInForeground()) },
            send = { currentLocation()?.let { (p, acc) -> client.myLocation(inc.id, p, acc) } },
            wait = { delay(it) },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${inc.tier} — ${inc.assetName}", style = MaterialTheme.typography.titleLarge)
            inc.reasons.forEach { Text("• " + ReasonText.render(it, lang)) }
            LadderText.render(inc.escalation, view.names, lang, ::formatClock)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(inc.ownerMemberId?.let { t(lang, "يتولاها: ", "Pris en charge par : ", "Handled by: ") + (view.names[it] ?: it.take(8)) }
                ?: t(lang, "لم يتولّها أحد بعد", "Personne ne s'en occupe encore", "Nobody is handling it yet"))
            inc.events.filter { it.kind == "action" }.takeLast(5).forEach { e ->
                val who = e.memberId?.let { view.names[it] } ?: ""
                Text("✓ ${e.data["kind"]?.toString()?.trim('"')} ${e.data["value"]?.toString()?.trim('"') ?: ""} — $who — ${formatClock(e.atMs)}", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ act { client.claim(inc.id) } }) { Text(t(lang, "أتولّاها", "Je m'en occupe", "I'll handle it")) }
                OutlinedButton({ act { client.contest(inc.id) } }) { Text(t(lang, "لا أستطيع", "Je ne peux pas", "I can't")) }
                OutlinedButton({ sheetOpen = true }) { Text(t(lang, "البطاقة", "Fiche", "Sheet")) }
            }
            Text(t(lang, "اتصلت بـ", "J'ai appelé", "I called"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                numbers.forEach { n ->
                    OutlinedButton({ dial(n.number); act { client.action(inc.id, "called_number", n.number) } }) {
                        Text(n.number + if (n.verifiedAtMs == null) " ⚠" else "")
                    }
                }
                TextButton({ act { client.action(inc.id, "went_to_last_position") } }) { Text(t(lang, "ذهبت لآخر موقع", "Je vais à la dernière position", "Going to last position")) }
                TextButton({ act { client.action(inc.id, "checked_school") } }) { Text(t(lang, "اتصلت بالمدرسة", "J'ai vérifié l'école", "Checked the school")) }
            }
            if (numbers.any { it.verifiedAtMs == null }) Text(t(lang, "⚠ تحقّق من الرقم لولايتك", "⚠ Vérifiez le numéro pour votre wilaya", "⚠ Check the number for your wilaya"), style = MaterialTheme.typography.bodySmall)

            when (CloseHint.pending(inc.pendingClose, view.myMemberId, view.editors)) {
                PendingAction.WAIT_FOR_OTHER -> Text(t(lang, "طلبتَ الإغلاق — بانتظار تأكيد وليّ آخر", "Fermeture demandée — en attente d'un autre tuteur", "Close requested — waiting for another guardian"))
                PendingAction.CONFIRM -> Button({ act { client.confirmClose(inc.id) } }) {
                    Text(t(lang, "تأكيد الإغلاق", "Confirmer la fermeture", "Confirm close") + " (${inc.pendingClose?.requestedBy?.let { view.names[it] } ?: ""})")
                }
                PendingAction.NONE -> {
                    Text(t(lang, "إغلاق", "Fermer", "Close"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            CloseReason.FOUND_SAFE to t(lang, "وُجد سالمًا", "Retrouvé", "Found safe"),
                            CloseReason.FAMILY to t(lang, "شأن عائلي", "Affaire de famille", "Family matter"),
                            CloseReason.TECHNICAL to t(lang, "خلل تقني", "Problème technique", "Technical"),
                        ).forEach { (r, label) -> TextButton({ act { client.close(inc.id, r) } }) { Text(label) } }
                    }
                    CloseHint.forReason(CloseReason.FOUND_SAFE, inc.tier == Tier.P0, view.editors, lang).let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ scope.launch { runCatching { client.share(inc.id, 60) }.onSuccess { shareUrl = it.url; copyText(it.url) } } }) {
                    Text(t(lang, "رابط مؤقت (ساعة)", "Lien temporaire (1 h)", "Temporary link (1 h)"))
                }
                if (shareUrl != null) TextButton({ scope.launch { runCatching { client.revokeShare(inc.id) }.onSuccess { shareUrl = null } } }) {
                    Text(t(lang, "إلغاء الرابط", "Révoquer le lien", "Revoke link"))
                }
                OutlinedButton({
                    scope.launch {
                        runCatching { client.policePack(inc.id) }.onSuccess { bytes ->
                            val ok = saveDownload("hirz-${inc.id.takeLast(4)}.zip", "application/zip", bytes)
                            message = if (ok) t(lang, "حُفظ ملف الشرطة في التنزيلات/Hirz", "Dossier police enregistré dans Téléchargements/Hirz", "Police pack saved to Downloads/Hirz")
                            else t(lang, "تعذّر الحفظ", "Échec de l'enregistrement", "Could not save")
                        }.onFailure { message = t(lang, "للأولياء فقط", "Réservé aux tuteurs", "Guardians only") }
                    }
                }) { Text(t(lang, "ملف الشرطة", "Dossier police", "Police pack")) }
            }
            shareUrl?.let { Text(t(lang, "نُسخ الرابط: ", "Lien copié : ", "Link copied: ") + it, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (sheetOpen) SheetDialog(client, inc.id, lang) { sheetOpen = false }
}

/** The incident sheet: everything to read out to the police, a PDF, and a "post" composed only on the user's tap. */
@Composable
private fun SheetDialog(client: HirzClient, incidentId: String, lang: String, onDismiss: () -> Unit) {
    var sheet by remember { mutableStateOf<SheetDto?>(null) }
    var phone by remember { mutableStateOf("") }
    LaunchedEffect(incidentId) { sheet = runCatching { client.sheet(incidentId) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
        title = { Text(sheet?.assetName ?: "…") },
        text = {
            val s = sheet
            if (s == null) Text("…") else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.todayClothes?.let { Text(t(lang, "اللباس اليوم: ", "Vêtements : ", "Wearing: ") + it) }
                s.lastFix?.let { Text(t(lang, "آخر موقع: ", "Dernière position : ", "Last fix: ") + formatClock(it.fixTimeMs) + " — " + Captions.age(s.lastFixAgeS?.times(1000), it.accuracyM, lang)) }
                Text(t(lang, "مواقع آخر 30 دقيقة: ", "Positions (30 min) : ", "Fixes (30 min): ") + s.track.size)
                s.sources.forEach { Text("📱 ${it.kind} ${it.model ?: ""} ${it.imei ?: ""}") }
                s.shareActiveUntilMs?.let { Text(t(lang, "رابط مشاركة نشط حتى ", "Lien actif jusqu'à ", "Share link active until ") + formatClock(it)) }
                OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(16) },
                    label = { Text(t(lang, "هاتف العائلة للمنشور", "Téléphone de la famille", "Family phone for the post")) }, singleLine = true)
                Button({ saveDownload("hirz-sheet-${incidentId.takeLast(4)}.pdf", "application/pdf", sheetPdf(s, lang)) }) { Text("PDF") }
                Button({
                    shareText(PostText.compose(s.assetName, s.todayClothes, null, s.lastFix?.let { formatClock(it.fixTimeMs) }, phone, lang))
                }, enabled = phone.length >= 9) { Text(t(lang, "منشور", "Publication", "Post")) }
            }
        },
    )
}

/** The family code word (design §5.10): set by owners and guardians; shown only after the device lock check. */
@Composable
fun CodeWordCard(client: HirzClient, circle: CircleDto) {
    val scope = rememberCoroutineScope()
    val lang = currentLang()
    var draft by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t(lang, "كلمة سر العائلة", "Mot de passe familial", "Family code word"), style = MaterialTheme.typography.titleMedium)
            Text(t(lang, "تُطلب ممن يتصل باسم العائلة. لا تظهر في أي إشعار.", "À demander à qui appelle au nom de la famille. Jamais dans une notification.",
                "Ask it of anyone calling on the family's behalf. Never shown in a notification."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton({
                scope.launch {
                    if (shown != null) { shown = null; return@launch }
                    if (!confirmDeviceCredential()) return@launch
                    shown = runCatching { client.codeWord(circle.id).word }.getOrElse { t(lang, "لم تُحدَّد بعد", "Pas encore défini", "Not set yet") }
                }
            }) { Text(if (shown == null) t(lang, "إظهار", "Afficher", "Show") else t(lang, "إخفاء", "Masquer", "Hide")) }
            shown?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
            if (circle.myRole == Role.OWNER || circle.myRole == Role.GUARDIAN) {
                OutlinedTextField(draft, { draft = it.take(40) }, label = { Text(t(lang, "كلمة جديدة (3–40)", "Nouveau mot (3–40)", "New word (3–40)")) }, singleLine = true)
                Button({
                    scope.launch {
                        note = if (runCatching { client.setCodeWord(circle.id, draft.trim()) }.isSuccess) { draft = ""; t(lang, "حُفظت", "Enregistré", "Saved") }
                        else t(lang, "تعذّر الحفظ", "Échec", "Failed")
                    }
                }, enabled = draft.trim().length in 3..40) { Text(t(lang, "حفظ", "Enregistrer", "Save")) }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Ring the asset's phone (design §6.2): it sounds for 30 s even when silenced. */
@Composable
fun RingButton(client: HirzClient, assetId: String) {
    val scope = rememberCoroutineScope()
    val lang = currentLang()
    var sent by remember(assetId) { mutableStateOf<Int?>(null) }
    OutlinedButton({ scope.launch { sent = runCatching { client.ring(assetId).sent }.getOrDefault(0) } }) {
        Text(t(lang, "رنّ الهاتف", "Faire sonner", "Ring") + (sent?.let { " ($it)" } ?: ""))
    }
}

/** Sign out after removing this install's push token from the server (P1 minor #20). */
@Composable
fun SignOutButton(client: HirzClient, pushToken: suspend () -> String?, signOut: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val lang = currentLang()
    var confirm by remember { mutableStateOf(false) }
    if (!confirm) TextButton({ confirm = true }) { Text(t(lang, "تسجيل الخروج", "Se déconnecter", "Sign out")) }
    else FilterChip(true, {
        scope.launch {
            pushToken()?.let { tok -> runCatching { client.deletePushToken(tok) } }
            signOut()
        }
    }, { Text(t(lang, "تأكيد: لن تصلك التنبيهات على هذا الهاتف", "Confirmer : plus d'alertes sur ce téléphone", "Confirm: no more alerts on this phone")) })
}
