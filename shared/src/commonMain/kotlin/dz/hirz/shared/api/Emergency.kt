package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/** The family's shared secret for phone calls and strangers (design §5.10); shown only after re-authentication. */
@Serializable
data class CodeWordDto(val word: String)

/**
 * A public emergency number. [verifiedAtMs] is null until someone in the circle confirmed it works in their
 * wilaya; the app then shows "check for your wilaya" next to it.
 */
@Serializable
data class EmergencyNumberDto(val number: String, val label: String, val verifiedAtMs: Long? = null)

@Serializable
data class EmergencyConfirmRequest(val circleId: String)
