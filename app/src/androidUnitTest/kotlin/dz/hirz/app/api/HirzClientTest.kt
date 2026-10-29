package dz.hirz.app.api

import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

class HirzClientTest {
    @Test
    fun sendsBearerToken() = runTest {
        var seen: String? = null
        val engine = MockEngine { req ->
            seen = req.headers[HttpHeaders.Authorization]
            respond("""{"memberId":"m1","lang":"ar"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = HirzClient("https://core.test", { "id-token-1" }, engine)
        assertEquals("m1", client.me().memberId)
        assertEquals("Bearer id-token-1", seen)
    }

    @Test
    fun retriesOnceOn401WithFreshToken() = runTest {
        val tokens = mutableListOf<Boolean>()
        var calls = 0
        val engine = MockEngine { req ->
            calls++
            if (req.headers[HttpHeaders.Authorization] == "Bearer stale") respond("", HttpStatusCode.Unauthorized)
            else respond("""{"memberId":"m1","lang":"ar"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = HirzClient("https://core.test", { force -> tokens += force; if (force) "fresh" else "stale" }, engine)
        assertEquals("m1", client.me().memberId)
        assertEquals(listOf(false, true), tokens)
        assertEquals(2, calls)
    }

    @Test
    fun failsWithApiErrorAfterSecond401() = runTest {
        val engine = MockEngine { respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders) }
        val client = HirzClient("https://core.test", { "t" }, engine)
        val e = runCatching { client.me() }.exceptionOrNull()
        assertEquals(401, (e as HirzApiException).status)
    }

    @Test
    fun decodesIncidentDto() = runTest {
        val incident = IncidentDto(
            id = "i1", assetId = "a1", assetName = "Ahmed", circleId = "c1", tier = Tier.P0, rule = RuleId.SOS,
            state = "open", openedAtMs = 1L,
        )
        val engine = MockEngine { respond(Json.encodeToString(IncidentDto.serializer(), incident), HttpStatusCode.OK, jsonHeaders) }
        assertEquals(incident, HirzClient("https://core.test", { "t" }, engine).incident("i1"))
    }

    @Test
    fun ingestUsesSourceToken() = runTest {
        var seen: String? = null
        var path: String? = null
        val engine = MockEngine { req ->
            seen = req.headers[HttpHeaders.Authorization]; path = req.url.encodedPath
            respond("""{"accepted":1,"duplicates":0}""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = HirzClient("https://core.test", { "member-token" }, engine)
        val result = client.ingest("source-token", IngestBatch(listOf(IngestSignal("c1"))))
        assertEquals(1, result.accepted)
        assertEquals("Bearer source-token", seen)
        assertEquals("/v1/ingest", path)
    }

    @Test
    fun baseUrlTrailingSlashIsHarmless() = runTest {
        var url: String? = null
        val engine = MockEngine { req -> url = req.url.toString(); respond("[]", HttpStatusCode.OK, jsonHeaders) }
        HirzClient("https://core.test/", { "t" }, engine).circles()
        assertEquals("https://core.test/v1/circles", url)
    }
}
