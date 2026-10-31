package dz.hirz.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Severity (design §5): P0 immediate full-screen to everyone, P1 alert with an ack window, P2 in-app only. */
@Serializable
enum class Tier { P0, P1, P2 }

@Serializable
enum class RuleId {
    SOS, DURESS, PLACE_EXIT, FORBIDDEN_ENTRY, SILENT_SOURCE, NO_RESPONSE_CHECKIN,
    /** Vehicles (design §4.3, §12.2). */
    ARMED_MOVEMENT, TOWING, POWER_CUT, JAMMING, PARKED_TILT, DOOR_OPEN_ARMED, ANTENNA_CUT,
    /** People profiles (design §4.3, §12.2, P5). */
    HAZARD_PROXIMITY, NIGHT_EXIT, RUN_AWAY_SPEED, PAST_ADDRESS_HEADING, CURFEW, COASTAL_NIGHT,
    /** Livestock and presence (design §4.3, §12.2, P6). */
    HERD_NIGHT_MOVE, HERD_DISPERSION, COLLAR_CUT, ANIMAL_STILL, VEHICLE_SPEED_UNEXPLAINED, LEASH_BREAK, KEYLESS_NO_OWNER,
    /** Routines (design §4.3, §12.2, P7). */
    MISSED_ARRIVAL, STATIONARY_AFTER_DISMISSAL, UNUSUAL_STOP, PACING_LOOP,
}

/** A machine reason the app renders through i18n, e.g. `left_place{place, readings, distanceM, accuracyM, ageS}`. */
@Serializable
data class Reason(val code: String, val args: Map<String, String> = emptyMap())

@Serializable
enum class ReceiptState {
    @SerialName("delivered") DELIVERED,
    @SerialName("opened") OPENED,
}

@Serializable
data class ReceiptRequest(val state: ReceiptState)

@Serializable
enum class CloseReason {
    @SerialName("found_safe") FOUND_SAFE,
    @SerialName("family") FAMILY,
    @SerialName("technical") TECHNICAL,
    @SerialName("measurement_error") MEASUREMENT_ERROR,
    @SerialName("recovered") RECOVERED,
}

@Serializable
data class CloseRequest(val reason: CloseReason)

@Serializable
data class DeliveryDto(
    val memberId: String,
    val channel: String,
    val sentAtMs: Long? = null,
    val deliveredAtMs: Long? = null,
    val openedAtMs: Long? = null,
    val error: String? = null,
)

@Serializable
data class IncidentEventDto(val kind: String, val memberId: String? = null, val data: JsonObject = JsonObject(emptyMap()), val atMs: Long)

/** "Claimed" means someone is handling it; it never lowers the tier or closes the incident (design §5). */
@Serializable
data class IncidentDto(
    val id: String,
    val assetId: String,
    val assetName: String,
    val circleId: String,
    val tier: Tier,
    val rule: RuleId,
    val state: String,
    val silent: Boolean = false,
    val reasons: List<Reason> = emptyList(),
    val openedAtMs: Long,
    val ownerMemberId: String? = null,
    val claimedAtMs: Long? = null,
    val closedAtMs: Long? = null,
    val closeReason: CloseReason? = null,
    val events: List<IncidentEventDto> = emptyList(),
    val deliveries: List<DeliveryDto> = emptyList(),
    val pendingClose: PendingCloseDto? = null,
    val escalation: EscalationViewDto? = null,
)

/**
 * Where the escalation ladder stands (design §5.3): [stage] is GUARDIANS, CONTACTS, EVERYONE_UNAVAILABLE, CLAIMED or
 * CLOSED; while GUARDIANS, [waitingForMemberId] is the member asked to take it, until [deadlineMs].
 */
@Serializable
data class EscalationViewDto(val stage: String, val waitingForMemberId: String? = null, val deadlineMs: Long? = null)

/** A close waiting for a second guardian, or for the only guardian's second tap, before [expiresAtMs] (design §5.7). */
@Serializable
data class PendingCloseDto(val requestedBy: String, val reason: CloseReason, val atMs: Long, val expiresAtMs: Long)

/** Something a member did about an incident; shown to everyone so nobody repeats it (design §5.8). */
@Serializable
data class ActionRequest(val kind: String, val value: String? = null)

/** After close: was it a false alarm? Counted per rule for the false-alarm budget (design §5.9). */
@Serializable
data class ReviewRequest(val falseAlarm: Boolean, val note: String? = null)

@Serializable
data class CheckInSentDto(val sent: Int)

/** A guardian's own position, sent only while an incident they can see is open (design §5.11). */
@Serializable
data class MyLocationRequest(val position: LatLon, val accuracyM: Double? = null)
