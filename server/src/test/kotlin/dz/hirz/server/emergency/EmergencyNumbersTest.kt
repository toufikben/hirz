package dz.hirz.server.emergency

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.EmergencyConfirmRequest
import dz.hirz.shared.api.EmergencyNumberDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmergencyNumbersTest {
    @Test
    fun registryListsSeededNumbersUnverified() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            client.newCircle("test.mom")
            val numbers: List<EmergencyNumberDto> = client.get("/v1/emergency-numbers?country=DZ") { bearerAuth("test.mom") }.body()
            assertTrue(numbers.map { it.number }.containsAll(listOf("1548", "17", "14", "1055", "112", "104")))
            assertTrue(numbers.all { it.verifiedAtMs == null })
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/emergency-numbers?country=DZ").status)
        }
    }

    @Test
    fun confirmationIsPerCircle() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val mine = client.newCircle("test.mom")
            val theirs = client.newCircle("test.other")
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/emergency-numbers/1548/confirm", "test.mom", EmergencyConfirmRequest(mine.id)).status)
            val forMine: List<EmergencyNumberDto> = client.get("/v1/emergency-numbers?country=DZ&circleId=${mine.id}") { bearerAuth("test.mom") }.body()
            assertNotNull(forMine.single { it.number == "1548" }.verifiedAtMs)
            val forTheirs: List<EmergencyNumberDto> = client.get("/v1/emergency-numbers?country=DZ&circleId=${theirs.id}") { bearerAuth("test.other") }.body()
            assertNull(forTheirs.single { it.number == "1548" }.verifiedAtMs)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/emergency-numbers/1548/confirm", "test.mom", EmergencyConfirmRequest(theirs.id)).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/emergency-numbers/999999/confirm", "test.mom", EmergencyConfirmRequest(mine.id)).status)
        }
    }
}
