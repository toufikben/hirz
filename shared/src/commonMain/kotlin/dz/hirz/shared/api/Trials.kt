package dz.hirz.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What an alarm turned out to be (P8), asked after every closed P0/P1. A guided test is never a false alarm, and
 * "not sure" is not counted either way.
 */
@Serializable
enum class Outcome(val isFalseAlarm: Boolean) {
    @SerialName("real") REAL(false),
    @SerialName("false_device") FALSE_DEVICE(true),
    @SerialName("false_rule") FALSE_RULE(true),
    @SerialName("test") TEST(false),
    @SerialName("unsure") UNSURE(false),
}
