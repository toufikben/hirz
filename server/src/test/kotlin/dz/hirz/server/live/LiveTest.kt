package dz.hirz.server.live

import dz.hirz.server.support.join
import dz.hirz.server.support.pairedChild
import dz.hirz.server.support.postJson
import dz.hirz.server.support.testJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LiveEvent
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.PositionDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

private suspend fun DefaultClientWebSocketSession.nextEvent(): LiveEvent = withTimeout(5_000) {
    while (true) {
        when (val frame = incoming.receive()) {
            is Frame.Text -> return@withTimeout testJson.decodeFromString(LiveEvent.serializer(), frame.readText())
            is Frame.Close -> fail("socket closed: ${closeReason.await()}")
            else -> continue
        }
    }
    @Suppress("UNREACHABLE_CODE") fail("unreachable")
}

private suspend fun DefaultClientWebSocketSession.awaitClose(): CloseReason? = withTimeout(5_000) {
    for (frame in incoming) if (frame is Frame.Close) break
    closeReason.await()
}

class LiveTest {
    @Test
    fun snapshotThenStream() = withApp { client, env ->
        val (_, assetId, paired) = client.pairedChild()
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("a", env.clock.millis(), LatLon(36.75, 3.05), 12.0))))
        client.webSocket("/v1/live?token=test.owner") {
            val snapshot = assertIs<LiveEvent.Snapshot>(nextEvent())
            val asset = snapshot.assets.single()
            assertEquals(assetId, asset.assetId)
            assertEquals(LatLon(36.75, 3.05), asset.last?.position)
            env.clock.advance(60.seconds) // 1.1 km in 60 s is plausible; at the same instant it would be an impossible jump
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("b", env.clock.millis(), LatLon(36.76, 3.05), 9.0))))
            val update = assertIs<LiveEvent.PositionUpdate>(nextEvent())
            assertEquals(LatLon(36.76, 3.05), update.position.position)
            assertEquals(9.0, update.position.accuracyM)
        }
    }

    @Test
    fun incidentUpdatesStream() = withApp { client, _ ->
        val (_, _, paired) = client.pairedChild()
        client.webSocket("/v1/live?token=test.owner") {
            assertIs<LiveEvent.Snapshot>(nextEvent())
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            var sawIncident = false
            repeat(3) { if (!sawIncident && nextEvent() is LiveEvent.IncidentUpdate) sawIncident = true }
            assertTrue(sawIncident)
        }
    }

    @Test
    fun invalidTokenIsRejected() = withApp { client, _ ->
        client.webSocket("/v1/live?token=bogus") {
            assertEquals(4401, awaitClose()?.code?.toInt())
        }
    }

    @Test
    fun removed_member_socket_is_closed() = withApp { client, _ ->
        val (circleId, _, _) = client.pairedChild()
        client.join("test.owner", circleId, "test.mom", Role.GUARDIAN)
        val mom = client.get("/v1/circles/$circleId/members") { bearerAuth("test.owner") }.body<List<MemberDto>>().single { it.role == Role.GUARDIAN }
        client.webSocket("/v1/live?token=test.mom") {
            assertIs<LiveEvent.Snapshot>(nextEvent())
            client.delete("/v1/circles/$circleId/members/${mom.memberId}") { bearerAuth("test.owner") }
            assertEquals(4403, awaitClose()?.code?.toInt())
        }
    }

    @Test
    fun historyIsOrderedLimitedAndLogged() = withApp { client, env ->
        val (circleId, assetId, paired) = client.pairedChild()
        val t = env.clock.millis()
        val signals = (1..5).map { IngestSignal("p$it", t - (6 - it) * 10_000L, LatLon(36.75 + it * 0.0001, 3.05), 10.0) }
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(signals))
        val history: List<PositionDto> = client.get("/v1/assets/$assetId/positions?sinceMs=${t - 45_000}&limit=3") { bearerAuth("test.owner") }.body()
        assertEquals(3, history.size)
        assertEquals(history.sortedBy { it.fixTimeMs }, history)
        assertTrue(history.all { it.fixTimeMs >= t - 45_000 })
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/assets/$assetId/positions") { bearerAuth("test.stranger") }.status)
        env.db.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM location_views WHERE asset_id = '$assetId' AND via = 'history'").use {
                it.next(); assertEquals(1, it.getInt(1))
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = circleId
    }
}
