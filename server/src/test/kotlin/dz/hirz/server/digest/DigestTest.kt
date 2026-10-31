package dz.hirz.server.digest

import dz.hirz.server.jobs.DigestJob
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.DigestDto
import dz.hirz.shared.api.PushTokenDto
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

private class Home(val circleId: String, val assetId: String)

private suspend fun HttpClient.home(env: TestEnv): Home {
    env.clock.now = Instant.parse("2026-11-08T10:00:00Z")
    val circle = newCircle("test.mom")
    put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
    val kid = newAsset("test.mom", circle.id, name = "Yanis")
    return Home(circle.id, kid.id)
}

/** A P2 finding of [rule] on the asset at [at], as a rule would store it. */
private fun finding(env: TestEnv, h: Home, rule: String, at: Instant) = env.db.connection.use { c ->
    c.createStatement().executeUpdate(
        "INSERT INTO findings (asset_id, rule, tier, reasons, at) VALUES ('${h.assetId}', '$rule', 'P2', '[]'::jsonb, '$at'::timestamptz)",
    )
}

private val EVENING = Instant.parse("2026-11-08T19:00:00Z") // 20:00 Algiers

class DigestTest {
    @Test
    fun p2sGatherIntoOneEveningDigest() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.home(env)
            finding(env, h, "UNUSUAL_STOP", Instant.parse("2026-11-08T07:20:00Z"))
            finding(env, h, "SILENT_SOURCE", Instant.parse("2026-11-08T13:00:00Z"))
            finding(env, h, "UNUSUAL_STOP", Instant.parse("2026-11-07T07:20:00Z")) // yesterday: not today's digest
            env.clock.now = EVENING
            DigestJob(env.db, env.clock, env.notifier).runOnce()
            DigestJob(env.db, env.clock, env.notifier).runOnce()
            val digests = client.get("/v1/circles/${h.circleId}/digests") { bearerAuth("test.mom") }.body<List<DigestDto>>()
            assertEquals(1, digests.size, "one digest an evening")
            assertEquals(2, digests.single().items.size)
            assertEquals(1, env.notifier.sent.count { it.second.kind == "digest" })
        }
    }

    @Test
    fun noDigestWhenNothingHappened() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.home(env)
            env.clock.now = EVENING
            DigestJob(env.db, env.clock, env.notifier).runOnce()
            assertTrue(client.get("/v1/circles/${h.circleId}/digests") { bearerAuth("test.mom") }.body<List<DigestDto>>().isEmpty())
            assertTrue(env.notifier.sent.none { it.second.kind == "digest" })
        }
    }

    @Test
    fun notBeforeTheEvening() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.home(env)
            finding(env, h, "UNUSUAL_STOP", Instant.parse("2026-11-08T07:20:00Z"))
            env.clock.now = Instant.parse("2026-11-08T15:00:00Z")
            DigestJob(env.db, env.clock, env.notifier).runOnce()
            assertTrue(client.get("/v1/circles/${h.circleId}/digests") { bearerAuth("test.mom") }.body<List<DigestDto>>().isEmpty())
        }
    }

    @Test
    fun digestPushIsQuietAndCarriesNoPlace() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.home(env)
            finding(env, h, "UNUSUAL_STOP", Instant.parse("2026-11-08T07:20:00Z"))
            env.clock.now = EVENING
            DigestJob(env.db, env.clock, env.notifier).runOnce()
            val push = env.notifier.sent.single { it.second.kind == "digest" }.second
            assertEquals(null, push.tier, "no tier: nothing rings")
            assertEquals(null, push.rule)
        }
    }

    @Test
    fun digestRespectsCondition() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.home(env)
            finding(env, h, "PAST_ADDRESS_HEADING", Instant.parse("2026-11-08T07:20:00Z"))
            env.clock.now = EVENING
            DigestJob(env.db, env.clock, env.notifier).runOnce()
            val item = client.get("/v1/circles/${h.circleId}/digests") { bearerAuth("test.mom") }.body<List<DigestDto>>().single().items.single()
            assertEquals("PERSON_ALERT", item.rule, "a rule that exists only for one condition is not named")
        }
    }
}
