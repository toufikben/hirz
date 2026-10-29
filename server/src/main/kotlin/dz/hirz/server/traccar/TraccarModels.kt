package dz.hirz.server.traccar

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What Traccar 6.16 forwards (`forward.type=json` and `event.forward.url`): only the fields Hirz reads. Everything
 * else Traccar adds is ignored, so a Traccar upgrade that adds fields never breaks ingestion.
 */
@Serializable
data class TraccarPosition(
    val id: Long,
    val deviceId: Long,
    val protocol: String? = null,
    val serverTime: String? = null,
    val deviceTime: String? = null,
    val fixTime: String? = null,
    val outdated: Boolean = false,
    val valid: Boolean = true,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speed: Double = 0.0,
    val course: Double = 0.0,
    val accuracy: Double = 0.0,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TraccarDevice(val id: Long, val uniqueId: String, val name: String? = null)

@Serializable
data class TraccarEvent(
    val id: Long,
    val type: String,
    val eventTime: String? = null,
    val positionId: Long = 0,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TraccarPositionForward(val position: TraccarPosition, val device: TraccarDevice)

@Serializable
data class TraccarEventForward(val event: TraccarEvent, val position: TraccarPosition? = null, val device: TraccarDevice)

object TraccarJson {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    fun positionForward(text: String): TraccarPositionForward = json.decodeFromString(TraccarPositionForward.serializer(), text)
    fun eventForward(text: String): TraccarEventForward = json.decodeFromString(TraccarEventForward.serializer(), text)
}
