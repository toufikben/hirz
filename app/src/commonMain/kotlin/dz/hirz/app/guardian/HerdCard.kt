package dz.hirz.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.HerdDto
import dz.hirz.shared.asset.AssetType
import kotlinx.coroutines.launch

/**
 * The circle's herds (design §1 groups, P6): who is in each, which head leads (it carries the tracker), the weekly
 * market day when moving the herd is expected, and the announced Eid al-Adha date.
 */
@Composable
fun HerdsCard(client: HirzClient, circleId: String, assets: List<AssetDto>, canEdit: Boolean) {
    val lang = currentLang()
    fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
    val scope = rememberCoroutineScope()
    var herds by remember { mutableStateOf<List<HerdDto>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    suspend fun reload() { runCatching { client.herds(circleId) }.onSuccess { herds = it } }
    LaunchedEffect(circleId) { reload() }
    val livestock = assets.filter { it.type == AssetType.LIVESTOCK }
    if (livestock.isEmpty() && herds.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(t("القطعان", "Troupeaux", "Herds"), style = MaterialTheme.typography.titleMedium)
    herds.forEach { h -> key(h.id) {
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(h.name, style = MaterialTheme.typography.titleSmall)
            Text(t("${h.members.size} رأس", "${h.members.size} têtes", "${h.members.size} heads"))
            h.marketWindow.firstOrNull()?.let { Text(t("يوم السوق: ", "Jour de marché : ", "Market day: ") + HerdLogic.marketLabel(it, lang)) }
            h.eidDate?.let { Text(t("عيد الأضحى: $it", "Aïd el-Adha : $it", "Eid al-Adha: $it")) }
            if (!canEdit) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                livestock.filter { it.id !in h.members }.take(4).forEach { a ->
                    OutlinedButton({ scope.launch { runCatching { client.addToHerd(h.id, a.id) }; reload() } }) { Text("+ ${a.name}") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                livestock.filter { it.id in h.members }.forEach { a ->
                    FilterChip(h.leaderId == a.id, { scope.launch { runCatching { client.setLeader(h.id, a.id) }; reload() } },
                        { Text(t("قائد: ", "Meneur : ", "Leader: ") + a.name) })
                }
            }
            var day by remember { mutableStateOf(h.marketWindow.firstOrNull()?.daysOfWeek?.firstOrNull() ?: 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (1..7).forEach { d -> FilterChip(day == d, { day = d }, { Text(HerdLogic.dayName(d, lang).take(3)) }) }
            }
            OutlinedButton({
                HerdLogic.marketWindow(day, "05:00", "14:00")?.let { w -> scope.launch { runCatching { client.setMarket(h.id, w) }; reload() } }
            }) { Text(t("يوم السوق 05:00–14:00", "Marché 05:00–14:00", "Market 05:00–14:00")) }
            var eid by remember { mutableStateOf(h.eidDate ?: "") }
            OutlinedTextField(eid, { eid = it.take(10) }, singleLine = true,
                label = { Text(t("تاريخ العيد المُعلن (2027-05-16)", "Date annoncée de l'Aïd (2027-05-16)", "Announced Eid date (2027-05-16)")) })
            OutlinedButton({ scope.launch { runCatching { client.setEid(h.id, eid.ifBlank { null }) }; reload() } }) { Text(t("حفظ", "Enregistrer", "Save")) }
        } }
    } }
    if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(t("اسم القطيع", "Nom du troupeau", "Herd name")) })
        Button({ scope.launch { runCatching { client.createHerd(circleId, name.trim()) }.onSuccess { name = "" }; reload() } },
            enabled = name.isNotBlank()) { Text(t("إضافة", "Ajouter", "Add")) }
    }
    }
}
