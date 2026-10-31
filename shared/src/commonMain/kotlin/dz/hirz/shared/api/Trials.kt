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

/** The consent text's version; the app shows that text and the server refuses an older one. */
const val CONSENT_VERSION = 1

/** A field trial (P8); dates are ISO local days, Africa/Algiers, both inclusive. */
@Serializable
data class TrialDto(val id: String, val name: String, val startsOn: String, val endsOn: String)

@Serializable
data class CreateTrialRequest(val name: String, val startsOn: String, val endsOn: String)

@Serializable
data class JoinTrialRequest(val trialId: String, val consentVersion: Int)

/** The circle's trial, if it is in one. */
@Serializable
data class CircleTrialDto(val trial: TrialDto, val joinedAtMs: Long)

/** The family is about to test [rule] on [assetId] for real: it must ring. */
@Serializable
data class GuidedTestRequest(val assetId: String, val rule: RuleId)

@Serializable
data class GuidedTestDto(val id: String, val assetId: String, val rule: RuleId, val startedAtMs: Long)
