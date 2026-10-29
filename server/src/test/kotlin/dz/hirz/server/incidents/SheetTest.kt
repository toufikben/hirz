package dz.hirz.server.incidents

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CodeWordDto
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.SheetDto
import dz.hirz.shared.api.TodayRequest
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val SPOT = LatLon(36.7538, 3.0588)
private fun step(i: Int) = LatLon(SPOT.lat + i * 0.0005, SPOT.lon)

/** mom's circle, one paired child; returns (circleId, assetId, sourceToken). */
private suspend fun HttpClient.child(): Triple<String, String, String> {
    val circle = newCircle("test.mom")
    val asset = newAsset("test.mom", circle.id)
    val paired: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    return Triple(circle.id, asset.id, paired.sourceToken)
}

private suspend fun HttpClient.sosIncident(circleId: String, sourceToken: String): String {
    postJson("/v1/ingest", sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
    return get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
}

private suspend fun HttpClient.sheet(id: String): SheetDto = get("/v1/incidents/$id/sheet") { bearerAuth("test.mom") }.body()

class SheetTest {
    @Test
    fun sheetHasTrackOf30Minutes() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, token) = client.child()
            val now = env.clock.millis()
            val ages = listOf(45, 25, 10, 2)
            client.postJson("/v1/ingest", token, IngestBatch(ages.mapIndexed { i, m ->
                IngestSignal("f$i", now - m * 60_000L, step(i), 10.0)
            }))
            val sheet = client.sheet(client.sosIncident(circleId, token))
            assertEquals(3, sheet.track.size, "only fixes from the last 30 minutes")
            assertEquals(now - 2 * 60_000L, sheet.lastFix?.fixTimeMs)
            assertEquals(120L, sheet.lastFixAgeS)
        }
    }

    @Test
    fun clothesExpireAtLocalMidnight() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, assetId, token) = client.child()
            val r = client.put("/v1/assets/$assetId/today") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(TodayRequest("red jacket, blue jeans"))
            }
            assertEquals(HttpStatusCode.NoContent, r.status)
            val id = client.sosIncident(circleId, token)
            assertEquals("red jacket, blue jeans", client.sheet(id).todayClothes)
            // 08:00Z = 09:00 Algiers; +15 h is 00:00 Algiers the next day.
            env.clock.advance(15.hours)
            assertNull(client.sheet(id).todayClothes)
        }
    }

    @Test
    fun photoSizeLimited() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (_, assetId, _) = client.child()
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(1_000)
            val tooBig = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(520 * 1024)
            val put = { body: ByteArray -> suspend {
                client.put("/v1/assets/$assetId/photo") { bearerAuth("test.mom"); contentType(ContentType.Image.JPEG); setBody(body) }.status
            } }
            assertEquals(HttpStatusCode.PayloadTooLarge, put(tooBig)())
            assertEquals(HttpStatusCode.UnsupportedMediaType, put(ByteArray(100))())
            assertEquals(HttpStatusCode.NoContent, put(jpeg)())
            assertContentEquals(jpeg, client.get("/v1/assets/$assetId/photo") { bearerAuth("test.mom") }.readRawBytes())
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/assets/$assetId/photo") { bearerAuth("test.stranger") }.status)
        }
    }

    @Test
    fun sheetNeverContainsCodeWord() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, token) = client.child()
            client.put("/v1/circles/$circleId/code-word") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(CodeWordDto("zaytouna-77"))
            }
            val id = client.sosIncident(circleId, token)
            val body = client.get("/v1/incidents/$id/sheet") { bearerAuth("test.mom") }.bodyAsText()
            assertTrue(body.contains("1548"), "emergency numbers are on the sheet")
            assertFalse(body.contains("zaytouna-77"))
        }
    }

    @Test
    fun sheetLogsLocationView() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, assetId, token) = client.child()
            client.postJson("/v1/ingest", token, IngestBatch(listOf(IngestSignal("f", env.clock.millis() - 1.minutes.inWholeMilliseconds, SPOT, 10.0))))
            client.sheet(client.sosIncident(circleId, token))
            val views = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM location_views WHERE via = 'sheet' AND asset_id = '$assetId'").use { it.next(); it.getInt(1) }
            }
            assertEquals(1, views)
        }
    }
}
