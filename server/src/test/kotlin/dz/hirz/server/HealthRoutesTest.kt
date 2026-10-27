package dz.hirz.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun healthReturnsOk() = testApplication {
        application { module() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertEquals("hirz-core", body["service"]?.jsonPrimitive?.content)
        assertEquals("0.1.0", body["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun unknownRouteIs404() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
    }
}
