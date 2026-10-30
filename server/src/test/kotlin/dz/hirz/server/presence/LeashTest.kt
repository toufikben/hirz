package dz.hirz.server.presence

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.LeashScanner
import dz.hirz.server.rules.FindingRecorder
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
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.OutingDto
import dz.hirz.shared.api.OutingPing
import dz.hirz.shared.api.OutingRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import io.ktor.client.HttpClient
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val SOUK = LatLon(36.77, 3.06)
private fun north(m: Double) = LatLon(SOUK.lat + m / 111_320.0, SOUK.lon)

private class Family(val circleId: String, val assetId: String, val childToken: String)

/** mom's child (of [condition]) with a paired phone; dad is a guardian. */
private suspend fun HttpClient.family(condition: Condition = Condition.NONE): Family {
    val circle = newCircle("test.mom")
    join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
    val kid = newAsset("test.mom", circle.id, AssetType.CHILD, "Yanis")
    if (condition != Condition.NONE) put("/v1/assets/${kid.id}/profile") {
        bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(condition = condition))
    }
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    return Family(circle.id, kid.id, p.sourceToken)
}

private suspend fun HttpClient.childAt(env: TestEnv, f: Family, p: LatLon) =
    postJson("/v1/ingest", f.childToken, IngestBatch(listOf(IngestSignal("c${env.clock.millis()}", env.clock.millis(), p, 8.0))))

private suspend fun HttpClient.ping(env: TestEnv, outing: String, p: LatLon) =
    postJson("/v1/outings/$outing/ping", "test.dad", OutingPing(p, 8.0, env.clock.millis()))

private suspend fun HttpClient.open(f: Family): List<IncidentDto> = get("/v1/circles/${f.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

private fun scanner(env: TestEnv): LeashScanner {
    val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
    return LeashScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
}

class LeashTest {
    @Test
    fun leashBreakAfter60sAnd50m() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            val outing: OutingDto = client.postJson("/v1/assets/${f.assetId}/outings", "test.dad", OutingRequest(hours = 3)).body()
            client.ping(env, outing.id, SOUK); client.childAt(env, f, north(5.0)); scanner(env).scanOnce()
            env.clock.advance(30.seconds)
            client.ping(env, outing.id, SOUK); client.childAt(env, f, north(120.0)); scanner(env).scanOnce()
            assertTrue(client.open(f).isEmpty(), "30 s apart in a crowd is not yet lost")
            env.clock.advance(40.seconds)
            client.ping(env, outing.id, SOUK); client.childAt(env, f, north(140.0)); scanner(env).scanOnce()
            assertEquals(listOf(RuleId.LEASH_BREAK to Tier.P1), client.open(f).map { it.rule to it.tier })
        }
    }

    @Test
    fun leashOnlyDuringAnOuting() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            client.childAt(env, f, north(500.0))
            env.clock.advance(90.seconds)
            scanner(env).scanOnce()
            assertTrue(client.open(f).isEmpty(), "no outing, no leash")
            val outing: OutingDto = client.postJson("/v1/assets/${f.assetId}/outings", "test.dad", OutingRequest(hours = 3)).body()
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/outings/${outing.id}/ping", "test.mom", OutingPing(SOUK, 8.0, env.clock.millis())).status,
                "only the guardian who took the child out reports where they are")
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/outings/${outing.id}") { bearerAuth("test.dad") }.status)
            client.ping(env, outing.id, SOUK)
            env.clock.advance(90.seconds)
            client.childAt(env, f, north(500.0))
            scanner(env).scanOnce()
            assertTrue(client.open(f).isEmpty(), "an ended outing is no leash")
        }
    }

    @Test
    fun autismLeashIsP0() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family(Condition.AUTISM)
            val outing: OutingDto = client.postJson("/v1/assets/${f.assetId}/outings", "test.dad", OutingRequest(hours = 2)).body()
            client.ping(env, outing.id, SOUK); client.childAt(env, f, SOUK); scanner(env).scanOnce()
            env.clock.advance(70.seconds)
            client.ping(env, outing.id, SOUK); client.childAt(env, f, north(200.0)); scanner(env).scanOnce()
            assertEquals(Tier.P0, client.open(f).single { it.rule == RuleId.LEASH_BREAK }.tier)
        }
    }

    @Test
    fun outingEndsByItself() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            assertEquals(HttpStatusCode.UnprocessableEntity,
                client.postJson("/v1/assets/${f.assetId}/outings", "test.dad", OutingRequest(hours = 7)).status, "six hours at most")
            val outing: OutingDto = client.postJson("/v1/assets/${f.assetId}/outings", "test.dad", OutingRequest(hours = 1)).body()
            client.ping(env, outing.id, SOUK); client.childAt(env, f, SOUK); scanner(env).scanOnce()
            env.clock.advance(2.hours)
            client.ping(env, outing.id, SOUK); client.childAt(env, f, north(800.0)); scanner(env).scanOnce()
            assertTrue(client.open(f).isEmpty(), "the outing ended on its own an hour ago")
        }
    }
}
