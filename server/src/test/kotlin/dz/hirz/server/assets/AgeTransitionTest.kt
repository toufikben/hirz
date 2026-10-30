package dz.hirz.server.assets

import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.signal.AlarmKind
import dz.hirz.server.jobs.SilenceScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.CircleEvents
import io.ktor.client.request.get
import kotlin.time.Duration.Companion.minutes
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
private suspend fun HttpClient.child(birthDate: String?, type: AssetType = AssetType.CHILD): String {
    val circle = newCircle("test.mom")
    put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
    val kid = newAsset("test.mom", circle.id, type)
    // Paired while a child; the birth date says how old they are now.
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    if (birthDate != null) put("/v1/assets/${kid.id}/profile") {
        bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(birthDate = birthDate))
    }
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
            assertEquals(HttpStatusCode.OK, refused.status, "never an error status: trackers and sync must keep going")
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

    @Test
    fun sosStillGoesThroughWhilePaused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val token = client.child("2008-10-01")
            job(env).runOnce()
            client.postJson("/v1/ingest", token, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val circle = env.db.connection.use { c -> c.createStatement().executeQuery("SELECT circle_id FROM assets").use { it.next(); it.getString(1) } }
            val open = client.get("/v1/circles/$circle/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            assertEquals(1, open.size, "an SOS is never dropped, paused or not")
        }
    }

    @Test
    fun specialNeedsAdultIsNeverPaused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val token = client.child("2000-03-01", AssetType.SPECIAL_NEEDS)
            job(env).runOnce()
            assertTrue("consent_required" !in client.send(env, token).bodyAsText(), "an adult who may not be able to consent is not cut off")
        }
    }

    @Test
    fun consentBefore18IsRefused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val token = client.child("2015-03-01")
            assertEquals(HttpStatusCode.Conflict, client.post("/v1/sources/me/consent") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun pausedSourceIsNotSilent() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val token = client.child("2008-10-01")
            client.send(env, token)
            job(env).runOnce()
            env.clock.advance(60.minutes)
            val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
            SilenceScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles))).scanOnce()
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM incidents").use { it.next(); assertEquals(0, it.getInt(1), "a pause is not a silence") }
            }
        }
    }

    @Test
    fun aChildWhoTurned18PairsOnlyWithTheirConsent() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val kid = client.newAsset("test.mom", circle.id)
            client.put("/v1/assets/${kid.id}/profile") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(birthDate = "2008-10-01"))
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, client.pair(client.pairingCode("test.mom", kid.id).code).status)
        }
    }
}
