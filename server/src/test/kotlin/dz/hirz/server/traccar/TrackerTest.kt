package dz.hirz.server.traccar

import dz.hirz.server.support.TRACCAR_SECRET
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.activateTracker
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.traccarForward
import dz.hirz.server.support.validImei
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.Tier
import dz.hirz.shared.api.TrackerClaimDto
import dz.hirz.shared.api.TrackerClaimRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val IMEI = validImei("35933907512345")
private val HOME = LatLon(36.7538, 3.0588)

private suspend fun HttpClient.forward(body: String, secret: String? = TRACCAR_SECRET): HttpResponse = post("/v1/traccar/positions") {
    secret?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body)
}

private suspend fun HttpClient.claim(assetId: String, imei: String = IMEI, owner: String = "test.mom"): HttpResponse =
    postJson("/v1/assets/$assetId/trackers", owner, TrackerClaimRequest(model = "gt06n", imei = imei, simPhone = "0661000000", apn = "internet"))

private suspend fun HttpClient.state(claimId: String, who: String = "test.mom"): TrackerClaimDto =
    get("/v1/trackers/claims/$claimId") { bearerAuth(who) }.body()

private fun count(env: TestEnv, sql: String): Int = env.db.connection.use { c -> c.createStatement().executeQuery(sql).use { it.next(); it.getInt(1) } }

