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
            // Only UNREGISTERED proves the token is dead; INVALID_ARGUMENT can be our payload's fault.
            val invalid = e.messagingErrorCode == MessagingErrorCode.UNREGISTERED
            SendResult.Failed(e.messagingErrorCode?.name ?: e.message ?: "fcm_error", tokenInvalid = invalid)
        }
    }

    companion object {
        fun dataFor(m: AlertMessage): Map<String, String> = buildMap {
            put("t", m.kind)
            m.incidentId?.let { put("id", it.toString()) }
            m.tier?.let { put("tier", it) }
            put("asset", m.assetName)
            // RUN_AWAY_SPEED and PAST_ADDRESS_HEADING exist only for autism and dementia: on a lock screen or at FCM,
            // the rule would name the condition. The app reads the reason from the incident itself.
            m.rule?.let { put("rule", if (it in CONDITION_RULES) "PERSON_ALERT" else it) }
        }

        fun ttlMsFor(tier: String?): Long = when (tier) {
            "P0" -> 60_000L
            "P1" -> 180_000L
            else -> 900_000L
        }
    }
}

/** Rules that exist only for one condition: never named outside the asset's profile. */
internal val CONDITION_RULES = setOf("RUN_AWAY_SPEED", "PAST_ADDRESS_HEADING")
