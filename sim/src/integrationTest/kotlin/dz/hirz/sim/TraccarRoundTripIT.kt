package dz.hirz.sim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Thin Traccar REST client for the round-trip test (first user becomes admin on a fresh server). */
private class TraccarApi(private val baseUrl: String) {
    private val http = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    private fun json(path: String, body: JsonObject) = send(
        HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())),
    )

    fun ensureSession(email: String, password: String) {
        json("/api/users", buildJsonObject { put("name", "ci"); put("email", email); put("password", password) })
        val form = "email=${URLEncoder.encode(email, Charsets.UTF_8)}&password=${URLEncoder.encode(password, Charsets.UTF_8)}"
        val session = send(
            HttpRequest.newBuilder(URI.create("$baseUrl/api/session"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)),
        )
        check(session.statusCode() == 200) { "session failed: HTTP ${session.statusCode()} ${session.body()}" }
    }

    fun createDevice(name: String, uniqueId: String): Long {
        val response = json("/api/devices", buildJsonObject { put("name", name); put("uniqueId", uniqueId) })
        check(response.statusCode() == 200) { "device create failed: HTTP ${response.statusCode()} ${response.body()}" }
        return Json.parseToJsonElement(response.body()).jsonObject.getValue("id").jsonPrimitive.long
    }

    fun positions(deviceId: Long): JsonArray {
        val response = send(HttpRequest.newBuilder(URI.create("$baseUrl/api/positions?deviceId=$deviceId")).GET())
        check(response.statusCode() == 200) { "positions failed: HTTP ${response.statusCode()}" }
        return Json.parseToJsonElement(response.body()).jsonArray
    }

    /** Polls once a second; throws (never returns empty) when nothing arrives within [timeoutSec]. */
    fun awaitPosition(deviceId: Long, timeoutSec: Int = 30): JsonObject {
        repeat(timeoutSec) {
            positions(deviceId).firstOrNull()?.let { return it.jsonObject }
            Thread.sleep(1_000)
        }
        throw AssertionError("position not recorded within ${timeoutSec}s")
    }
}

class TraccarRoundTripIT {
    private val baseUrl = System.getenv("TRACCAR_URL")!!.trimEnd('/')
    private val gt06Host = System.getenv("TRACCAR_GT06_HOST") ?: "localhost"
    private val gt06Port = (System.getenv("TRACCAR_GT06_PORT") ?: "5023").toInt()
    private val api = TraccarApi(baseUrl)

    @BeforeTest
    // In the e2e stack an admin already exists and self-registration is closed: use it when given.
    fun session() = api.ensureSession(
        System.getenv("TRACCAR_USER") ?: "ci@hirz.test",
        System.getenv("TRACCAR_PASSWORD") ?: "ci-${UUID.nameUUIDFromBytes(baseUrl.toByteArray())}",
    )

    @Test
    fun gt06FixAppearsInTraccar() {
        val imei = "863000000000001"
        val deviceId = api.createDevice("sim-gt06", imei)
        Gt06Client(gt06Host, gt06Port).use { client ->
            client.login(imei)
            client.send(Gt06Fix(36.7538, 3.0588, 0, 0, Instant.now(), 8))
        }
        val position = api.awaitPosition(deviceId)
        val lat = position.getValue("latitude").jsonPrimitive.double
        val lon = position.getValue("longitude").jsonPrimitive.double
        assertTrue(abs(lat - 36.7538) < 1e-4, "latitude $lat")
        assertTrue(abs(lon - 3.0588) < 1e-4, "longitude $lon")
    }

    @Test
    fun failsWhenPositionNeverArrives() {
        val deviceId = api.createDevice("sim-silent", "863000000000002")
        val error = assertFailsWith<AssertionError> { api.awaitPosition(deviceId) }
        assertEquals("position not recorded within 30s", error.message)
    }
}
