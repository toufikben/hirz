package dz.hirz.app

import androidx.compose.runtime.Composable
import dz.hirz.app.api.HirzClient
import kotlinx.coroutines.flow.StateFlow

/** The FCM registration token of this install, or null when push is unavailable. */
expect suspend fun platformPushToken(): String?

/** Shows which alarm permissions are missing (notifications, full screen, Do Not Disturb), never hiding a refusal. */
@Composable
expect fun AlarmReadinessCard()

/** Shows and requests the location permissions companion mode needs ("all the time"). */
@Composable
expect fun CompanionPermissionsCard()

data class PairedInfo(val circleName: String, val assetName: String, val hasPin: Boolean)

/** SMS-gateway mode (design §12.3.3): only the `gateway` build may send and read SMS. */
interface GatewayPort {
    /** False in the Play build, which declares no SMS permission. */
    val supported: Boolean
    /** The circle this phone serves as gateway, or null. */
    val pairedCircle: StateFlow<String?>
    suspend fun pair(client: HirzClient, code: String): Result<Unit>
    /** Asks for the SMS permissions and starts the foreground service. */
    fun start()
    fun unpair()
}

/** Companion-mode capabilities the platform provides (design §6.2). */
interface CompanionPort {
    val paired: StateFlow<PairedInfo?>
    suspend fun scanQr(): String?
    suspend fun pair(client: HirzClient, code: String, adult: Boolean, consent: Boolean): Result<Unit>
    fun startSharing()
    suspend fun sos()
    suspend fun submitPin(pin: String)
    suspend fun lastHeartbeatMs(client: HirzClient): Long?
    fun openBatterySettings()
    /** The paired asset's type, for the layout; null when unknown or offline. */
    suspend fun assetType(client: HirzClient): dz.hirz.shared.asset.AssetType?
    /** "I'm fine": a check-in reply sent now. */
    suspend fun imFine()
    /** Says [text] aloud in [lang] (ar or fr); silent when the phone has no voice for it. */
    fun speak(text: String, lang: String)
}
