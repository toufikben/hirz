package dz.hirz.shared.signal

import kotlinx.serialization.Serializable

/** Device-raised alarms carried by the signal contract (design §3). */
@Serializable
enum class AlarmKind { SOS, TAMPER, REMOVAL, POWER_CUT, JAMMING, FALL, DURESS }
