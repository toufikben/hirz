package dz.hirz.server.traccar

import dz.hirz.server.ingest.IngestService
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.activateTracker
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.traccarForward
import dz.hirz.server.support.validImei
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.TrackerClaimDto
import dz.hirz.shared.api.TrackerClaimRequest
import io.ktor.client.call.body
import kotlin.test.Test
import kotlin.test.assertEquals

private val IMEI = validImei("35933907512345")

class TraccarSyncTest {
    private fun count(env: TestEnv) = env.db.connection.use { c -> c.createStatement().executeQuery("SELECT count(*) FROM positions").use { it.next(); it.getInt(1) } }

    private fun stored(env: TestEnv, id: Long, at: java.time.Instant) {
        env.traccar.stored += TraccarJson.positionForward(traccarForward(IMEI, id, at, deviceId = 101)).position
    }

    @Test
    fun syncFillsMissedForwards() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val claim: TrackerClaimDto = client.postJson("/v1/assets/${asset.id}/trackers", "test.mom", TrackerClaimRequest("gt06n", IMEI, apn = "internet")).body()
            val active = client.activateTracker(IMEI, claim, env.clock.instant().plusSeconds(5), deviceId = 101)
            assertEquals(1, count(env))
            // Traccar holds the activating report plus two the forwarder never delivered.
            stored(env, 902, active)
            stored(env, 903, active.plusSeconds(20))
            stored(env, 904, active.plusSeconds(35))
            env.clock.now = active.plusSeconds(60)
            TraccarSync(env.db, env.clock, env.traccar, IngestService(env.db, env.clock, env.rules, emptyList())).syncOnce()
            assertEquals(3, count(env), "two new rows; the forwarded one is a duplicate")
        }
    }

    /** Review I3: a reused Traccar device may hold a previous holder's positions; they never reach the new circle. */
    @Test
    fun syncNeverImportsFromBeforeTheSource() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            stored(env, 1, env.clock.instant().minusSeconds(1_800))
            val claim: TrackerClaimDto = client.postJson("/v1/assets/${asset.id}/trackers", "test.mom", TrackerClaimRequest("gt06n", IMEI, apn = "internet")).body()
            val active = client.activateTracker(IMEI, claim, env.clock.instant().plusSeconds(5), deviceId = 101)
            // A heartbeat-only device: no last fix, so the sync would look back an hour.
            env.db.connection.use { c -> c.createStatement().executeUpdate("UPDATE sources SET last_fix_at = NULL") }
            env.clock.now = active.plusSeconds(60)
            TraccarSync(env.db, env.clock, env.traccar, IngestService(env.db, env.clock, env.rules, emptyList())).syncOnce()
            assertEquals(1, count(env), "only the activating report")
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
