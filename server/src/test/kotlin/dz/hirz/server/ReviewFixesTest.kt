package dz.hirz.server

import dz.hirz.server.ingest.IngestListener
import dz.hirz.server.jobs.RetentionPurge
import dz.hirz.server.notify.Notifier
import dz.hirz.server.notify.SendResult
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairedChild
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.testJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreateInviteRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.InviteDto
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LiveEvent
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** One test per finding of the P1 whole-branch review (RED first, then the fix). */
class ReviewFixesTest {
    private fun count(env: TestEnv, sql: String) = env.db.connection.use { c -> c.createStatement().executeQuery(sql).use { it.next(); it.getInt(1) } }

    /** Review #2: the carrier's install may also hold the member token; a duress alarm must never reach it. */
    @Test
    fun duressNeverRingsADeviceSharedByMemberAndSource() = withApp { client, env ->
        val circle = client.newCircle("test.owner")
        client.join("test.owner", circle.id, "test.teen", Role.VIEWER)
        listOf("test.owner" to "tok-owner", "test.teen" to "tok-shared").forEach { (m, t) ->
            client.put("/v1/me/push-token") { bearerAuth(m); contentType(ContentType.Application.Json); setBody(PushTokenDto(t)) }
        }
        val asset = client.newAsset("test.owner", circle.id, name = "Lina")
        val paired: PairResponse = client.pair(client.pairingCode("test.owner", asset.id).code, pushToken = "tok-shared").body()
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("d", alarms = setOf(AlarmKind.DURESS)))))
        assertEquals(listOf("tok-owner"), env.notifier.sent.map { it.first.token })
    }

    /** Review #3: a failing listener on an earlier signal must not stop the SOS, and a retry re-evaluates. */
    @Test
    fun sosSurvivesAListenerFailureAndRetriesReEvaluate() {
        val fail = AtomicBoolean(true)
        val env = TestEnv(ingestListeners = listOf(IngestListener { if (fail.get() && it.clientId == "fix") error("boom") }))
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.pairedChild()
            val batch = IngestBatch(listOf(
                IngestSignal("fix", env.clock.millis(), LatLon(36.75, 3.05), 10.0),
                IngestSignal("sos", alarms = setOf(AlarmKind.SOS)),
            ))
            client.postJson("/v1/ingest", paired.sourceToken, batch)
            val open: List<IncidentDto> = client.get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.owner") }.body()
            assertEquals(1, open.size, "the SOS opened an incident even though another signal's listener failed")
            assertEquals(1, count(env, "SELECT count(*) FROM positions WHERE client_id = 'fix' AND evaluated_at IS NULL"))
            fail.set(false)
            client.postJson("/v1/ingest", paired.sourceToken, batch)
            assertEquals(0, count(env, "SELECT count(*) FROM positions WHERE evaluated_at IS NULL"))
        }
    }

    /** Review #4: a send that throws (not a returned failure) must not cancel the other sends. */
    @Test
    fun throwingSendDoesNotCancelTheOthers() {
        val sent = CopyOnWriteArrayList<String>()
        val notifier = Notifier { to, _ ->
            if (to.token == "tok-mom") throw IllegalStateException("network")
            sent += to.token; SendResult.Ok("ok")
        }
        val custom = TestEnv(notifierOverride = notifier)
        withApp(custom) { client, _ ->
            val circle = client.newCircle("test.owner")
            client.join("test.owner", circle.id, "test.mom", Role.GUARDIAN)
            listOf("test.owner" to "tok-owner", "test.mom" to "tok-mom").forEach { (m, t) ->
                client.put("/v1/me/push-token") { bearerAuth(m); contentType(ContentType.Application.Json); setBody(PushTokenDto(t)) }
            }
            val asset = client.newAsset("test.owner", circle.id)
            val paired: PairResponse = client.pair(client.pairingCode("test.owner", asset.id).code).body()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("s", alarms = setOf(AlarmKind.SOS)))))
            assertEquals(listOf("tok-owner"), sent.toList())
            assertEquals(2, count(custom, "SELECT count(*) FROM deliveries"))
            assertEquals(1, count(custom, "SELECT count(*) FROM deliveries WHERE error IS NOT NULL"))
        }
    }

    /** Review #7: routine P2 silence must not suspend retention; P0/P1 keep only their evidence window. */
    @Test
    fun retentionIgnoresP2AndKeepsOnlyTheEvidenceWindow() = withApp { client, env ->
        val (circleId, assetId, paired) = client.pairedChild()
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("old", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
        env.clock.advance(10.days)
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("evidence", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
        env.db.connection.use {
            it.createStatement().execute("INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('$assetId', '$circleId', 'SILENT_SOURCE', 'P2', '${env.clock.now}')")
            it.createStatement().execute("INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('$assetId', '$circleId', 'SOS', 'P0', '${env.clock.now}')")
        }
        env.clock.advance(31.days)
        RetentionPurge(env.db, env.clock).runOnce()
        val left = env.db.connection.use { c ->
            c.createStatement().executeQuery("SELECT client_id FROM positions ORDER BY id").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        assertEquals(listOf("evidence"), left)
    }

    /** Review #8: a temporary member whose access expires loses an already-open socket. */
    @Test
    fun expiredTemporaryMemberLosesOpenSocket() = withApp { client, env ->
        val (circleId, _, paired) = client.pairedChild()
        val until = env.clock.now.plusSeconds(3600).toEpochMilli()
        val invite: InviteDto = client.postJson("/v1/circles/$circleId/invites", "test.owner", CreateInviteRequest(Role.TEMPORARY, memberExpiresAtMs = until)).body()
        client.post("/v1/invites/${invite.code}/accept") { bearerAuth("test.sitter") }
        client.webSocket("/v1/live?token=test.sitter") {
            val first = incoming.receive() as Frame.Text
            assertIs<LiveEvent.Snapshot>(testJson.decodeFromString(LiveEvent.serializer(), first.readText()))
            env.clock.advance(1.hours + 1.seconds)
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("p", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
            val next = withTimeoutOrNull(3_000) { incoming.receive() }
            val text = (next as? Frame.Text)?.readText()
            assertTrue(next == null || next is Frame.Close || text?.contains("member_removed") == true, "no position may reach an expired member: $text")
        }
    }
}
