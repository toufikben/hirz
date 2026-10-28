package dz.hirz.server

import dz.hirz.server.support.withApp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun healthReturnsOk() = withApp { client, _ ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertEquals("hirz-core", body["service"]?.jsonPrimitive?.content)
        assertEquals("0.1.0", body["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun unknownRouteIs404() = withApp { client, _ ->
        assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
    }
}
