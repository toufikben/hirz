package dz.hirz.server.rules

import dz.hirz.server.jobs.SilenceScanner
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.pairedChild
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SilenceTest {
    private fun scanner(env: TestEnv) = SilenceScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentSink.NONE))

    private fun findings(env: TestEnv): List<String> = env.db.connection.use { c ->
        c.createStatement().executeQuery("SELECT rule || ':' || tier || ':' || (reasons->0->>'code') FROM findings ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    @Test
    fun noFindingBeforeThreshold() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (_, _, paired) = client.pairedChild()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("a", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
            env.clock.advance(4.minutes)
            scanner(env).scanOnce()
            assertEquals(emptyList(), findings(env))
        }
    }

    @Test
    fun silent_dead_after_5_min_once_per_episode() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (_, _, paired) = client.pairedChild()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("a", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
            val s = scanner(env)
            env.clock.advance(5.minutes + 1.seconds)
            s.scanOnce()
            env.clock.advance(30.seconds)
            s.scanOnce()
            assertEquals(listOf("SILENT_SOURCE:P2:silent_dead"), findings(env))
        }
    }

    @Test
    fun silent_alive_after_10_min_no_fix() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (_, _, paired) = client.pairedChild()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("fix", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
            repeat(11) { i ->
                env.clock.advance(1.minutes)
                client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("hb$i"))))
            }
            scanner(env).scanOnce()
            assertEquals(listOf("SILENT_SOURCE:P2:silent_alive"), findings(env))
        }
    }

    @Test
    fun silenceDuringOpenIncidentAppendsReasonInstead() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, assetId, paired) = client.pairedChild()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("a", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
            val incident = env.db.connection.use { c ->
                c.createStatement().executeQuery(
                    "INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('$assetId', '$circleId', 'SOS', 'P0', now()) RETURNING id",
                ).use { it.next(); it.getString(1) }
            }
            env.clock.advance(6.minutes)
            scanner(env).scanOnce()
            assertEquals(emptyList(), findings(env))
            env.db.connection.use { c ->
                c.createStatement().executeQuery(
                    "SELECT count(*) FROM incident_events WHERE incident_id = '$incident' AND kind = 'reason' AND data->>'code' = 'silence_during_incident'",
                ).use { it.next(); assertEquals(1, it.getInt(1)) }
            }
        }
    }
}
