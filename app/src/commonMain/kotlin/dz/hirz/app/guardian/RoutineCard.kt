package dz.hirz.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.DigestDto
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.RoutineDto
import dz.hirz.shared.api.RoutineRequest
import kotlinx.coroutines.launch

/**
 * An asset's routines (design §6.3): a ready-made template on one of the circle's places; the server fills in the days
 * and times. Missing the arrival, staying still after dismissal and stopping on the way are judged from these.
 */
@Composable
fun RoutineCard(client: HirzClient, circleId: String, asset: AssetDto, canEdit: Boolean) {
    val lang = currentLang()
    fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var routines by remember { mutableStateOf<List<RoutineDto>>(emptyList()) }
    var places by remember { mutableStateOf<List<PlaceDto>>(emptyList()) }
    var place by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    suspend fun reload() {
        runCatching { client.routines(asset.id) to client.places(circleId) }
            .onSuccess { (r, p) -> routines = r; places = p; failed = false }.onFailure { failed = true }
    }
    LaunchedEffect(open) { if (open) reload() }

    OutlinedButton({ open = !open }) { Text(t("الروتين اليومي", "Routine", "Daily routine")) }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        routines.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(RoutineLogic.label(r, places.firstOrNull { it.id == r.placeId }?.name ?: "?", lang), style = MaterialTheme.typography.bodyMedium)
                if (canEdit) TextButton({ scope.launch { runCatching { client.deleteRoutine(r.id) }; reload() } }) { Text(t("حذف", "Supprimer", "Remove")) }
            }
        }
        if (routines.isEmpty()) Text(t("لا روتين بعد", "Aucune routine", "No routine yet"), style = MaterialTheme.typography.bodySmall)
        if (canEdit && places.isNotEmpty()) {
            Text(t("المكان", "Lieu", "Place"), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                places.forEach { p -> FilterChip(place == p.id, { place = p.id }, { Text(p.name) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RoutineLogic.templatesFor(asset.type).forEach { tpl ->
                    OutlinedButton({
                        val pid = place ?: return@OutlinedButton
                        scope.launch { runCatching { client.createRoutine(asset.id, RoutineRequest(pid, template = tpl)) }.onFailure { failed = true }; reload() }
                    }, enabled = place != null) { Text("+ " + RoutineLogic.templateName(tpl, lang)) }
                }
            }
        }
        if (failed) Text(t("تعذّر التحميل أو الحفظ", "Échec du chargement ou de l'enregistrement", "Could not load or save"), color = MaterialTheme.colorScheme.error)
    }
}

/** The circle's evening digests (design §7.4): the day's quiet findings, one list a day, nothing that rings. */
@Composable
fun DigestCard(client: HirzClient, circleId: String) {
    val lang = currentLang()
    fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
    var open by remember { mutableStateOf(false) }
    var digests by remember { mutableStateOf<List<DigestDto>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open) { if (open) runCatching { client.digests(circleId) }.onSuccess { digests = it } }

    OutlinedButton({ open = !open }) { Text(t("ملخص المساء", "Résumé du soir", "Evening digest")) }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (digests.isEmpty()) Text(t("لا شيء يُذكر", "Rien à signaler", "Nothing to report"), style = MaterialTheme.typography.bodySmall)
        digests.forEach { d ->
            Text(d.day, style = MaterialTheme.typography.titleSmall)
            d.items.forEachIndexed { i, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• " + DigestLogic.line(item, lang), style = MaterialTheme.typography.bodyMedium)
                    // Was this line worth telling? (P8: the digest's usefulness is part of the budget.)
                    TextButton({ scope.launch { runCatching { client.digestFeedback(d.id, i, true) } } }) { Text("👍") }
                    TextButton({ scope.launch { runCatching { client.digestFeedback(d.id, i, false) } } }) { Text("👎") }
                }
            }
        }
    }
}
