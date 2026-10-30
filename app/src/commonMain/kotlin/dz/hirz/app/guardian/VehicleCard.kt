package dz.hirz.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import dz.hirz.app.api.HirzApiException
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.AuthorizationRequest
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.api.VehicleStateDto
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * A vehicle's controls (design §12): the mode and why, arm/disarm, "I'm driving", night schedule, and the guarded
 * engine stop. The stop needs the device credential and a typed word; the server's refusal is shown as it is.
 */
@Composable
fun VehicleCard(client: HirzClient, asset: AssetDto, isOwner: Boolean, canEdit: Boolean) {
    val lang = currentLang()
    fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<VehicleStateDto?>(null) }
    var openRules by remember { mutableStateOf<Set<RuleId>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var stopSent by remember { mutableStateOf(false) }

    suspend fun reload() {
        runCatching { client.vehicle(asset.id) }.onSuccess { state = it }
        runCatching { client.incidents(asset.circleId) }.onSuccess { list -> openRules = list.filter { it.assetId == asset.id }.map { it.rule }.toSet() }
    }
    fun act(block: suspend () -> VehicleStateDto) = scope.launch {
        runCatching { block() }.onSuccess { state = it; message = null }.onFailure { e ->
            message = if ((e as? HirzApiException)?.code == "incident_open")
                t("لا يمكن الفك أثناء إنذار مفتوح", "Impossible pendant une alerte ouverte", "Not while an alert is open")
            else t("تعذّر التنفيذ", "Échec", "Failed")
        }
    }
    LaunchedEffect(asset.id) { reload() }

    val s = state ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(VehicleLogic.modeLine(s, Clock.System.now().toEpochMilliseconds(), lang), style = MaterialTheme.typography.bodyLarge)
        s.ignition?.let { Text(if (it) t("المحرّك يعمل", "Moteur en marche", "Engine on") else t("المحرّك مطفأ", "Moteur coupé", "Engine off")) }
        if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.mode == VehicleMode.ARMED) OutlinedButton({ act { client.setMode(asset.id, VehicleMode.DISARMED) } }) { Text(t("فكّ التسليح", "Désarmer", "Disarm")) }
            else Button({ act { client.setMode(asset.id, VehicleMode.ARMED) } }) { Text(t("تسليح", "Armer", "Arm")) }
            OutlinedButton({ act { client.authorize(asset.id, AuthorizationRequest(hours = 2)) } }) { Text(t("أقود ساعتين", "Je conduis 2 h", "I'm driving, 2 h")) }
            OutlinedButton({ act { client.setArmedSchedule(asset.id, VehicleLogic.NIGHT_PRESET) } }) { Text(t("تسليح ليلي", "Armer la nuit", "Arm at night")) }
        }
        if (VehicleLogic.engineStopOffered(isOwner, openRules, asset.lifecycle == "missing", s.engineCut)) {
            Button({ typed = ""; confirming = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(t("إيقاف المحرّك عن بُعد", "Couper le moteur à distance", "Stop the engine remotely"))
            }
        }
        if (isOwner && s.engineCut && stopSent) OutlinedButton({
            scope.launch {
                runCatching { client.engine(asset.id, "resume") }.onSuccess { r ->
                    message = if (r.outcome == "sent") t("أُرسل أمر التشغيل", "Remise en marche envoyée", "Resume sent") else VehicleLogic.refusal(r.reason, lang)
                }
            }
        }) { Text(t("إعادة تشغيل المحرّك", "Remettre le moteur", "Resume the engine")) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(t("إيقاف المحرّك", "Couper le moteur", "Stop the engine")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t(
                    "يُرسل فقط والمركبة شبه متوقفة وجهاز التتبع متصل. لا تواجه السارق؛ اتصل بالشرطة.",
                    "Envoyé seulement si le véhicule est quasi à l'arrêt et le traceur connecté. N'affrontez pas le voleur ; appelez la police.",
                    "Sent only with the vehicle almost stationary and the tracker online. Do not confront the thief; call the police.",
                ))
                Text(t("اكتب «${VehicleLogic.confirmWord(lang)}» للتأكيد", "Tapez « ${VehicleLogic.confirmWord(lang)} » pour confirmer", "Type \"${VehicleLogic.confirmWord(lang)}\" to confirm"))
                OutlinedTextField(typed, { typed = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                confirming = false
                scope.launch {
                    if (!confirmDeviceCredential()) return@launch
                    runCatching { client.engine(asset.id, "stop") }.onSuccess { r ->
                        if (r.outcome == "sent") { stopSent = true; message = t("أُرسل أمر الإيقاف", "Coupure envoyée", "Stop sent") }
                        else message = VehicleLogic.refusal(r.reason, lang)
                    }.onFailure { message = VehicleLogic.refusal(null, lang) }
                    reload()
                }
            }, enabled = VehicleLogic.confirmMatches(typed, lang)) { Text(t("أوقف", "Couper", "Stop")) }
        },
        dismissButton = { TextButton({ confirming = false }) { Text(t("إلغاء", "Annuler", "Cancel")) } },
    )
}
