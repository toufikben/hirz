package dz.hirz.server.notify

import java.util.UUID

data class PushTarget(val memberId: UUID?, val token: String)

/**
 * What a push may carry. Deliberately no coordinates and no reasons: a lock screen is public,
 * and the app fetches details after authenticating (design §12.3).
 */
data class AlertMessage(
    val kind: String,
    val incidentId: UUID?,
    val tier: String?,
    val assetName: String,
    val rule: String?,
)

sealed interface SendResult {
    data class Ok(val providerId: String) : SendResult
    data class Failed(val error: String, val tokenInvalid: Boolean) : SendResult
}

fun interface Notifier {
    suspend fun send(to: PushTarget, message: AlertMessage): SendResult
}

/** Used when no push provider is configured: records nothing, delivers nothing, never pretends success. */
object DisabledNotifier : Notifier {
    override suspend fun send(to: PushTarget, message: AlertMessage): SendResult =
        SendResult.Failed("push disabled: no Firebase project configured", tokenInvalid = false)
}
