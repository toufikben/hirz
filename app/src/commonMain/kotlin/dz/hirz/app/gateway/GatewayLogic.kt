package dz.hirz.app.gateway

/** The outcome of one outbox item, from the per-part results Android reports (design §12.3.3). */
data class SmsOutcome(val status: String, val error: String?) {
    companion object {
        private const val RESULT_OK = -1

        /** Every part must succeed; the first failing part names the error. */
        fun of(partResults: List<Int>): SmsOutcome {
            if (partResults.isEmpty()) return SmsOutcome("failed", "no_parts")
            val failed = partResults.firstOrNull { it != RESULT_OK } ?: return SmsOutcome("sent", null)
            return SmsOutcome("failed", when (failed) {
                1 -> "generic_failure"
                2 -> "radio_off"
                3 -> "null_pdu"
                4 -> "no_service"
                else -> "error_$failed"
            })
        }
    }
}

data class InboundPart(val from: String, val body: String, val receivedAtMs: Long)
data class InboundMessage(val from: String, val body: String, val receivedAtMs: Long)

/** Received SMS go to the server whole and unfiltered: the server alone decides what a reply means. */
object GatewayInbox {
    /** Joins the parts of a multipart SMS (same sender, same timestamp) in arrival order. */
    fun assemble(parts: List<InboundPart>): List<InboundMessage> =
        parts.groupBy { it.from to it.receivedAtMs }.map { (key, ps) -> InboundMessage(key.first, ps.joinToString("") { it.body }, key.second) }
}

object GatewaySchedule {
    /** Silence of 3 heartbeats (15 min) raises "gateway silent" to the owners. */
    const val HEARTBEAT_MS = 300_000L
    /** The outbox is also polled, in case a push is lost. */
    const val POLL_MS = 60_000L

    fun heartbeatDue(lastMs: Long?, nowMs: Long) = lastMs == null || nowMs - lastMs >= HEARTBEAT_MS
}
