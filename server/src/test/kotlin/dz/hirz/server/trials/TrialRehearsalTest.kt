package dz.hirz.server.trials

import dz.hirz.server.jobs.DigestJob
import dz.hirz.server.jobs.TrialMetricsJob
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CONSENT_VERSION
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.CreateTrialRequest
import dz.hirz.shared.api.DigestDto
import dz.hirz.shared.api.DigestFeedbackRequest
import dz.hirz.shared.api.GuidedTestRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.JoinTrialRequest
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.Outcome
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.ReceiptRequest
import dz.hirz.shared.api.ReceiptState
import dz.hirz.shared.api.ReviewRequest
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.TrialDto
import dz.hirz.shared.api.TrialReportDto
import dz.hirz.shared.rules.Verdict
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val HOME = LatLon(36.7538, 3.0588)
private val ALGIERS = ZoneId.of("Africa/Algiers")
private val FIRST = LocalDate.parse("2026-11-01")
private const val DAYS = 10
private const val ASSETS = 10

private fun at(day: LocalDate, hhmm: String) = day.atTime(LocalTime.parse(hhmm)).atZone(ALGIERS).toInstant()

/**
 * A rehearsed trial (P8): one family, ten people, ten days, run through the real pipeline. It covers every phone's
 * hourly fixes, six guided SOS tests (received, claimed, closed by two guardians and reviewed as tests), some quiet P2
 * findings rated in the evening digest, and the nightly metrics. The report must pass the budget. Adding noise
 * (false P1s) must fail it on that line alone.
 */
class TrialRehearsalTest {
    @Test
    fun aRehearsedTrialPassesAndANoisyOneFailsOnTheRightLine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val trial: TrialDto = client.postJson("/v1/operator/trials", "test.ops", CreateTrialRequest("rehearsal", "2026-11-01", "2026-11-28")).body()
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
            client.put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
            client.postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Dar", PlaceKind.HOME, ShapeDto.Circle(HOME, 80.0)))
            client.postJson("/v1/circles/${circle.id}/trial", "test.mom", JoinTrialRequest(trial.id, CONSENT_VERSION))
            val people = (0 until ASSETS).map { i ->
                val a = client.newAsset("test.mom", circle.id, name = "P$i")
                a.id to client.pair(client.pairingCode("test.mom", a.id).code).body<PairResponse>().sourceToken
            }
            val digest = DigestJob(env.db, env.clock, env.notifier)
            val metrics = TrialMetricsJob(env.db, env.clock, env.rules)

            for (d in 0 until DAYS) {
                val day = FIRST.plusDays(d.toLong())
                // A guided SOS test on six of the ten days, on a different person each time.
                if (d < 6) {
                    val (assetId, token) = people[d]
                    env.clock.now = at(day, "11:00")
                    client.postJson("/v1/circles/${circle.id}/trial/tests", "test.mom", GuidedTestRequest(assetId, RuleId.SOS))
                    env.clock.now = at(day, "11:00").plusSeconds(5)
                    client.postJson("/v1/ingest", token, IngestBatch(listOf(IngestSignal("sos-$d", alarms = setOf(AlarmKind.SOS)))))
                    val inc = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
                        .single { it.rule == RuleId.SOS }
                    client.postJson("/v1/incidents/${inc.id}/receipts", "test.mom", ReceiptRequest(ReceiptState.DELIVERED))
                    env.clock.now = at(day, "11:01")
                    client.post("/v1/incidents/${inc.id}/claim") { bearerAuth("test.mom") }
                    env.clock.now = at(day, "11:10")
                    client.postJson("/v1/incidents/${inc.id}/close", "test.mom", CloseRequest(CloseReason.FAMILY))
                    client.post("/v1/incidents/${inc.id}/close/confirm") { bearerAuth("test.dad") }
                    client.postJson("/v1/incidents/${inc.id}/review", "test.mom", ReviewRequest(outcome = Outcome.TEST))
                }
                // A quiet P2 on three people, for the evening digest.
                env.db.connection.use { c ->
                    (0 until 3).forEach { k ->
                        c.createStatement().executeUpdate(
                            "INSERT INTO findings (asset_id, rule, tier, reasons, at) VALUES ('${people[k].first}', 'UNUSUAL_STOP', 'P2', '[]'::jsonb, '${at(day, "15:00")}'::timestamptz)",
                        )
                    }
                }
                // Every phone's day: a good fix each hour, uploaded before the evening.
                env.clock.now = at(day, "23:30")
                people.forEachIndexed { i, (_, token) ->
                    client.postJson("/v1/ingest", token, IngestBatch((0 until 24).map { h ->
                        val t = at(day, "%02d:20".format(h)).toEpochMilli()
                        IngestSignal("f$i-$d-$h", t, HOME, 10.0)
                    }))
                }
                digest.runOnce()
                client.get("/v1/circles/${circle.id}/digests") { bearerAuth("test.mom") }.body<List<DigestDto>>()
                    .firstOrNull { it.day == day.toString() }?.let { dg ->
                        dg.items.indices.forEach { idx -> client.postJson("/v1/digests/${dg.id}/feedback", "test.mom", DigestFeedbackRequest(idx, idx != 1)) }
                    }
            }
            env.clock.now = at(FIRST.plusDays(DAYS.toLong()), "04:00")
            (0 until DAYS).forEach { metrics.runFor(FIRST.plusDays(it.toLong())) }

            val report: TrialReportDto = client.get("/v1/operator/trials/${trial.id}/report") { bearerAuth("test.ops") }.body()
            println("Rehearsal report: ${report.lines}")
            assertEquals(ASSETS * DAYS, report.metrics.assetDays)
            assertTrue(report.passed, report.lines.filter { it.verdict != Verdict.PASS }.toString())

            // Noise: four false P1s (the rule was wrong) on day 2, reviewed by mum.
            env.db.connection.use { c ->
                val mom = c.createStatement().executeQuery("SELECT member_id FROM circle_members WHERE circle_id = '${circle.id}' AND role = 'owner'")
                    .use { it.next(); it.getString(1) }
                repeat(4) { k ->
                    val opened = at(FIRST.plusDays(1), "1${k}:30")
                    c.createStatement().executeUpdate(
                        """WITH i AS (INSERT INTO incidents (asset_id, circle_id, rule, tier, state, opened_at, closed_at)
                             VALUES ('${people[9].first}', '${circle.id}', 'PLACE_EXIT', 'P1', 'closed', '$opened'::timestamptz, '$opened'::timestamptz) RETURNING id)
                           INSERT INTO incident_reviews (incident_id, member_id, false_alarm, outcome, at) SELECT id, '$mom', true, 'false_rule', '$opened'::timestamptz FROM i""",
                    )
                }
            }
            metrics.runFor(FIRST.plusDays(1))
            val noisy: TrialReportDto = client.get("/v1/operator/trials/${trial.id}/report") { bearerAuth("test.ops") }.body()
            assertEquals(listOf("false_p1"), noisy.lines.filter { it.verdict != Verdict.PASS }.map { it.id }, noisy.lines.toString())
        }
    }
}
