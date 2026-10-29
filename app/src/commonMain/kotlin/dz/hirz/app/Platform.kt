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
}
