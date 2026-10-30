package dz.hirz.app.companion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import dz.hirz.app.AppEnvironment
import dz.hirz.app.CompanionPermissionsCard
import dz.hirz.app.generated.resources.*
import dz.hirz.app.guardian.Captions
import dz.hirz.app.guardian.currentLang
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Companion mode: no map, no history, no list — only sharing, SOS, PIN and check-in (design §6.2). */
@Composable
fun CompanionRoot(env: AppEnvironment, baseUrl: String) {
    val port = env.companion
    val paired by port.paired.collectAsState()
    val client = remember(baseUrl) { env.client(baseUrl) }
    val info = paired
    if (info == null) PairScreen(env, client) else CompanionHome(env, client, info)
}

@Composable
private fun PairScreen(env: AppEnvironment, client: dz.hirz.app.api.HirzClient) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var adult by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.pair_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedButton({ scope.launch { env.companion.scanQr()?.let { code = it.substringAfterLast('/') } } }) { Text(stringResource(Res.string.scan_qr)) }
        OutlinedTextField(code, { code = it.uppercase().take(12) }, label = { Text(stringResource(Res.string.pair_code_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(adult, { adult = it })
            Text(stringResource(Res.string.adult_question))
        }
        Text(stringResource(Res.string.consent_text, "…"), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(consent, { consent = it })
            Text(stringResource(Res.string.i_agree))
        }
        Button({
            scope.launch {
                failed = env.companion.pair(client, code, adult, consent).isFailure
                if (!failed) env.companion.startSharing()
            }
        }, enabled = code.length >= 8 && consent) { Text(stringResource(Res.string.pair_button)) }
        if (failed) Text(stringResource(Res.string.pair_failed), color = MaterialTheme.colorScheme.error)
        TextButton({ scope.launch { env.modes.set(null) } }) { Text(stringResource(Res.string.back)) }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun CompanionHome(env: AppEnvironment, client: dz.hirz.app.api.HirzClient, info: dz.hirz.app.PairedInfo) {
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinDone by remember { mutableStateOf(false) }
    var lastBeat by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    var status by remember { mutableStateOf<dz.hirz.shared.api.SourceStatusDto?>(null) }
    LaunchedEffect(Unit) { status = env.companion.status(client) }
    if (status?.pausedReason == "age_18") { ConsentAt18(env, client) { status = status?.copy(pausedReason = null) }; return }
    if (SimpleMode.isSimple(status?.assetType)) { SimpleHome(env, info); return }
    LaunchedEffect(Unit) {
        env.companion.startSharing()
        while (true) {
            lastBeat = env.companion.lastHeartbeatMs(client)
            now = Clock.System.now().toEpochMilliseconds()
            delay(30_000)
        }
    }
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.sharing_with, info.circleName), style = MaterialTheme.typography.titleMedium)
        CompanionPermissionsCard()
        // A 2-second hold prevents pocket presses (design §6.2).
        Surface(
            color = MaterialTheme.colorScheme.error,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().height(160.dp).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val released = withTimeoutOrNull(2_000) { waitForUpOrCancellation() }
                    if (released == null) scope.launch { env.companion.sos(); sent = true }
                }
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(if (sent) Res.string.sos_sent else Res.string.sos_hold), color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (info.hasPin) {
            Text(stringResource(Res.string.cancel_alert), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8); pinDone = false }, label = { Text(stringResource(Res.string.enter_pin)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            // Identical result whatever the PIN meant: the server decides, the screen never tells.
            Button({ scope.launch { env.companion.submitPin(pin); pin = ""; pinDone = true; sent = false } }, enabled = pin.length >= 4,
                colors = ButtonDefaults.buttonColors()) { Text(stringResource(Res.string.save)) }
            if (pinDone) Text(stringResource(Res.string.pin_accepted))
        }
        Text(stringResource(Res.string.last_heartbeat, Captions.age(lastBeat?.let { now - it }, null, currentLang())))
        OutlinedButton({ env.companion.openBatterySettings() }) { Text(stringResource(Res.string.battery_settings)) }
    }
}

/**
 * The elder's screen (design §12.4): a very large SOS held for 2 seconds, an "I'm fine" button, both answered aloud.
 * Text 24 sp or more, touch targets far above 72 dp.
 */
/** At 18 sharing pauses (design §12.5.6): only the person, on this phone, can turn it back on. SOS keeps working. */
@Composable
private fun ConsentAt18(env: AppEnvironment, client: dz.hirz.app.api.HirzClient, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lang = currentLang()
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(when (lang) {
            "fr" -> "Vous avez 18 ans. Le partage de votre position est en pause. Votre famille ne le voit plus tant que vous n'acceptez pas. Le bouton SOS fonctionne toujours."
            "en" -> "You are 18. Sharing your location is paused; your family no longer sees it unless you agree. The SOS button still works."
            else -> "بلغت 18 سنة. مشاركة موقعك متوقفة، ولن تراه عائلتك إلا إذا وافقت. زر الاستغاثة يعمل دائمًا."
        }, style = MaterialTheme.typography.titleMedium)
        Button({ scope.launch { if (env.companion.consent(client)) done() else failed = true } }) {
            Text(when (lang) { "fr" -> "J'accepte de partager"; "en" -> "I agree to share"; else -> "أوافق على المشاركة" })
        }
        OutlinedButton({ scope.launch { env.companion.sos() } }) { Text(stringResource(Res.string.sos_hold)) }
        if (failed) Text(stringResource(Res.string.pair_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SimpleHome(env: AppEnvironment, info: dz.hirz.app.PairedInfo) {
    val scope = rememberCoroutineScope()
    val lang = currentLang()
    var sent by remember { mutableStateOf(false) }
    var fine by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { env.companion.startSharing() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.error,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().weight(3f).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val released = withTimeoutOrNull(2_000) { waitForUpOrCancellation() }
                    if (released == null) scope.launch {
                        env.companion.sos(); sent = true
                        env.companion.speak(SimpleMode.spokenAfterSos(lang), lang)
                    }
                }
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(if (sent) Res.string.sos_sent else Res.string.sos_hold), color = MaterialTheme.colorScheme.onError,
                    fontSize = 34.sp, textAlign = TextAlign.Center)
            }
        }
        Button(
            { scope.launch { env.companion.imFine(); fine = true; env.companion.speak(SimpleMode.spokenAfterFine(lang), lang) } },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Text(if (fine) when (lang) { "fr" -> "Envoyé ✓"; "en" -> "Sent ✓"; else -> "أُرسل ✓" }
                else when (lang) { "fr" -> "Je vais bien"; "en" -> "I'm fine"; else -> "أنا بخير" }, fontSize = 28.sp)
        }
        // Small, below the two big buttons: the permissions the family sets up once, and the PIN for a coerced elder.
        CompanionPermissionsCard()
        if (info.hasPin) {
            var pin by remember { mutableStateOf("") }
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(Res.string.enter_pin)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Button({ scope.launch { env.companion.submitPin(pin); pin = "" } }, enabled = pin.length >= 4) { Text(stringResource(Res.string.save)) }
        }
        OutlinedButton({ env.companion.openBatterySettings() }) { Text(stringResource(Res.string.battery_settings)) }
    }
}

