package dz.hirz.server.rules

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.CurfewScanner
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val HOME = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(HOME.lat + m / 111_320.0, HOME.lon)

private class Teen(val circleId: String, val token: String)

/** A teen with a home, an aunt's place 500 m north and a coast place 2 km north; curfew 22:00 Algiers. */
private suspend fun HttpClient.teen(env: TestEnv, at: Instant): Teen {
    env.clock.now = at
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Home", PlaceKind.HOME, ShapeDto.Circle(HOME, 50.0), marginM = 50))
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Khalti", PlaceKind.RELATIVE, ShapeDto.Circle(north(500.0), 50.0), marginM = 50))
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Beach", PlaceKind.COAST, ShapeDto.Circle(north(2000.0), 200.0), marginM = 50))
    val asset = newAsset("test.mom", circle.id, AssetType.TEEN, "Amine")
    put("/v1/assets/${asset.id}/profile") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(curfewMinute = 22 * 60)) }
    val p: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    return Teen(circle.id, p.sourceToken)
}

private suspend fun HttpClient.fix(env: TestEnv, t: Teen, m: Double) {
    postJson("/v1/ingest", t.token, IngestBatch(listOf(IngestSignal("t${env.clock.millis()}", env.clock.millis(), north(m), 10.0))))
    env.clock.advance(10.seconds)
}

private suspend fun HttpClient.open(t: Teen): List<IncidentDto> = get("/v1/circles/${t.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

private fun scanner(env: TestEnv): CurfewScanner {
    val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
    return CurfewScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
}

private fun findings(env: TestEnv, rule: String): Int = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT count(*) FROM findings WHERE rule = '$rule'").use { it.next(); it.getInt(1) }
}

class TeenRulesTest {
    @Test
    fun outsideAfterCurfewRaisesOncePerNight() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.teen(env, Instant.parse("2026-11-02T21:00:00Z")) // 22:00 Algiers
            client.fix(env, t, 1000.0)
            scanner(env).scanOnce()
            assertTrue(client.open(t).isEmpty(), "15 minutes of grace after the curfew")
            env.clock.now = Instant.parse("2026-11-02T21:20:00Z")
            client.fix(env, t, 1000.0)
            scanner(env).scanOnce()
            assertEquals(listOf(RuleId.CURFEW to Tier.P2), client.open(t).map { it.rule to it.tier })
            env.clock.now = Instant.parse("2026-11-02T21:40:00Z")
            client.fix(env, t, 1000.0)
            scanner(env).scanOnce()
            assertEquals(1, findings(env, "CURFEW"), "once per night")
        }
    }

    @Test
    fun atAuntsAfterCurfewIsFine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.teen(env, Instant.parse("2026-11-02T21:20:00Z"))
            client.fix(env, t, 500.0)
            scanner(env).scanOnce()
            assertTrue(client.open(t).isEmpty())
        }
    }

    @Test
    fun coastAtNightRaises() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.teen(env, Instant.parse("2026-11-02T22:30:00Z")) // 23:30 Algiers
            client.fix(env, t, 1500.0)
            client.fix(env, t, 2000.0)
            client.fix(env, t, 2010.0)
            assertEquals(Tier.P1, client.open(t).single { it.rule == RuleId.COASTAL_NIGHT }.tier)
        }
    }

    @Test
    fun coastByDayDoesNot() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.teen(env, Instant.parse("2026-11-02T13:00:00Z"))
            client.fix(env, t, 1500.0)
            client.fix(env, t, 2000.0)
            client.fix(env, t, 2010.0)
            client.fix(env, t, 2020.0)
            assertTrue(client.open(t).none { it.rule == RuleId.COASTAL_NIGHT })
        }
    }
}
