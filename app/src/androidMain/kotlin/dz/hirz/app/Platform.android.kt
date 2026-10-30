package dz.hirz.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dz.hirz.app.api.HirzClient
import dz.hirz.app.companion.CompanionCredentials
import dz.hirz.app.companion.CompanionEngine
import dz.hirz.app.companion.CompanionSignals
import dz.hirz.app.companion.CompanionVault
import dz.hirz.app.companion.LocationService
import dz.hirz.app.companion.PinPad
import dz.hirz.app.generated.resources.Res
import dz.hirz.app.generated.resources.alarm_ok_all_set
import dz.hirz.app.generated.resources.alarm_ready_title
import dz.hirz.app.generated.resources.grant
import dz.hirz.app.generated.resources.perm_dnd
import dz.hirz.app.generated.resources.perm_fullscreen
import dz.hirz.app.generated.resources.perm_notifications
import dz.hirz.app.generated.resources.permissions_location
import dz.hirz.app.push.AlarmNotifier
import dz.hirz.app.push.currentFcmToken
import dz.hirz.shared.api.PairRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

actual suspend fun platformPushToken(): String? = currentFcmToken()

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ReadinessCard(title: String, rows: List<Pair<String, () -> Unit>>, allSet: String?) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (rows.isEmpty() && allSet != null) Text(allSet)
            rows.forEach { (label, fix) ->
                Text(label, color = MaterialTheme.colorScheme.error)
                TextButton(fix) { Text(stringResource(Res.string.grant)) }
            }
        }
    }
}

/** Re-reads permission state every few seconds, because the user grants some of them in system settings. */
@Composable
private fun tick(): Int {
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); n++ } }
    return n
}

@Composable
actual fun AlarmReadinessCard() {
    val context = LocalContext.current
    tick()
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val nm = context.getSystemService(NotificationManager::class.java)
    AlarmNotifier.ensureChannel(context)
    val rows = buildList {
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            add(stringResource(Res.string.perm_notifications) to { notify.launch(Manifest.permission.POST_NOTIFICATIONS) })
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            add(stringResource(Res.string.perm_fullscreen) to {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
        if (!nm.isNotificationPolicyAccessGranted) {
            add(stringResource(Res.string.perm_dnd) to {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    }
    ReadinessCard(stringResource(Res.string.alarm_ready_title), rows, stringResource(Res.string.alarm_ok_all_set))
}

@Composable
actual fun CompanionPermissionsCard() {
    val context = LocalContext.current
    tick()
    // Sharing starts as soon as location is granted (it never starts without it: P1 review #1).
    val restart = { LocationService.start(context.applicationContext) }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { restart() }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { restart() }
    val rows = buildList {
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val needed = buildList {
            if (!fine) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) add(stringResource(Res.string.permissions_location) to { foreground.launch(needed.toTypedArray()) })
        else if (Build.VERSION.SDK_INT >= 29 && !granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            // Android requires asking for "all the time" separately, after foreground location (design §8.6).
            add(stringResource(Res.string.permissions_location) to { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) })
        }
    }
    if (rows.isNotEmpty()) ReadinessCard(stringResource(Res.string.alarm_ready_title), rows, null)
}

/** Companion capabilities on Android: encrypted credentials, foreground service, QR scanning. */
class AndroidCompanionPort(private val activity: ComponentActivity) : CompanionPort {
    private val vault = CompanionVault(activity.applicationContext)
    private val state = MutableStateFlow(vault.credentials()?.let { PairedInfo(it.circleName, it.assetName, it.pinSalt != null) })
    override val paired: StateFlow<PairedInfo?> = state

    private var pendingScan: CompletableDeferred<String?>? = null
    private val scanLauncher = activity.registerForActivityResult(ScanContract()) { result -> pendingScan?.complete(result.contents) }

    override suspend fun scanQr(): String? {
        val deferred = CompletableDeferred<String?>()
        pendingScan = deferred
        scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false))
        return deferred.await()
    }

    override suspend fun pair(client: HirzClient, code: String, adult: Boolean, consent: Boolean): Result<Unit> = runCatching {
        val push = currentFcmToken()
        val r = client.pair(PairRequest(code.trim(), Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME, push, adult, consent))
        vault.save(CompanionCredentials(r.sourceId, r.sourceToken, r.pinSalt, r.circleName, r.assetName))
        state.value = PairedInfo(r.circleName, r.assetName, r.pinSalt != null)
    }

    override fun startSharing() {
        if (vault.credentials() != null) LocationService.start(activity.applicationContext)
    }

    override suspend fun sos() {
        CompanionEngine.cadence.sosPressed(System.currentTimeMillis())
        CompanionEngine.sendNow(activity.applicationContext, CompanionSignals.sos())
    }

    override suspend fun submitPin(pin: String) {
        val salt = vault.credentials()?.pinSalt ?: return
        CompanionEngine.sendNow(activity.applicationContext, PinPad.submit(pin, salt).signal)
    }

    override suspend fun lastHeartbeatMs(client: HirzClient): Long? =
        vault.credentials()?.let { runCatching { client.sourceMe(it.sourceToken).lastHeartbeatAtMs }.getOrNull() }

    override suspend fun assetType(client: HirzClient): dz.hirz.shared.asset.AssetType? =
        vault.credentials()?.let { runCatching { client.sourceMe(it.sourceToken).assetType }.getOrNull() }

    override suspend fun imFine() {
        CompanionEngine.sendNow(activity.applicationContext, CompanionSignals.checkIn(dz.hirz.shared.api.CheckInReply.OK))
    }

    private var tts: android.speech.tts.TextToSpeech? = null

    override fun speak(text: String, lang: String) {
        val locale = java.util.Locale.forLanguageTag(if (lang == "fr") "fr-FR" else "ar")
        val existing = tts
        if (existing != null) { existing.language = locale; existing.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "hirz"); return }
        tts = android.speech.tts.TextToSpeech(activity.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) tts?.let { it.language = locale; it.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "hirz") }
        }
    }

    override fun openBatterySettings() {
        // The settings list, not the one-tap exemption request (that needs a restricted permission).
        runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
