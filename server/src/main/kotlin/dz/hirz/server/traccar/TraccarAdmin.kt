package dz.hirz.server.traccar

import java.time.Instant

/** Traccar answers 200 when a command went to a connected device and 202 when it queued it for later. */
enum class CommandResult { SENT, QUEUED }

class TraccarUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Core's view of Traccar's REST API: devices follow claims, and positions back up the forward path. */
interface TraccarAdmin {
    fun createDevice(uniqueId: String, name: String): Long
    fun deleteDevice(id: Long)
    fun positions(deviceId: Long, from: Instant, to: Instant): List<TraccarPosition>
    /** online, offline or unknown. */
    fun deviceStatus(id: Long): String
    /** Traccar's version, e.g. "6.16.0"; null when unknown. */
    fun serverVersion(): String?
    fun sendCommand(deviceId: Long, type: String): CommandResult
    /** Counters a queued command (see [TraccarHttpAdmin.deleteQueued]); throws when that could not be done. */
    fun deleteQueued(deviceId: Long)

    companion object {
        /** No Traccar configured (tests of other features, local runs): provisioning is a no-op. */
        val NONE = object : TraccarAdmin {
            override fun createDevice(uniqueId: String, name: String): Long = 0
            override fun deleteDevice(id: Long) {}
            override fun positions(deviceId: Long, from: Instant, to: Instant): List<TraccarPosition> = emptyList()
            override fun deviceStatus(id: Long): String = "unknown"
            override fun serverVersion(): String? = null
            override fun sendCommand(deviceId: Long, type: String): CommandResult = throw TraccarUnavailable("no Traccar configured")
            override fun deleteQueued(deviceId: Long) {}
        }
    }
}
