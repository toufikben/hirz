package dz.hirz.server.traccar

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Traccar's REST API with an admin account (`HIRZ_TRACCAR_URL`, `_USER`, `_PASSWORD`). Reached only over the private
 * network; Traccar's web port is never public (design §8.2).
 */
class TraccarHttpAdmin(baseUrl: String, private val user: String, private val password: String, engine: HttpClientEngine) : TraccarAdmin {
    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient(engine) {
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
        expectSuccess = false
    }

    private fun <T> call(what: String, block: suspend () -> T): T = runBlocking {
        try { block() } catch (e: TraccarUnavailable) { throw e } catch (e: Exception) { throw TraccarUnavailable("Traccar $what failed", e) }
    }

    private suspend fun HttpResponse.fail(what: String): Nothing = throw TraccarUnavailable("Traccar $what: HTTP ${status.value} ${bodyAsText().take(200)}")

    override fun createDevice(uniqueId: String, name: String): Long = call("create device") {
        val r = http.post("$base/api/devices") {
            basicAuth(user, password); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name); put("uniqueId", uniqueId) }.toString())
        }
        when {
            r.status.isSuccess() -> TraccarJson.json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
            // A device left behind by an earlier claim: reuse it rather than fail the owner's claim.
            r.status == HttpStatusCode.BadRequest -> {
                val existing = http.get("$base/api/devices") { basicAuth(user, password); parameter("uniqueId", uniqueId) }
                if (!existing.status.isSuccess()) existing.fail("find device")
                TraccarJson.json.parseToJsonElement(existing.bodyAsText()).jsonArray.firstOrNull()?.jsonObject?.getValue("id")?.jsonPrimitive?.long
                    ?: r.fail("create device")
            }
            else -> r.fail("create device")
        }
    }

    override fun deleteDevice(id: Long) = call("delete device") {
        val r = http.delete("$base/api/devices/$id") { basicAuth(user, password) }
        if (!r.status.isSuccess() && r.status != HttpStatusCode.NotFound) r.fail("delete device")
    }

    override fun positions(deviceId: Long, from: Instant, to: Instant): List<TraccarPosition> = call("positions") {
        val r = http.get("$base/api/positions") {
            basicAuth(user, password); parameter("deviceId", deviceId); parameter("from", from.toString()); parameter("to", to.toString())
        }
        if (!r.status.isSuccess()) r.fail("positions")
        TraccarJson.json.decodeFromString(ListSerializer(TraccarPosition.serializer()), r.bodyAsText())
    }
}
