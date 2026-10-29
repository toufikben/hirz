package dz.hirz.server.custody

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.AssetLiveDto
import dz.hirz.shared.api.ConsentDto
import dz.hirz.shared.api.ConsentRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.HandoverDto
import dz.hirz.shared.api.HandoverRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LinkCodeDto
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val SCHOOL = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(SCHOOL.lat + m / 111_320.0, SCHOOL.lon)

private class Family(val circleId: String, val assetId: String, val sourceToken: String, val dadId: String)

/** mom (owner, priority 1), uncle (guardian, 2), dad (guardian, 3); a school place; a paired child. */
private suspend fun HttpClient.family(): Family {
    val circle = newCircle("test.mom")
    join("test.mom", circle.id, "test.uncle", Role.GUARDIAN)
    join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("School", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 100.0), marginM = 50))
    val asset = newAsset("test.mom", circle.id)
    val paired: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    val dad = get("/v1/circles/${circle.id}/members") { bearerAuth("test.mom") }.body<List<MemberDto>>().single { it.priority == 3 }
    return Family(circle.id, asset.id, paired.sourceToken, dad.memberId)
}

/** Inside, inside, then three readings 200+ m out, 10 s apart: a confirmed exit. */
private suspend fun HttpClient.leaveSchool(f: Family, env: TestEnv, tag: String) {
    listOf(0.0, 0.0, 200.0, 210.0, 220.0).forEachIndexed { i, m ->
        postJson("/v1/ingest", f.sourceToken, IngestBatch(listOf(IngestSignal("$tag$i", env.clock.millis(), north(m), 10.0))))
        env.clock.advance(10.seconds)
    }
}

private suspend fun HttpClient.handover(f: Family, env: TestEnv, mute: Boolean, hours: Int = 2) =
    postJson("/v1/assets/${f.assetId}/handovers", "test.mom", HandoverRequest(f.dadId, env.clock.millis(), env.clock.millis() + hours * 3_600_000L, mute))

private suspend fun HttpClient.open(f: Family): List<IncidentDto> = get("/v1/circles/${f.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

private fun findings(env: TestEnv, rule: String): Int = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT count(*) FROM findings WHERE rule = '$rule'").use { it.next(); it.getInt(1) }
}

class CustodyTest {
    @Test
    fun handoverMakesCustodian() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            val h = client.handover(f, env, mute = false)
            assertEquals(HttpStatusCode.OK, h.status)
            assertEquals(f.dadId, h.body<HandoverDto>().memberId)
            client.postJson("/v1/ingest", f.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.open(f).single().id
            val first = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT data->'order'->>0 FROM escalation_state WHERE incident_id = '$id'").use { it.next(); it.getString(1) }
            }
            assertEquals(f.dadId, first, "the custodian is asked first, ahead of circle priority")
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/assets/${f.assetId}/handovers", "test.stranger",
                HandoverRequest(f.dadId, env.clock.millis(), env.clock.millis() + 60_000, false)).status)
        }
    }

    @Test
    fun mutedExitStillRecordedNotRaised() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            client.handover(f, env, mute = true)
            client.leaveSchool(f, env, "m")
            assertEquals(1, findings(env, "PLACE_EXIT"), "the exit is on record")
            assertTrue(client.open(f).isEmpty(), "but nobody is alarmed during the outing")
        }
    }

    @Test
    fun sosNeverMuted() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            client.handover(f, env, mute = true)
            client.postJson("/v1/ingest", f.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            assertEquals(listOf(RuleId.SOS), client.open(f).map { it.rule })
        }
    }

    @Test
    fun expiredHandoverStopsMuting() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            client.handover(f, env, mute = true, hours = 1)
            env.clock.advance(2.hours)
            client.leaveSchool(f, env, "e")
            assertEquals(listOf(RuleId.PLACE_EXIT), client.open(f).map { it.rule })
        }
    }

    @Test
    fun consentNeedsOtherOwner() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            val other = client.newCircle("test.father2")
            client.join("test.father2", other.id, "test.grandma", Role.GUARDIAN)
            val code: LinkCodeDto = client.post("/v1/circles/${other.id}/link-code") { bearerAuth("test.father2") }.body()
            val consent: ConsentDto = client.postJson("/v1/assets/${f.assetId}/consents", "test.mom", ConsentRequest(code.code)).body()
            assertEquals("pending", consent.state)
            val shared = { who: String -> suspend { client.get("/v1/circles/${other.id}/shared-assets") { bearerAuth(who) }.body<List<AssetLiveDto>>() } }
            assertTrue(shared("test.father2")().isEmpty())
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/consents/${consent.id}/accept") { bearerAuth("test.grandma") }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/consents/${consent.id}/accept") { bearerAuth("test.mom") }.status)
            val accepted: ConsentDto = client.post("/v1/consents/${consent.id}/accept") { bearerAuth("test.father2") }.body()
            assertEquals("active", accepted.state)
            assertEquals(listOf(f.assetId), shared("test.grandma")().map { it.assetId })
        }
    }

    @Test
    fun revocationNotifiesBothCircles() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            val other = client.newCircle("test.father2")
            listOf("test.mom" to "tok-mom", "test.dad" to "tok-dad", "test.father2" to "tok-father2").forEach { (m, t) ->
                client.put("/v1/me/push-token") { bearerAuth(m); contentType(ContentType.Application.Json); setBody(PushTokenDto(t)) }
            }
            val code: LinkCodeDto = client.post("/v1/circles/${other.id}/link-code") { bearerAuth("test.father2") }.body()
            val consent: ConsentDto = client.postJson("/v1/assets/${f.assetId}/consents", "test.mom", ConsentRequest(code.code)).body()
            client.post("/v1/consents/${consent.id}/accept") { bearerAuth("test.father2") }
            val granted = env.notifier.sent.filter { it.second.kind == "custody_changed" }.map { it.first.token }.toSet()
            assertEquals(setOf("tok-mom", "tok-dad", "tok-father2"), granted)
            env.notifier.sent.clear()
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/consents/${consent.id}") { bearerAuth("test.father2") }.status)
            val revoked = env.notifier.sent.filter { it.second.kind == "custody_changed" }.map { it.first.token }.toSet()
            assertEquals(setOf("tok-mom", "tok-dad", "tok-father2"), revoked)
            assertTrue(client.get("/v1/circles/${other.id}/shared-assets") { bearerAuth("test.father2") }.body<List<AssetLiveDto>>().isEmpty())
        }
    }
}
