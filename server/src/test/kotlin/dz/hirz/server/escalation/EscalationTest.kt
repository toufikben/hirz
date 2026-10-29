package dz.hirz.server.escalation

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ContactRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

private suspend fun HttpClient.familyWithContact(): Triple<String, String, PairResponse> {
    val circle = postJson("/v1/circles", "test.mom", dz.hirz.shared.api.CreateCircleRequest("Family")).body<dz.hirz.shared.api.CircleDto>()
    join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
    join("test.mom", circle.id, "test.aunt", Role.VIEWER)
    listOf("test.mom" to "tok-mom", "test.dad" to "tok-dad").forEach { (m, t) ->
        put("/v1/me/push-token") { bearerAuth(m); contentType(ContentType.Application.Json); setBody(PushTokenDto(t)) }
    }
    postJson("/v1/circles/${circle.id}/contacts", "test.mom", ContactRequest("Jaddah", "0550123456", "ar"))
    val asset = newAsset("test.mom", circle.id, name = "Lina")
    val paired: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    return Triple(circle.id, asset.id, paired)
}

class EscalationTest {
    private fun engine(env: TestEnv) =
        EscalationEngine(env.db, env.clock, env.notifier, env.rules, CircleService(env.db, env.clock, CircleEvents.NONE))

    private fun log(env: TestEnv): List<String> = env.db.connection.use { c ->
        c.createStatement().executeQuery("SELECT kind FROM escalation_log ORDER BY id").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun sms(env: TestEnv): List<String> = env.db.connection.use { c ->
        c.createStatement().executeQuery("SELECT to_phone FROM outbound_sms ORDER BY id").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    @Test
    fun unclaimedP0ReachesSecondGuardianThenContacts() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (_, _, paired) = client.familyWithContact()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val e = engine(env)
            env.notifier.sent.clear()
            env.clock.advance(20.seconds); e.tick()   // mom: no receipt → window from sent
            env.clock.advance(41.seconds); e.tick()   // P0 window (60 s) over → dad
            assertEquals(listOf("tok-dad"), env.notifier.sent.map { it.first.token })
            env.clock.advance(20.seconds); e.tick()
            env.clock.advance(41.seconds); e.tick()   // dad times out → contacts
            assertEquals(listOf("+213550123456"), sms(env))
            val kinds = log(env)
            listOf("opened", "window_from_sent", "escalated", "contacts_notified").forEach { k -> kotlin.test.assertTrue(k in kinds, "missing $k in $kinds") }
        }
    }

    @Test
    fun claimStopsEscalation() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.familyWithContact()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            client.post("/v1/incidents/$id/claim") { bearerAuth("test.dad") }
            env.notifier.sent.clear()
            val e = engine(env)
            repeat(10) { env.clock.advance(60.seconds); e.tick() }
            assertEquals(emptyList(), env.notifier.sent.map { it.first.token })
            assertEquals(emptyList(), sms(env))
        }
    }

    @Test
    fun concurrentClaimsYieldOneOwner() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.familyWithContact()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            coroutineScope {
                listOf("test.mom", "test.dad").map { m -> async { client.post("/v1/incidents/$id/claim") { bearerAuth(m) } } }.awaitAll()
            }
            assertEquals(1, log(env).count { it == "claimed" })
        }
    }

    @Test
    fun p2NeverEscalates() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, assetId, _) = client.familyWithContact()
            env.db.connection.use {
                it.createStatement().execute("INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('$assetId', '$circleId', 'SILENT_SOURCE', 'P2', now())")
            }
            val e = engine(env)
            repeat(10) { env.clock.advance(60.seconds); e.tick() }
            assertEquals(emptyList(), sms(env))
            assertEquals(emptyList(), log(env))
        }
    }
}
