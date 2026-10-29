package dz.hirz.server.exports

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
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
import dz.hirz.shared.api.Role
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val SPOT = LatLon(36.7538, 3.0588)
private fun step(i: Int) = LatLon(SPOT.lat + i * 0.0002, SPOT.lon)

private fun unzip(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { z ->
    generateSequence { z.nextEntry }.associate { it.name to z.readBytes() }
}

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/**
 * mom (owner), dad (guardian), aunt (viewer); one paired child with fixes every minute from 30 minutes ago,
 * except a 20-minute silence; then an SOS. Returns (circleId, incidentId).
 */
private suspend fun HttpClient.scenario(nowMs: Long): Pair<String, String> {
    val circle = newCircle("test.mom")
    join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
    join("test.mom", circle.id, "test.aunt", Role.VIEWER)
    val asset = newAsset("test.mom", circle.id)
    val paired: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    // Minutes ago: 30..26, then silence, then 5..1.
    val minutes = (26..30).reversed() + (1..5).reversed()
    postJson("/v1/ingest", paired.sourceToken, IngestBatch(minutes.mapIndexed { i, m -> IngestSignal("f$m", nowMs - m * 60_000L, step(i), 12.0) }))
    postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
    val id = get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
    return circle.id to id
}

private suspend fun HttpClient.pack(id: String, who: String = "test.dad") = get("/v1/incidents/$id/police-pack") { bearerAuth(who) }

class PolicePackTest {
    @Test
    fun packContainsAllFilesWithMatchingHashes() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (_, id) = client.scenario(env.clock.millis())
            val r = client.pack(id)
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(ContentType.Application.Zip, r.contentType()?.withoutParameters())
            val files = unzip(r.readRawBytes())
            assertEquals(setOf("positions.csv", "track.gpx", "incident.json", "completeness.txt", "manifest.json"), files.keys)
            val manifest = Json.parseToJsonElement(files.getValue("manifest.json").decodeToString()).jsonObject
            val listed = manifest.getValue("files").jsonArray.associate {
                it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("sha256").jsonPrimitive.content
            }
            assertEquals(files.keys - "manifest.json", listed.keys)
            listed.forEach { (name, hash) -> assertEquals(sha256(files.getValue(name)), hash, name) }
            assertNotNull(manifest["exportedBy"])
            assertNotNull(manifest["serverVersion"])
        }
    }

    @Test
    fun packListsGapsNeverInterpolates() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val now = env.clock.millis()
            val (_, id) = client.scenario(now)
            val files = unzip(client.pack(id).readRawBytes())
            val gaps = files.getValue("completeness.txt").decodeToString().lines().filter { it.startsWith("gap ") }
            // The window opens 2 h before the incident: the silence before the first fix is a gap too (review #8).
            assertEquals(2, gaps.size, gaps.joinToString(" | "))
            assertTrue(gaps.first().contains("window_start"), gaps.first())
            assertTrue(gaps.any { "duration_s=1260" in it }, "the 20-minute silence between fixes")
            val rows = files.getValue("positions.csv").decodeToString().trim().lines().drop(1)
            assertEquals(10, rows.size, "only stored fixes, nothing invented")
            val inGap = (now - 25 * 60_000L + 1) until (now - 5 * 60_000L)
            val fixTimes = rows.map { java.time.Instant.parse(it.substringBefore(',')).toEpochMilli() }
            assertTrue(fixTimes.none { it in inGap })
        }
    }

    @Test
    fun timesInUtcAndAlgiers() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (_, id) = client.scenario(env.clock.millis())
            val csv = unzip(client.pack(id).readRawBytes()).getValue("positions.csv").decodeToString().trim().lines()
            assertEquals(
                "fix_time_utc,fix_time_algiers,server_time_utc,lat,lon,accuracy_m,accuracy_estimated,fix_source,speed_kmh,heading,battery,source_id,source_kind,device_model,quality",
                csv.first(),
            )
            // Test clock: 2026-11-01T08:00Z; the last fix is 1 minute earlier; Algiers is UTC+1.
            val last = csv.last().split(',')
            assertEquals("2026-11-01T07:59:00Z", last[0])
            assertEquals("2026-11-01T08:59:00+01:00", last[1])
        }
    }

    @Test
    fun viewerCannotExport() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (_, id) = client.scenario(env.clock.millis())
            assertEquals(HttpStatusCode.NotFound, client.pack(id, "test.aunt").status)
            assertEquals(HttpStatusCode.NotFound, client.pack(id, "test.stranger").status)
        }
    }

    @Test
    fun exportIsAuditedAndNeverContainsCodeWord() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, id) = client.scenario(env.clock.millis())
            client.put("/v1/circles/$circleId/code-word") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(CodeWordDto("zaytouna-77"))
            }
            val files = unzip(client.pack(id).readRawBytes())
            assertTrue(files.values.none { "zaytouna-77" in it.decodeToString() })
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM audit_log WHERE action = 'police_pack_exported'").use { it.next(); assertEquals(1, it.getInt(1)) }
                c.createStatement().executeQuery("SELECT exported_at IS NOT NULL FROM incidents WHERE id = '$id'").use { it.next(); assertTrue(it.getBoolean(1)) }
                c.createStatement().executeQuery("SELECT count(*) FROM location_views WHERE via = 'police_pack'").use { it.next(); assertEquals(1, it.getInt(1)) }
            }
            assertFalse(files.getValue("track.gpx").decodeToString().contains("<script"))
        }
    }
}
