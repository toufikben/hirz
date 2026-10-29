package dz.hirz.server.traccar

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.ingest.IngestService
import dz.hirz.server.support.TRACCAR_SECRET
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.traccarForward
import dz.hirz.server.support.validImei
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.TrackerClaimRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

private val IMEI = validImei("35933907512345")

class TraccarSyncTest {
    private fun count(env: TestEnv) = env.db.connection.use { c -> c.createStatement().executeQuery("SELECT count(*) FROM positions").use { it.next(); it.getInt(1) } }

    @Test
    fun syncFillsMissedForwards() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            client.postJson("/v1/assets/${asset.id}/trackers", "test.mom", TrackerClaimRequest("gt06n", IMEI, apn = "internet"))
            val first = traccarForward(IMEI, 1, env.clock.instant().plusSeconds(5), deviceId = 101)
            client.post("/v1/traccar/positions") { bearerAuth(TRACCAR_SECRET); contentType(ContentType.Application.Json); setBody(first) }
            assertEquals(1, count(env))
            // Traccar holds that position plus two the forwarder never delivered.
            listOf(1L to 5L, 2L to 20L, 3L to 35L).forEach { (id, s) ->
                env.traccar.stored += TraccarJson.positionForward(traccarForward(IMEI, id, env.clock.instant().plusSeconds(s), deviceId = 101)).position
            }
            env.clock.advance(kotlin.time.Duration.parse("1m"))
            val ingest = IngestService(env.db, env.clock, env.rules, emptyList())
            TraccarSync(env.db, env.clock, env.traccar, ingest).syncOnce()
            assertEquals(3, count(env), "two new rows; the forwarded one is a duplicate")
        }
    }

    @Test
    fun syncNeverRunsWithoutAdmin() {
        val env = TestEnv()
        withApp(env) { _, _ ->
            val ingest = IngestService(env.db, env.clock, env.rules, emptyList())
            assertEquals(0, TraccarSync(env.db, env.clock, TraccarAdmin.NONE, ingest).syncOnce())
        }
    }
}
