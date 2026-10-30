package dz.hirz.server.traccar

import java.time.Instant

class TraccarUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Core's view of Traccar's REST API: devices follow claims, and positions back up the forward path. */
interface TraccarAdmin {
    fun createDevice(uniqueId: String, name: String): Long
    fun deleteDevice(id: Long)
    fun positions(deviceId: Long, from: Instant, to: Instant): List<TraccarPosition>

    companion object {
        /** No Traccar configured (tests of other features, local runs): provisioning is a no-op. */
        val NONE = object : TraccarAdmin {
            override fun createDevice(uniqueId: String, name: String): Long = 0
            override fun deleteDevice(id: Long) {}
            override fun positions(deviceId: Long, from: Instant, to: Instant): List<TraccarPosition> = emptyList()
        }
    }
}
