package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/**
 * Adding a GSM tracker (design §3 wizard, §12.5.1). The claim activates only when the device reports to Hirz after
 * the claim was made, which proves the claimant reconfigured it; knowing an IMEI is not enough.
 */
@Serializable
data class TrackerClaimRequest(
    val model: String,
    val imei: String,
    val simPhone: String? = null,
    val apn: String? = null,
    val intervalS: Int = 60,
    /** The device's current password; the catalogue default when null. */
    val devicePassword: String? = null,
    /** Number the tracker calls or texts on SOS; that setup SMS is skipped when null. */
    val sosPhone: String? = null,
)

/** One SMS the owner sends from their own phone to the tracker's SIM; [purpose] is password, server, apn, interval, sos_number or status. */
@Serializable
data class SmsCommandDto(val to: String? = null, val text: String, val purpose: String)

/** [state]: `pending`, `active` or `expired`. */
@Serializable
data class TrackerClaimDto(
    val claimId: String,
    val state: String,
    val expiresAtMs: Long,
    val sourceId: String? = null,
    val commands: List<SmsCommandDto> = emptyList(),
)
