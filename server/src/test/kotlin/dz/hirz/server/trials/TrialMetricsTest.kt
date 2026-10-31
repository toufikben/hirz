package dz.hirz.server.trials

import dz.hirz.server.jobs.TrialMetricsJob
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CONSENT_VERSION
import dz.hirz.shared.api.CreateTrialRequest
import dz.hirz.shared.api.GuidedTestRequest
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.JoinTrialRequest
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.TrialDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val DAY = LocalDate.parse("2026-11-01")

private class Pilot(val circleId: String, val assetId: String, val momId: String)

/** A circle enrolled in a November trial, with one child named Yanis. */
private suspend fun HttpClient.pilot(env: TestEnv, enrol: Boolean = true, owner: String = "test.mom"): Pilot {
    val trial: TrialDto = postJson("/v1/operator/trials", "test.ops", CreateTrialRequest("pilot", "2026-11-01", "2026-11-28")).body()
    val circle = newCircle(owner)
    val kid = newAsset(owner, circle.id, name = "Yanis")
    pair(pairingCode(owner, kid.id).code) // only assets with a source count
    if (enrol) postJson("/v1/circles/${circle.id}/trial", owner, JoinTrialRequest(trial.id, CONSENT_VERSION))
    val mom = env.db.connection.use { c ->
        c.createStatement().executeQuery("SELECT member_id FROM circle_members WHERE circle_id = '${circle.id}' AND role = 'owner'").use { it.next(); it.getString(1) }
    }
    return Pilot(circle.id, kid.id, mom)
}

/** A closed incident opened at [opened]; reviewed by mom as [outcome] when given; claimed after [claimS] s. */
private fun incident(env: TestEnv, p: Pilot, rule: String, tier: String, opened: String, outcome: String? = null, claimS: Long? = null, deliveredS: Long? = null): String =
    env.db.connection.use { c ->
        val at = Instant.parse(opened)
        val id = c.createStatement().executeQuery(
            """INSERT INTO incidents (asset_id, circle_id, rule, tier, state, opened_at, closed_at, claimed_at)
               VALUES ('${p.assetId}', '${p.circleId}', '$rule', '$tier', 'closed', '$at'::timestamptz, '${at.plusSeconds(3600)}'::timestamptz,
               ${claimS?.let { "'${at.plusSeconds(it)}'::timestamptz" } ?: "NULL"}) RETURNING id""",
        ).use { it.next(); it.getString(1) }
        outcome?.let {
            val falseAlarm = it == "false_rule" || it == "false_device"
            c.createStatement().executeUpdate(
                """INSERT INTO incident_reviews (incident_id, member_id, false_alarm, outcome, at)
                   VALUES ('$id', '${p.momId}', $falseAlarm, '$it', '${at.plusSeconds(7200)}'::timestamptz)""",
            )
        }
        deliveredS?.let {
            c.createStatement().executeUpdate(
                """INSERT INTO deliveries (incident_id, member_id, channel, sent_at, delivered_at)
                   VALUES ('$id', '${p.momId}', 'push', '$at'::timestamptz, '${at.plusSeconds(it)}'::timestamptz)""",
            )
        }
        id
    }

private fun rows(env: TestEnv): List<Map<String, Any?>> = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT * FROM trial_daily ORDER BY asset_pseudo").use { rs ->
        val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
        buildList { while (rs.next()) add(cols.associateWith { col -> rs.getObject(col).let { v -> if (v is java.sql.Array) (v.array as Array<*>).toList() else v?.toString() } }) }
    }
}

private fun job(env: TestEnv) = TrialMetricsJob(env.db, env.clock, env.rules)

