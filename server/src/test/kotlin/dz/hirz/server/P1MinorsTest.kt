package dz.hirz.server

import dz.hirz.server.auth.Codes
import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.SilenceScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestResult
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ShapeDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

private val SCHOOL = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(SCHOOL.lat + m / 111_320.0, SCHOOL.lon)

class P1MinorsTest {
    /** #11: two sources of one asset report at once; the boundary state must not lose readings. */
    @Test
    fun overlappingIngestsKeepBoundaryCounts() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("School", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 100.0)))
            val asset = client.newAsset("test.mom", circle.id)
            val a: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code).body()
            val b: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code).body()
            val t0 = env.clock.millis()
            // Two readings inside first: the boundary starts UNKNOWN and needs them to be INSIDE.
            client.postJson("/v1/ingest", a.sourceToken, IngestBatch(listOf(IngestSignal("in1", t0 - 60_000, SCHOOL, 10.0), IngestSignal("in2", t0 - 50_000, SCHOOL, 10.0))))
            // Both sources report the same exit at the same moments, concurrently: a stress check of the per-asset lock.
            coroutineScope {
                listOf(a.sourceToken, b.sourceToken).map { token ->
                    async {
                        (1..3).forEach { s -> client.postJson("/v1/ingest", token, IngestBatch(listOf(IngestSignal("o$s", t0 - 30_000 + s * 1_000L, north(200.0 + s), 10.0)))) }
                    }
                }.awaitAll()
            }
            val exits = client.get("/v1/circles/${circle.id}/incidents") { bearerAuth("test.mom") }.body<List<IncidentDto>>().count { it.rule.name == "PLACE_EXIT" }
            assertEquals(1, exits, "one confirmed exit, neither lost nor doubled")
            val findings = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM findings WHERE rule = 'PLACE_EXIT'").use { it.next(); it.getInt(1) }
            }
            assertEquals(1, findings, "the exit is concluded once, not once per racing batch")
        }
    }

    /** #17: a restart must not raise the same silence twice. */
    @Test
    fun silenceEpisodesSurviveRestart() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val asset = client.newAsset("test.mom", circle.id)
            client.pair(client.pairingCode("test.mom", asset.id).code)
            env.clock.advance(6.minutes)
            fun scanner() = SilenceScanner(env.db, env.clock, env.rules,
                FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, CircleService(env.db, env.clock, CircleEvents.NONE))))
            scanner().scanOnce()
            scanner().scanOnce()
            val findings = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM findings WHERE rule = 'SILENT_SOURCE'").use { it.next(); it.getInt(1) }
            }
            assertEquals(1, findings, "a second scanner instance (a restart) remembers the episode")
        }
    }

    /** #12: whatever PIN was typed, the reporting rate answer is the same for 10 minutes. */
    @Test
    fun highRateSameForAnyPin() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val answers = listOf("1234", "9876", "5555").map { typed ->
                val asset = client.newAsset("test.mom", circle.id, name = "A$typed")
                val p: PairResponse = client.pair(client.pairingCode("test.mom", asset.id, CreatePairingRequest("1234", "9876")).code).body()
                client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("pin", pinHash = Codes.pinHash(p.pinSalt!!, typed))))).body<IngestResult>().highRate
            }
            assertEquals(listOf(true, true, true), answers, "cancel, duress and a wrong PIN look the same on the device")
        }
    }
}
