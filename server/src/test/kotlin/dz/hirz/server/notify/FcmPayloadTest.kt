package dz.hirz.server.notify

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class FcmPayloadTest {
    @Test
    fun incidentPayloadHasNoCoordinatesOrReasons() {
        val id = UUID.randomUUID()
        val data = FcmNotifier.dataFor(AlertMessage("incident", id, "P0", "Ahmed", "SOS"))
        assertEquals(setOf("t", "id", "tier", "asset", "rule"), data.keys)
        assertEquals("incident", data["t"])
        assertEquals(id.toString(), data["id"])
    }

    @Test
    fun checkinPayloadIsMinimal() {
        assertEquals(mapOf("t" to "checkin", "asset" to "Ahmed"), FcmNotifier.dataFor(AlertMessage("checkin", null, null, "Ahmed", null)))
    }

    @Test
    fun ttlFollowsTier() {
        assertEquals(60_000L, FcmNotifier.ttlMsFor("P0"))
        assertEquals(180_000L, FcmNotifier.ttlMsFor("P1"))
        assertEquals(900_000L, FcmNotifier.ttlMsFor(null))
    }
}
