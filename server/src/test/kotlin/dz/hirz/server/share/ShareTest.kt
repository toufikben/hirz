package dz.hirz.server.share

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.ShareDataDto
import dz.hirz.shared.api.ShareDto
import dz.hirz.shared.api.ShareRequest
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private val SPOT = LatLon(36.7538, 3.0588)

/** mom alone with one paired child that sent a fix a minute ago, then an SOS; returns (incidentId, sourceToken). */
private suspend fun HttpClient.incident(nowMs: Long): String {
    val circle = newCircle("test.mom")
    val asset = newAsset("test.mom", circle.id)
    val paired: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(
        IngestSignal("f", nowMs - 60_000L, SPOT, 9.0),
        IngestSignal("sos", alarms = setOf(AlarmKind.SOS)),
    )))
    return get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
}

private suspend fun HttpClient.share(id: String, ttl: Int = 15): ShareDto = postJson("/v1/incidents/$id/share", "test.mom", ShareRequest(ttl)).body()

private fun path(dto: ShareDto) = "/s/" + dto.url.substringAfter("/s/")

class ShareTest {
    @Test
    fun shareWorksUntilExpiry() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val id = client.incident(env.clock.millis())
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/incidents/$id/share", "test.mom", ShareRequest(5)).status)
            val dto = client.share(id)
            assertEquals(43, dto.url.substringAfter("/s/").length, "32 random bytes, base64url without padding")
            val page = client.get(path(dto))
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue("Ahmed" in page.bodyAsText())
            val data: ShareDataDto = client.get(path(dto) + "/data").body()
            assertEquals(60L, data.lastAgeS)
            assertNotNull(data.last)
            env.clock.advance(16.minutes)
            assertEquals(HttpStatusCode.NotFound, client.get(path(dto)).status)
            assertEquals(HttpStatusCode.NotFound, client.get(path(dto) + "/data").status)
        }
    }

    @Test
    fun revokedShare404() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val id = client.incident(env.clock.millis())
            val dto = client.share(id)
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/incidents/$id/share") { bearerAuth("test.mom") }.status)
            assertEquals(HttpStatusCode.NotFound, client.get(path(dto)).status)
            assertEquals(HttpStatusCode.NotFound, client.get("/s/not-a-real-token").status)
        }
    }

    @Test
    fun closedIncidentShare404() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val id = client.incident(env.clock.millis())
            val dto = client.share(id)
            client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.mom") }
            assertEquals("closed", client.get("/v1/incidents/$id") { bearerAuth("test.mom") }.body<IncidentDto>().state)
            assertEquals(HttpStatusCode.NotFound, client.get(path(dto)).status)
            assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/incidents/$id/share", "test.mom", ShareRequest(15)).status)
        }
    }

    @Test
    fun shareViewsLogged() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val id = client.incident(env.clock.millis())
            val dto = client.share(id)
            client.get(path(dto)) { header("User-Agent", "PoliceTablet/1.0") }
            client.get(path(dto) + "/data")
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*), count(ip_hash), bool_or(user_agent = 'PoliceTablet/1.0') FROM share_views").use {
                    it.next()
                    assertEquals(2, it.getInt(1))
                    assertEquals(2, it.getInt(2))
                    assertTrue(it.getBoolean(3))
                }
                c.createStatement().executeQuery("SELECT count(*) FROM share_views WHERE ip_hash LIKE '%127.0.0.1%' OR ip_hash LIKE '%localhost%'").use {
                    it.next(); assertEquals(0, it.getInt(1), "the address is stored hashed")
                }
            }
        }
    }

    @Test
    fun pageHasNoExternalScripts() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val id = client.incident(env.clock.millis())
            val html = client.get(path(client.share(id))).bodyAsText()
            assertFalse(html.contains("<script", ignoreCase = true))
            assertFalse(Regex("""<(link|img|iframe)[^>]+(src|href)="https?://""", RegexOption.IGNORE_CASE).containsMatchIn(html))
            assertTrue("https://www.openstreetmap.org/" in html)
            assertTrue(Regex("""<meta http-equiv="refresh" content="30">""").containsMatchIn(html))
        }
    }
}
