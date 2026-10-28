package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/** Severity (design §5): P0 immediate full-screen to everyone, P1 alert with an ack window, P2 in-app only. */
@Serializable
enum class Tier { P0, P1, P2 }

@Serializable
enum class RuleId { SOS, DURESS, PLACE_EXIT, FORBIDDEN_ENTRY, SILENT_SOURCE }

/** A machine reason the app renders through i18n, e.g. `left_place{place, readings, distanceM, accuracyM, ageS}`. */
@Serializable
data class Reason(val code: String, val args: Map<String, String> = emptyMap())
