package dz.hirz.server.traccar

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

class TraccarAdminTest {
    @Test
    fun createDeviceSendsBasicAuthAndReturnsId() {
        var body = ""
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            assertEquals("/api/devices", req.url.encodedPath)
            assertEquals("Basic " + Base64.getEncoder().encodeToString("admin:pw".toByteArray()), req.headers[HttpHeaders.Authorization])
            body = String(runBlocking { req.body.toByteArray() })
            respond("""{"id":42,"name":"hirz-ab12","uniqueId":"359339075123456","attributes":{}}""", HttpStatusCode.OK, JSON)
        }
        val id = TraccarHttpAdmin("http://traccar:8082", "admin", "pw", engine).createDevice("359339075123456", "hirz-ab12")
        assertEquals(42, id)
        assertTrue("\"uniqueId\":\"359339075123456\"" in body && "\"name\":\"hirz-ab12\"" in body, body)
    }

    @Test
    fun existingDeviceIsReusedNotDuplicated() {
        val engine = MockEngine { req ->
            when {
                req.method == HttpMethod.Post -> respondError(HttpStatusCode.BadRequest, "Duplicate entry")
                req.url.encodedPath == "/api/devices" && req.url.parameters["uniqueId"] == "359339075123456" ->
                    respond("""[{"id":7,"uniqueId":"359339075123456"}]""", HttpStatusCode.OK, JSON)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        assertEquals(7, TraccarHttpAdmin("http://traccar:8082", "admin", "pw", engine).createDevice("359339075123456", "hirz-x"))
    }

    @Test
    fun deleteMissingDeviceIsNotAnError() {
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Delete, req.method)
            assertEquals("/api/devices/9", req.url.encodedPath)
            respondError(HttpStatusCode.NotFound)
        }
        TraccarHttpAdmin("http://traccar:8082", "admin", "pw", engine).deleteDevice(9)
    }

    @Test
    fun serverErrorsBecomeTraccarUnavailable() {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        assertFailsWith<TraccarUnavailable> { TraccarHttpAdmin("http://traccar:8082", "admin", "pw", engine).deleteDevice(9) }
    }

    @Test
    fun positionsQueryUsesUtcRange() {
        val engine = MockEngine { req ->
            assertEquals("/api/positions", req.url.encodedPath)
            assertEquals("7", req.url.parameters["deviceId"])
            assertEquals("2026-11-01T07:00:00Z", req.url.parameters["from"])
            assertEquals("2026-11-01T08:00:00Z", req.url.parameters["to"])
            respond("""[{"id":5,"deviceId":7,"fixTime":"2026-11-01T07:30:00.000+00:00","latitude":36.7,"longitude":3.0,"unknownField":1}]""", HttpStatusCode.OK, JSON)
        }
        val list = TraccarHttpAdmin("http://traccar:8082", "admin", "pw", engine)
            .positions(7, Instant.parse("2026-11-01T07:00:00Z"), Instant.parse("2026-11-01T08:00:00Z"))
        assertEquals(listOf(5L), list.map { it.id })
    }
}
