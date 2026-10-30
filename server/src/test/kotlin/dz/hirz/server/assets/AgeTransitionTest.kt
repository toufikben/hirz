package dz.hirz.server.assets

import dz.hirz.server.jobs.AgeTransitionJob
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.PushTokenDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
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

/** A child born on [birthDate] (or without one), paired; the owner has a push token. Returns the source token. */
private suspend fun HttpClient.child(birthDate: String?): String {
    val circle = newCircle("test.mom")
    put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
    val kid = newAsset("test.mom", circle.id)
    if (birthDate != null) put("/v1/assets/${kid.id}/profile") {
        bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(birthDate = birthDate))
    }
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    return p.sourceToken
}

private suspend fun HttpClient.send(env: TestEnv, token: String): HttpResponse =
    postJson("/v1/ingest", token, IngestBatch(listOf(IngestSignal("a${env.clock.millis()}", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))

private fun job(env: TestEnv) = AgeTransitionJob(env.db, env.clock, env.notifier)

class AgeTransitionTest {
    // The test clock starts on 2026-11-01.

    @Test
    fun at15TheOwnerIsToldOnce() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            client.child("2011-10-01")
            job(env).runOnce()
            job(env).runOnce()
            assertEquals(1, env.notifier.sent.count { it.second.kind == "age_review" })
        }
    }

    @Test
    fun at18SourcesPauseUntilAdultConsent() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val token = client.child("2008-10-01")
            assertEquals(HttpStatusCode.OK, client.send(env, token).status)
            job(env).runOnce()
            val refused = client.send(env, token)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertTrue("consent_required" in refused.bodyAsText())
            assertEquals(1, env.notifier.sent.count { it.second.kind == "age_paused" })
        }
    }

    @Test
    fun consentResumesTracking() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val token = client.child("2008-10-01")
            job(env).runOnce()
            assertEquals(HttpStatusCode.OK, client.post("/v1/sources/me/consent") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.OK, client.send(env, token).status)
            job(env).runOnce()
            assertEquals(HttpStatusCode.OK, client.send(env, token).status, "an adult who consented stays tracked")
        }
    }

    @Test
    fun noBirthDateNoTransition() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val token = client.child(null)
            job(env).runOnce()
            assertEquals(HttpStatusCode.OK, client.send(env, token).status)
            assertTrue(env.notifier.sent.none { it.second.kind.startsWith("age_") })
        }
    }
}