class TrackerTest {
    @Test
    fun claimRejectsInvalidImei() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.claim(asset.id, "12345").status)
            val badDigit = IMEI.dropLast(1) + ((IMEI.last() - '0' + 1) % 10)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.claim(asset.id, badDigit).status)
            assertTrue(env.traccar.created.isEmpty())
        }
    }

    @Test
    fun reportsAtTheChallengeIntervalActivate() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val claim: TrackerClaimDto = client.claim(asset.id).body()
            assertEquals("pending", claim.state)
            val interval = assertNotNull(claim.proofIntervalS)
            assertTrue(interval in 45..95)
            assertTrue(claim.commands.single { it.purpose == "interval" }.text.contains(interval.toString()), "the setup SMS sets the challenge")
            assertEquals(listOf(IMEI), env.traccar.created.map { it.second })
            client.forward(traccarForward(IMEI, 1, env.clock.instant().plusSeconds(30)))
            assertEquals("pending", client.state(claim.claimId).state, "one report proves nothing")
            client.activateTracker(IMEI, claim, env.clock.instant().plusSeconds(60))
            val after = client.state(claim.claimId)
            assertEquals("active", after.state)
            assertNotNull(after.sourceId)
            assertEquals(1, count(env, "SELECT count(*) FROM sources WHERE kind = 'traccar' AND device_uid = '$IMEI' AND revoked_at IS NULL"))
            assertEquals(1, count(env, "SELECT count(*) FROM positions WHERE asset_id = '${asset.id}'"), "the activating report is the first stored")
        }
    }

    /** Review C1: a tracker already reporting to Hirz (released, or never reconfigured) keeps its old interval. */
    @Test
    fun aDeviceAtItsOldIntervalNeverActivates() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val claim: TrackerClaimDto = client.claim(asset.id).body()
            val wrong = claim.proofIntervalS!! * 2L
            (0 until 8).forEach { i -> client.forward(traccarForward(IMEI, 10L + i, env.clock.instant().plusSeconds(5 + wrong * i))) }
            assertEquals("pending", client.state(claim.claimId).state)
            assertEquals(0, count(env, "SELECT count(*) FROM positions"))
        }
    }

    @Test
    fun reportBeforeClaimDoesNotCount() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val claim: TrackerClaimDto = client.claim(asset.id).body()
            client.activateTracker(IMEI, claim, env.clock.instant().minusSeconds(3L * claim.proofIntervalS!!))
            assertEquals("pending", client.state(claim.claimId).state)
            assertEquals(0, count(env, "SELECT count(*) FROM positions"))
        }
    }

    @Test
    fun expiredClaimNeverActivates() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val claim: TrackerClaimDto = client.claim(asset.id).body()
            env.clock.advance(31.minutes)
            TrackerService(env.db, env.clock, dz.hirz.server.circles.CircleService(env.db, env.clock, dz.hirz.server.circles.CircleEvents.NONE), env.traccar).expireClaims()
            assertEquals(listOf(env.traccar.created.single().first), env.traccar.deleted.toList())
            client.activateTracker(IMEI, claim, env.clock.instant())
            assertEquals("expired", client.state(claim.claimId).state)
            assertEquals(0, count(env, "SELECT count(*) FROM positions"))
        }
    }

    @Test
    fun imeiActiveElsewhereIsRefused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val mine = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val claim: TrackerClaimDto = client.claim(mine.id).body()
            client.activateTracker(IMEI, claim, env.clock.instant().plusSeconds(5))
            val theirs = client.newAsset("test.other", client.newCircle("test.other").id)
            assertEquals(HttpStatusCode.Conflict, client.claim(theirs.id, owner = "test.other").status)
            val sourceId = client.state(claim.claimId).sourceId
            assertEquals(HttpStatusCode.NotFound, client.delete("/v1/assets/${mine.id}/trackers/$sourceId") { bearerAuth("test.other") }.status)
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/assets/${mine.id}/trackers/$sourceId") { bearerAuth("test.mom") }.status)
            assertTrue(env.traccar.deleted.isNotEmpty(), "the released device leaves Traccar")
            val again: TrackerClaimDto = client.claim(theirs.id, owner = "test.other").body()
            // Still reporting at the old owner's settings: the new claim stays pending until its own challenge is met.
            // 200 s is outside every challenge's ±30 % (the longest is 95 s × 1.3).
            (0 until 4).forEach { i -> client.forward(traccarForward(IMEI, 50L + i, env.clock.instant().plusSeconds(10 + 200L * i))) }
            assertEquals("pending", client.state(again.claimId, "test.other").state)
        }
    }

    /** Review I1: a claim that could not reach Traccar leaves nothing behind and can be retried. */
    @Test
    fun traccarDownLeavesNoClaim() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            env.traccar.down = true
            assertEquals(HttpStatusCode.ServiceUnavailable, client.claim(asset.id).status)
            assertEquals(0, count(env, "SELECT count(*) FROM tracker_claims"))
            env.traccar.down = false
            assertEquals(HttpStatusCode.OK, client.claim(asset.id).status)
        }
    }

    @Test
    fun claimsPerMemberAreLimited() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            (1..5).forEach { i -> assertEquals(HttpStatusCode.OK, client.claim(asset.id, validImei("3593390751000$i")).status) }
            assertEquals(HttpStatusCode.TooManyRequests, client.claim(asset.id, validImei("35933907510006")).status)
        }
    }

    @Test
    fun unknownImeiIsDropped() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            client.newAsset("test.mom", client.newCircle("test.mom").id)
            assertEquals(HttpStatusCode.OK, client.forward(traccarForward(validImei("86000000000001"), 1, env.clock.instant())).status)
            assertEquals(0, count(env, "SELECT count(*) FROM sources WHERE kind = 'traccar'"))
            assertEquals(0, count(env, "SELECT count(*) FROM positions"))
        }
    }

    @Test
    fun forwardNeedsSecret() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            client.claim(asset.id)
            val body = traccarForward(IMEI, 1, env.clock.instant().plusSeconds(5))
            assertEquals(HttpStatusCode.Unauthorized, client.forward(body, secret = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.forward(body, secret = "wrong").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/traccar/events") { contentType(ContentType.Application.Json); setBody("{}") }.status)
            assertEquals(0, count(env, "SELECT count(*) FROM positions"))
        }
        val unset = TestEnv(traccarSecretOverride = "")
        withApp(unset) { client, _ ->
            assertEquals(HttpStatusCode.Unauthorized, client.forward(traccarForward(IMEI, 1, unset.clock.instant()), secret = "").status, "an unset secret refuses everything")
        }
    }

    @Test
    fun retriedForwardIsStoredOnce() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val claim: TrackerClaimDto = client.claim(asset.id).body()
            val active = client.activateTracker(IMEI, claim, env.clock.instant().plusSeconds(5))
            val sos = traccarForward(IMEI, 7, active.plusSeconds(10), alarm = "sos")
            client.forward(sos); client.forward(sos)
            assertEquals(2, count(env, "SELECT count(*) FROM positions"), "the activating report and one SOS")
            assertEquals(1, count(env, "SELECT count(*) FROM findings WHERE rule = 'SOS'"))
        }
    }

    @Test
    fun trackerSosOpensP0() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val asset = client.newAsset("test.mom", circle.id)
            val claim: TrackerClaimDto = client.claim(asset.id).body()
            val active = client.activateTracker(IMEI, claim, env.clock.instant().plusSeconds(5))
            client.post("/v1/traccar/events") {
                bearerAuth(TRACCAR_SECRET); contentType(ContentType.Application.Json)
                setBody("""{"event":{"id":9,"type":"alarm","positionId":2,"attributes":{"alarm":"sos"}},
                    "position":${traccarForward(IMEI, 2, active.plusSeconds(6)).substringAfter("\"position\":").substringBeforeLast(",\"device\"")},
                    "device":{"id":7,"uniqueId":"$IMEI"}}""")
            }
            val open = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            assertEquals(listOf(RuleId.SOS to Tier.P0), open.map { it.rule to it.tier })
            assertEquals(1, count(env, "SELECT count(*) FROM escalation_state"), "the ladder starts as for any SOS")
        }
    }

    @Test
    fun bufferedBacklogIsHistoryOnly() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Home", PlaceKind.HOME, ShapeDto.Circle(HOME, 80.0)))
            val asset = client.newAsset("test.mom", circle.id)
            val claim: TrackerClaimDto = client.claim(asset.id).body()
            env.clock.advance(10.seconds)
            val active = client.activateTracker(IMEI, claim, env.clock.instant())
            env.clock.now = active.plusSeconds(10)
            client.forward(traccarForward(IMEI, 2, env.clock.instant()))
            val old = env.clock.instant().minusMillis(2.hours.inWholeMilliseconds)
            (3L..5L).forEach { i -> client.forward(traccarForward(IMEI, i, env.clock.instant(), lat = 36.80, fixTime = old.plusSeconds(i * 10))) }
            env.clock.advance(10.seconds)
            client.forward(traccarForward(IMEI, 6, env.clock.instant()))
            assertEquals(6, count(env, "SELECT count(*) FROM positions"))
            val incidents = client.get("/v1/circles/${circle.id}/incidents") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            assertTrue(incidents.none { it.rule == RuleId.PLACE_EXIT }, "old buffered fixes never raise an exit now")
        }
    }
}
