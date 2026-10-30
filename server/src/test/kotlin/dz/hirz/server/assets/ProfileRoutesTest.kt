package dz.hirz.server.assets

import dz.hirz.shared.api.ActiveWindow
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ContactRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ProfileDto
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.RuleOverride
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.ShareDto
import dz.hirz.shared.api.ShareRequest
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val SCHOOL = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(SCHOOL.lat + m / 111_320.0, SCHOOL.lon)

private suspend fun HttpClient.putProfile(assetId: String, who: String, body: ProfileUpdate): HttpResponse =
    put("/v1/assets/$assetId/profile") { bearerAuth(who); contentType(ContentType.Application.Json); setBody(body) }

private suspend fun HttpClient.profile(assetId: String, who: String = "test.mom"): ProfileDto =
    get("/v1/assets/$assetId/profile") { bearerAuth(who) }.body()

class ProfileRoutesTest {
    @Test
    fun p0RulesCannotBeSwitchedOff() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
            val kid = client.newAsset("test.mom", circle.id, AssetType.CHILD, "Yanis")
            assertEquals(HttpStatusCode.OK, client.putProfile(kid.id, "test.mom", ProfileUpdate(condition = Condition.AUTISM)).status)
            assertEquals(HttpStatusCode.UnprocessableEntity,
                client.putProfile(kid.id, "test.mom", ProfileUpdate(overrides = listOf(RuleOverride(RuleId.HAZARD_PROXIMITY, enabled = false)))).status)
            assertEquals(HttpStatusCode.Forbidden,
                client.putProfile(kid.id, "test.dad", ProfileUpdate(overrides = listOf(RuleOverride(RuleId.HAZARD_PROXIMITY, tier = Tier.P1)))).status)
            assertEquals(HttpStatusCode.OK,
                client.putProfile(kid.id, "test.mom", ProfileUpdate(overrides = listOf(RuleOverride(RuleId.HAZARD_PROXIMITY, tier = Tier.P1)))).status)
            val hazard = client.profile(kid.id).rules.single { it.rule == RuleId.HAZARD_PROXIMITY }
            assertEquals(Tier.P1, hazard.tier)
            assertEquals(Tier.P0, hazard.defaultTier)
            assertTrue(hazard.locked, "a default-P0 rule is shown as one that cannot be switched off")
        }
    }

    @Test
    fun guardianMayRaiseNotLower() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
            val kid = client.newAsset("test.mom", circle.id)
            val raise = ProfileUpdate(overrides = listOf(RuleOverride(RuleId.PLACE_EXIT, tier = Tier.P0)))
            assertEquals(HttpStatusCode.OK, client.putProfile(kid.id, "test.dad", raise).status)
            assertEquals(HttpStatusCode.Forbidden, client.putProfile(kid.id, "test.dad", ProfileUpdate(overrides = emptyList())).status)
            assertEquals(HttpStatusCode.Forbidden,
                client.putProfile(kid.id, "test.dad", ProfileUpdate(overrides = listOf(RuleOverride(RuleId.PLACE_EXIT, enabled = false)))).status)
            assertEquals(HttpStatusCode.Forbidden, client.putProfile(kid.id, "test.dad", ProfileUpdate(condition = Condition.AUTISM)).status)
            assertEquals(HttpStatusCode.Forbidden, client.putProfile(kid.id, "test.dad", ProfileUpdate(birthDate = "2015-03-01")).status)
            assertEquals(Condition.NONE, client.profile(kid.id, "test.dad").condition)
        }
    }

    @Test
    fun raisedTierIsWhatTheEngineUses() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("School", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 100.0), marginM = 50))
            val kid = client.newAsset("test.mom", circle.id)
            client.putProfile(kid.id, "test.mom", ProfileUpdate(overrides = listOf(RuleOverride(RuleId.PLACE_EXIT, tier = Tier.P0))))
            val p: PairResponse = client.pair(client.pairingCode("test.mom", kid.id).code).body()
            listOf(0.0, 0.0, 200.0, 210.0, 220.0).forEachIndexed { i, m ->
                client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("x$i", env.clock.millis(), north(m), 10.0))))
                env.clock.advance(10.seconds)
            }
            val open = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            assertEquals(Tier.P0, open.single { it.rule == RuleId.PLACE_EXIT }.tier)
        }
    }

    @Test
    fun overrideChangesAreAudited() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val elder = client.newAsset("test.mom", circle.id, AssetType.ELDER, "Jedda")
            client.putProfile(elder.id, "test.mom", ProfileUpdate(condition = Condition.DEMENTIA))
            client.putProfile(elder.id, "test.mom", ProfileUpdate(overrides = listOf(RuleOverride(RuleId.NIGHT_EXIT, tier = Tier.P0))))
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM audit_log WHERE action = 'profile_changed'").use { it.next(); assertEquals(2, it.getInt(1)) }
            }
            assertEquals(Tier.P1, client.profile(elder.id).rules.single { it.rule == RuleId.PAST_ADDRESS_HEADING }.tier)
        }
    }

    @Test
    fun conditionNeverInPushSmsOrShare() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
            client.postJson("/v1/circles/${circle.id}/contacts", "test.mom", ContactRequest("Khalti", "0661000001", "fr"))
            val kid = client.newAsset("test.mom", circle.id, AssetType.SPECIAL_NEEDS, "Yanis")
            client.putProfile(kid.id, "test.mom", ProfileUpdate(condition = Condition.AUTISM))
            val p: PairResponse = client.pair(client.pairingCode("test.mom", kid.id).code).body()
            client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(
                IngestSignal("f", env.clock.millis(), SCHOOL, 9.0), IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            env.clock.advance(10.minutes)
            val id = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            val share: ShareDto = client.postJson("/v1/incidents/$id/share", "test.mom", ShareRequest(15)).body()
            val path = "/s/" + share.url.substringAfter("/s/")
            val texts = mutableListOf(client.get(path).bodyAsText(), client.get("$path/data").bodyAsText())
            texts += env.notifier.sent.map { it.second.toString() }
            env.db.connection.use { c -> c.createStatement().executeQuery("SELECT body FROM outbound_sms").use { while (it.next()) texts += it.getString(1) } }
            val words = listOf("autism", "autisme", "توحد", "dementia", "démence", "زهايمر")
            texts.forEach { t -> words.forEach { w -> assertTrue(w !in t.lowercase(), "\"$w\" leaked: ${t.take(200)}") } }
            assertTrue(env.notifier.sent.isNotEmpty(), "the push did go out")
        }
    }

    @Test
    fun guardianCannotWeakenNightOrCurfewAndCurfewIsSane() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
            val invite: dz.hirz.shared.api.InviteDto = client.postJson("/v1/circles/${circle.id}/invites", "test.mom",
                dz.hirz.shared.api.CreateInviteRequest(Role.TEMPORARY, memberExpiresAtMs = env.clock.millis() + 3_600_000)).body()
            client.post("/v1/invites/${invite.code}/accept") { bearerAuth("test.sitter") }
            val teen = client.newAsset("test.mom", circle.id, AssetType.TEEN, "Amine")
            val oneMinute = listOf(ActiveWindow((1..7).toSet(), 0, 1))
            assertEquals(HttpStatusCode.Forbidden, client.putProfile(teen.id, "test.dad", ProfileUpdate(nightWindow = oneMinute)).status)
            assertEquals(HttpStatusCode.Forbidden, client.putProfile(teen.id, "test.dad", ProfileUpdate(curfewMinute = 5 * 60 + 59)).status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.putProfile(teen.id, "test.mom", ProfileUpdate(curfewMinute = 7 * 60)).status,
                "a morning curfew would run all day")
            assertEquals(HttpStatusCode.OK, client.putProfile(teen.id, "test.mom", ProfileUpdate(curfewMinute = 22 * 60)).status)
            assertEquals(null, client.putProfile(teen.id, "test.mom", ProfileUpdate(curfewMinute = -1)).body<ProfileDto>().curfewMinute, "-1 clears it")
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/assets/${teen.id}/profile") { bearerAuth("test.sitter") }.status,
                "a babysitter does not read a profile")
        }
    }
}
