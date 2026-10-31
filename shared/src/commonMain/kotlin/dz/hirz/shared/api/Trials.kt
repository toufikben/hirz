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
data class CircleTrialDto(val trial: TrialDto, val joinedAtMs: Long, /** Past its end: only leaving remains. */ val ended: Boolean = false,
    /** The circle consented to an older text: it must consent again to keep counting. */ val consentOutdated: Boolean = false)

/** The family is about to test [rule] on [assetId] for real: it must ring. */
@Serializable
data class GuidedTestRequest(val assetId: String, val rule: RuleId)

@Serializable
data class GuidedTestDto(val id: String, val assetId: String, val rule: RuleId, val startedAtMs: Long)

/** The operator's view of a trial (P8): aggregates only, judged against the budget. */
@Serializable
data class TrialReportDto(
    val trial: TrialDto,
    val circles: Int,
    val metrics: dz.hirz.shared.rules.TrialMetrics,
    val lines: List<dz.hirz.shared.rules.BudgetLine>,
    val passed: Boolean,
    /** Findings per "RULE/TIER" over the whole trial. */
    val findings: Map<String, Int>,
    /** Problem reports from the trial's circles, newest first. */
    val problems: List<ProblemReportDto> = emptyList(),
)

/** "Report a problem" (P8): free text and the app's version and device model. No location, ever. */
@Serializable
data class ProblemReportRequest(val circleId: String, val text: String, val appVersion: String, val device: String)

/** A problem report as the operator sees it: the circle's trial pseudonym, never the circle. */
@Serializable
data class ProblemReportDto(val circlePseudo: String, val text: String, val appVersion: String, val device: String, val atMs: Long)
