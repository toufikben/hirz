package dz.hirz.server.escalation

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class LadderViewTest {
    @Test
    fun incidentShowsLadderStageAndDeadline() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
            val asset = client.newAsset("test.mom", circle.id)
            val paired: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code).body()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val members = client.get("/v1/circles/${circle.id}/members") { bearerAuth("test.mom") }.body<List<MemberDto>>()
            val mom = members.single { it.role == Role.OWNER }.memberId
            val id = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            val view = client.get("/v1/incidents/$id") { bearerAuth("test.dad") }.body<IncidentDto>().escalation
            assertNotNull(view)
            assertEquals("GUARDIANS", view.stage)
            assertEquals(mom, view.waitingForMemberId)
            assertEquals(env.clock.millis() + env.rules.ackFallbackMs, view.deadlineMs)
            val e = EscalationEngine(env.db, env.clock, env.notifier, env.rules, CircleService(env.db, env.clock, CircleEvents.NONE))
            env.clock.advance(20.seconds); e.tick()
            env.clock.advance(61.seconds); e.tick()
            val next = client.get("/v1/incidents/$id") { bearerAuth("test.dad") }.body<IncidentDto>().escalation
            assertEquals(members.single { it.role == Role.GUARDIAN }.memberId, next?.waitingForMemberId)
        }
    }

    @Test
    fun signOutDeletesOwnPushTokenOnly() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            listOf("test.mom" to "tok-mom", "test.dad" to "tok-dad").forEach { (m, t) ->
                client.put("/v1/me/push-token") { bearerAuth(m); contentType(ContentType.Application.Json); setBody(PushTokenDto(t)) }
            }
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/me/push-token/tok-dad") { bearerAuth("test.mom") }.status)
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/me/push-token/tok-mom") { bearerAuth("test.mom") }.status)
            val left = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT token FROM push_tokens ORDER BY token").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            assertEquals(listOf("tok-dad"), left, "a member deletes only their own token")
        }
    }
}