class TrialMetricsTest {
    @Test
    fun aReviewDaysLaterIsCounted() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            val id = incident(env, p, "SOS", "P0", "2026-11-01T10:00:00Z")
            env.clock.now = Instant.parse("2026-11-02T04:00:00Z")
            job(env).runAll()
            assertEquals("0", rows(env).single { it["day"] == "2026-11-01" }["reviewed_p0p1"])
            env.db.connection.use { c ->
                c.createStatement().executeUpdate("INSERT INTO incident_reviews (incident_id, member_id, false_alarm, outcome, at) VALUES ('$id', '${p.momId}', true, 'false_device', now())")
            }
            env.clock.now = Instant.parse("2026-11-05T04:00:00Z")
            job(env).runAll()
            val day1 = rows(env).single { it["day"] == "2026-11-01" }
            assertEquals("1", day1["false_p0"], "a false P0 judged three days later still counts")
        }
    }

    @Test
    fun testOrUnsureWithoutAGuidedTestIsNotADecidedOutcome() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            incident(env, p, "PLACE_EXIT", "P1", "2026-11-01T10:00:00Z", "test")
            incident(env, p, "PLACE_EXIT", "P1", "2026-11-01T12:00:00Z", "unsure")
            job(env).runFor(DAY)
            val r = rows(env).single()
            assertEquals("2", r["closed_p0p1"]); assertEquals("0", r["reviewed_p0p1"], "neither answer counts toward outcome coverage")
            assertEquals("1", r["unmatched_tests"]); assertEquals("1", r["unsure_reviews"])
        }
    }

    @Test
    fun nothingBeforeConsent() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            env.clock.now = Instant.parse("2026-11-03T09:00:00Z") // the circle joins on the 3rd
            val p = client.pilot(env)
            incident(env, p, "PLACE_EXIT", "P1", "2026-11-01T10:00:00Z", "false_rule")
            job(env).runFor(DAY)
            assertTrue(rows(env).isEmpty(), "the 1st was before the family agreed")
        }
    }

    @Test
    fun undeliveredAndUnclaimedP0sAreCounted() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            incident(env, p, "SOS", "P0", "2026-11-01T10:00:00Z", "real")
            job(env).runFor(DAY)
            val r = rows(env).single()
            assertEquals("1", r["p0_alarms"]); assertEquals("1", r["p0_undelivered"]); assertEquals("1", r["p0_unclaimed"])
        }
    }

    @Test
    fun countsFalseAlarmsPerRuleAndTier() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            incident(env, p, "PLACE_EXIT", "P1", "2026-11-01T10:00:00Z", "false_rule")
            incident(env, p, "PLACE_EXIT", "P1", "2026-11-01T12:00:00Z", "false_device")
            incident(env, p, "SOS", "P0", "2026-11-01T14:00:00Z", "real")
            incident(env, p, "PLACE_EXIT", "P1", "2026-11-01T15:00:00Z") // not reviewed
            env.db.connection.use { c ->
                c.createStatement().executeUpdate("INSERT INTO findings (asset_id, rule, tier, reasons, at) VALUES ('${p.assetId}', 'UNUSUAL_STOP', 'P2', '[]'::jsonb, '2026-11-01T07:00:00Z'::timestamptz)")
            }
            job(env).runFor(DAY)
            val r = rows(env).single()
            assertEquals("2", r["false_p1"]); assertEquals("0", r["false_p0"])
            assertEquals("4", r["closed_p0p1"]); assertEquals("3", r["reviewed_p0p1"])
            assertTrue("UNUSUAL_STOP/P2" in r["findings"].toString(), r["findings"].toString())
        }
    }

    @Test
    fun guidedTestsCountRangAndMissed() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            env.clock.now = Instant.parse("2026-11-01T10:00:00Z")
            client.postJson("/v1/circles/${p.circleId}/trial/tests", "test.mom", GuidedTestRequest(p.assetId, RuleId.SOS))
            incident(env, p, "SOS", "P0", "2026-11-01T10:00:20Z", "false_rule", deliveredS = 3) // rang; a test is never a false alarm
            env.clock.now = Instant.parse("2026-11-01T13:00:00Z")
            client.postJson("/v1/circles/${p.circleId}/trial/tests", "test.mom", GuidedTestRequest(p.assetId, RuleId.SOS)) // never rang
            env.clock.now = Instant.parse("2026-11-02T04:00:00Z")
            job(env).runFor(DAY)
            val r = rows(env).single()
            assertEquals("2", r["guided_tests"]); assertEquals("1", r["guided_missed"]); assertEquals("2", r["p0_guided"])
            assertEquals("0", r["false_p0"], "the incident a guided test caused is a test, whatever the review says")
        }
    }

    @Test
    fun pushLatencyFromReceipts() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            incident(env, p, "SOS", "P0", "2026-11-01T10:00:00Z", "real", claimS = 90, deliveredS = 7)
            job(env).runFor(DAY)
            val r = rows(env).single()
            assertEquals(listOf("7000"), (r["p0_delivery_ms"] as List<*>).map { it.toString() })
            assertEquals(listOf("90000"), (r["p0_ack_ms"] as List<*>).map { it.toString() })
        }
    }

    @Test
    fun coverageFromFixes() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            val token = client.pair(client.pairingCode("test.mom", p.assetId).code).body<PairResponse>().sourceToken
            // 13 hours with a good fix, from 08:00 to 20:00 Algiers (07:00Z to 19:00Z).
            (7..19).forEach { h ->
                env.clock.now = Instant.parse("2026-11-01T%02d:10:00Z".format(h))
                client.postJson("/v1/ingest", token, IngestBatch(listOf(IngestSignal("h$h", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
            }
            env.clock.now = Instant.parse("2026-11-02T04:00:00Z")
            job(env).runFor(DAY)
            val r = rows(env).single()
            assertEquals("13", r["live_hours"]); assertEquals("13", r["fresh_hours"])
        }
    }

    @Test
    fun noIdentifiersStored() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            incident(env, p, "SOS", "P0", "2026-11-01T10:00:00Z", "real", claimS = 60, deliveredS = 5)
            job(env).runFor(DAY)
            val text = rows(env).single().toString()
            listOf(p.assetId, p.circleId, p.momId, "Yanis", "36.75").forEach { assertFalse(it in text, "trial data holds $it: $text") }
        }
    }

    @Test
    fun rerunIsIdempotent() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pilot(env)
            incident(env, p, "PLACE_EXIT", "P1", "2026-11-01T10:00:00Z", "false_rule")
            job(env).runFor(DAY)
            val first = rows(env)
            job(env).runFor(DAY)
            assertEquals(first, rows(env))
        }
    }

    @Test
    fun onlyEnrolledCircles() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            client.pilot(env)
            val other = client.pilot(env, enrol = false, owner = "test.zoe")
            incident(env, other, "PLACE_EXIT", "P1", "2026-11-01T10:00:00Z", "false_rule")
            job(env).runFor(DAY)
            assertEquals(1, rows(env).size, "the other family never joined")
            assertEquals("0", rows(env).single()["false_p1"])
        }
    }
}
