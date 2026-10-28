package dz.hirz.server.notify

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** FCM HTTP v1, data-only, high priority. The payload never carries coordinates or reasons (design §12.3). */
class FcmNotifier(app: FirebaseApp) : Notifier {
    private val messaging = FirebaseMessaging.getInstance(app)

    override suspend fun send(to: PushTarget, message: AlertMessage): SendResult = withContext(Dispatchers.IO) {
        val fcm = Message.builder()
            .setToken(to.token)
            .putAllData(dataFor(message))
            .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).setTtl(ttlMsFor(message.tier)).build())
            .build()
        try {
            SendResult.Ok(messaging.send(fcm))
        } catch (e: FirebaseMessagingException) {
            val invalid = e.messagingErrorCode in setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT)
            SendResult.Failed(e.messagingErrorCode?.name ?: e.message ?: "fcm_error", tokenInvalid = invalid)
        }
    }

    companion object {
        fun dataFor(m: AlertMessage): Map<String, String> = buildMap {
            put("t", m.kind)
            m.incidentId?.let { put("id", it.toString()) }
            m.tier?.let { put("tier", it) }
            put("asset", m.assetName)
            m.rule?.let { put("rule", it) }
        }

        fun ttlMsFor(tier: String?): Long = when (tier) {
            "P0" -> 60_000L
            "P1" -> 180_000L
            else -> 900_000L
        }
    }
}
