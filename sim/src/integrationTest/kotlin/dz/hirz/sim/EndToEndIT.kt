package dz.hirz.sim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Core's member API with a `test.<uid>` token (the e2e Core runs with HIRZ_TEST_AUTH in HIRZ_ENV=ci only). */
private class CoreApi(private val baseUrl: String, uid: String) {
    private val http = HttpClient.newHttpClient()
    private val token = "test.$uid"

    fun call(method: String, path: String, body: String? = null): Pair<Int, JsonElement?> {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$path")).header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        val r = http.send(req.build(), HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to r.body().takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) }
    }

    fun ok(method: String, path: String, body: String? = null): JsonElement {
        val (status, json) = call(method, path, body)
        check(status in 200..299) { "$method $path → HTTP $status $json" }
        return json ?: Json.parseToJsonElement("{}")
    }

    fun string(e: JsonElement, key: String) = e.jsonObject.getValue(key).jsonPrimitive.content

    /** Polls once a second until [check] holds; fails with [what] after [timeoutSec]. */
    fun <T> await(what: String, timeoutSec: Int = 10, probe: () -> T?): T {
        repeat(timeoutSec * 2) {
            probe()?.let { return it }
            Thread.sleep(500)
        }
        throw AssertionError("$what not seen within ${timeoutSec}s")
    }
}

private fun withCheckDigit(prefix: String): String = (0..9).map { prefix + it }.first { imei ->
    imei.reversed().mapIndexed { i, ch -> val d = ch - '0'; if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d }.sum() % 10 == 0
}

/**
 * The whole device path in CI (P3 Task 7): a simulated GT06 talks to a real Traccar 6.16.0, which forwards to Core,
 * which stores the fix, activates the claim and opens incidents. No phone is involved.
 */
class EndToEndIT {
    private val core = System.getenv("CORE_URL")!!.trimEnd('/')
    private val gt06Host = System.getenv("TRACCAR_GT06_HOST") ?: "localhost"
    private val gt06Port = (System.getenv("TRACCAR_GT06_PORT") ?: "5023").toInt()

    /** A fresh family (circle + asset) and a claimed tracker; returns (api, circleId, assetId, claimId, imei). */
    private fun family(tag: String): List<String> {
        val uid = "e2e-$tag-${UUID.randomUUID().toString().take(6)}"
        val api = CoreApi(core, uid)
        val circle = api.ok("POST", "/v1/circles", """{"name":"E2E $tag"}""")
        val circleId = api.string(circle, "id")
        val asset = api.ok("POST", "/v1/circles/$circleId/assets", """{"type":"child","name":"Sim $tag"}""")
        val assetId = api.string(asset, "id")
        val imei = withCheckDigit("86300000" + (100000 + (0..899999).random()).toString())
        val claim = api.ok("POST", "/v1/assets/$assetId/trackers", """{"model":"gt06n","imei":"$imei","simPhone":"0661000000","apn":"internet"}""")
        return listOf(uid, circleId, assetId, api.string(claim, "claimId"), imei)
    }

    @Test
    fun simulatedTrackerAppearsInCore() {
        val (uid, _, assetId, claimId, imei) = family("fix")
        val api = CoreApi(core, uid)
        Gt06Client(gt06Host, gt06Port).use { c ->
            c.login(imei)
            c.send(Gt06Fix(36.7538, 3.0588, 0, 0, Instant.now(), 8))
        }
        api.await("claim activation") { api.ok("GET", "/v1/trackers/claims/$claimId").takeIf { api.string(it, "state") == "active" } }
        val positions = api.await("the fix in Core") {
            api.ok("GET", "/v1/assets/$assetId/positions?sinceMs=0").jsonArray.takeIf { it.isNotEmpty() }
        }
        val p = positions.first().jsonObject.getValue("position").jsonObject
        assertTrue(kotlin.math.abs(p.getValue("lat").jsonPrimitive.content.toDouble() - 36.7538) < 1e-4)
    }

    @Test
    fun simulatedSosOpensIncident() {
        val (uid, circleId, _, _, imei) = family("sos")
        val api = CoreApi(core, uid)
        Gt06Client(gt06Host, gt06Port).use { c ->
            c.login(imei)
            c.send(Gt06Fix(36.7538, 3.0588, 0, 0, Instant.now(), 8))
            Thread.sleep(1_000)
            c.alarm(Gt06Fix(36.7539, 3.0589, 0, 0, Instant.now(), 8), Gt06Alarm.SOS)
        }
        val incident = api.await("an open SOS incident") {
            api.ok("GET", "/v1/circles/$circleId/incidents?state=open").jsonArray.firstOrNull { api.string(it, "rule") == "SOS" }
        }
        assertEquals("P0", api.string(incident, "tier"))
    }

    @Test
    fun forwardLatencyUnder5sInCi() {
        val (uid, _, assetId, claimId, imei) = family("latency")
        val api = CoreApi(core, uid)
        Gt06Client(gt06Host, gt06Port).use { c ->
            c.login(imei)
            c.send(Gt06Fix(36.7538, 3.0588, 0, 0, Instant.now(), 8))
            api.await("claim activation") { api.ok("GET", "/v1/trackers/claims/$claimId").takeIf { api.string(it, "state") == "active" } }
            repeat(20) { i ->
                c.send(Gt06Fix(36.7538 + i * 0.0001, 3.0588, 5, 90, Instant.now(), 8))
                Thread.sleep(300)
            }
        }
        val rows = api.await("21 fixes in Core", timeoutSec = 15) {
            api.ok("GET", "/v1/assets/$assetId/positions?sinceMs=0").jsonArray.takeIf { it.size >= 21 }
        }
        val delays = rows.map { r -> r.jsonObject.let { it.getValue("serverTimeMs").jsonPrimitive.long - it.getValue("fixTimeMs").jsonPrimitive.long } }.sorted()
        val p95 = delays[(delays.size * 95 / 100).coerceAtMost(delays.size - 1)]
        println("e2e source→Core delay p95 = ${p95} ms over ${delays.size} fixes")
        // GT06 times have 1 s resolution, so a fix can look up to 1 s older than it is.
        assertTrue(p95 < 5_000, "p95 $p95 ms")
    }
}
