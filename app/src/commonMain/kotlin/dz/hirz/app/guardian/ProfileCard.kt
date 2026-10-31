package dz.hirz.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dz.hirz.app.api.HirzApiException
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.ProfileDto
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.RuleOverride
import dz.hirz.shared.api.RuleSettingDto
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import kotlinx.coroutines.launch

/**
 * A person's behavior profile (design §4.4): the condition (owner only, never shown outside this screen), each rule's
 * switch and urgency, the teen's curfew and the birth date for the age transition. The server enforces every limit.
 */
@Composable
fun ProfileCard(client: HirzClient, asset: AssetDto, isOwner: Boolean) {
    val lang = currentLang()
    fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<ProfileDto?>(null) }
    var rows by remember { mutableStateOf<List<RuleSettingDto>>(emptyList()) }
    var curfew by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun load(p: ProfileDto) {
        profile = p; rows = p.rules
        curfew = p.curfewMinute?.let { "${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}" } ?: ""
        birth = p.birthDate ?: ""
    }
    fun save(req: ProfileUpdate) = scope.launch {
        runCatching { client.setProfile(asset.id, req) }.onSuccess { load(it); message = null }.onFailure { e ->
            message = when ((e as? HirzApiException)?.code) {
                "owner_only" -> t("هذا التغيير للمالك فقط", "Réservé au propriétaire", "Only the owner can change this")
                "p0_cannot_be_disabled" -> t("قاعدة عاجلة لا تُطفأ", "Règle urgente : jamais désactivée", "An urgent rule cannot be switched off")
                else -> t("تعذّر الحفظ", "Échec de l'enregistrement", "Could not save")
            }
        }
    }
    LaunchedEffect(open) { if (open && profile == null) runCatching { client.profile(asset.id) }.onSuccess { load(it) } }

    OutlinedButton({ open = !open }) { Text(t("ملف السلوك", "Profil de comportement", "Behavior profile")) }
    if (!open) return
    val p = profile ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isOwner) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Condition.entries.forEach { c ->
                FilterChip(p.condition == c, { save(ProfileUpdate(condition = c)) }, { Text(ProfileLogic.conditionLabel(c, lang)) })
            }
        }
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ProfileLogic.ruleName(r.rule, lang), style = MaterialTheme.typography.bodyMedium)
                if (ProfileLogic.showSwitch(r) && isOwner) Switch(r.enabled, { on -> rows = rows.map { if (it.rule == r.rule) it.copy(enabled = on) else it } })
                ProfileLogic.tierChoices(r, isOwner).forEach { tier ->
                    FilterChip(r.tier == tier, { rows = rows.map { if (it.rule == r.rule) it.copy(tier = tier) else it } }, { Text(tier.name) })
                }
            }
        }
        if (asset.type == AssetType.TEEN) OutlinedTextField(curfew, { curfew = it.take(5) }, singleLine = true,
            label = { Text(t("موعد العودة (22:00)", "Heure de retour (22:00)", "Curfew (22:00)")) })
        if (isOwner) {
            OutlinedTextField(birth, { birth = it.take(10) }, singleLine = true, label = { Text(t("تاريخ الميلاد (2012-05-31)", "Date de naissance (2012-05-31)", "Birth date (2012-05-31)")) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(p.conditionOnSheet, { save(ProfileUpdate(conditionOnSheet = it)) })
                Text(t("أظهر الحالة في ورقة الشرطة فقط", "Montrer la condition sur la fiche police seulement", "Show the condition on the police sheet only"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(p.nightBySun, { save(ProfileUpdate(nightBySun = it)) }, enabled = isOwner)
            Text(t("الليل من الغروب إلى الشروق", "Nuit du coucher au lever du soleil", "Night from sunset to sunrise"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(p.ramadanNightOff, { save(ProfileUpdate(ramadanNightOff = it)) }, enabled = isOwner)
            Text(t("سهرات رمضان تُعدّ خروجًا ليليًا", "Les soirées du Ramadan comptent comme sortie de nuit", "Ramadan evenings count as night exits"))
        }
        if (p.verifyFirst.isNotEmpty() || p.verifyFirstOff) {
            Text(t("تحقّق أولًا (إنذارات كاذبة متكررة): ", "À vérifier d'abord (fausses alertes répétées) : ", "Check first (repeated false alarms): ") +
                p.verifyFirst.joinToString(", ") { ProfileLogic.ruleName(it, lang) }, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(!p.verifyFirstOff, { save(ProfileUpdate(verifyFirstOff = !it)) }, enabled = isOwner)
                Text(t("إنذار هادئ أولًا", "Alerte discrète d'abord", "Quiet alert first"))
            }
        }
        Button({
            val overrides = rows.map { RuleOverride(it.rule, it.enabled, it.tier.takeIf { t -> t != it.defaultTier }) }
            save(ProfileUpdate(overrides = overrides, curfewMinute = ProfileLogic.parseClock(curfew),
                birthDate = birth.takeIf { isOwner && it.isNotBlank() }))
        }) { Text(t("حفظ", "Enregistrer", "Save")) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
