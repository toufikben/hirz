package dz.hirz.server.rules

import dz.hirz.server.apiJson
import dz.hirz.server.ts
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import kotlinx.serialization.builtins.ListSerializer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A rule's conclusion about one asset at one moment. [silent]: never shown or sounded on the carrier (duress). */
data class Finding(
    val assetId: UUID,
    val circleId: UUID,
    val rule: RuleId,
    val tier: Tier,
    val reasons: List<Reason>,
    val at: Instant,
    val silent: Boolean = false,
)

/** Where findings go next (the incident service in production). */
interface IncidentSink {
    fun open(finding: Finding, findingId: Long)
    fun reasonAdded(incidentId: UUID)

    companion object {
        val NONE = object : IncidentSink {
            override fun open(finding: Finding, findingId: Long) {}
            override fun reasonAdded(incidentId: UUID) {}
        }
    }
}

/** Stores every finding (the audit trail of what the engine concluded) and hands it to the incident layer. */
class FindingRecorder(private val db: DataSource, private val clock: Clock, private val sink: IncidentSink) {
    private val reasonsSerializer = ListSerializer(Reason.serializer())

    fun record(f: Finding) {
        val id = db.connection.use { c ->
            c.prepareStatement("INSERT INTO findings (asset_id, rule, tier, reasons, at) VALUES (?, ?, ?, ?::jsonb, ?) RETURNING id").apply {
                setObject(1, f.assetId); setString(2, f.rule.name); setString(3, f.tier.name)
                setString(4, apiJson.encodeToString(reasonsSerializer, f.reasons)); setTimestamp(5, f.at.ts())
            }.executeQuery().use { it.next(); it.getLong(1) }
        }
        sink.open(f, id)
    }

    /** Adds [reason] to the asset's open P0/P1 incident (of [rule], when given), if any; returns false when there is none. */
    fun appendToOpen(assetId: UUID, reason: Reason, rule: dz.hirz.shared.api.RuleId? = null): Boolean {
        val incidentId = db.connection.use { c ->
            val id = c.prepareStatement(
                "SELECT id FROM incidents WHERE asset_id = ? AND state = 'open' AND tier IN ('P0', 'P1') AND (?::text IS NULL OR rule = ?) ORDER BY opened_at LIMIT 1",
            ).apply { setObject(1, assetId); setString(2, rule?.name); setString(3, rule?.name) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
                ?: return false
            val json = apiJson.encodeToString(Reason.serializer(), reason)
            c.prepareStatement("INSERT INTO incident_events (incident_id, kind, data, at) VALUES (?, 'reason', ?::jsonb, ?)")
                .apply { setObject(1, id); setString(2, json); setTimestamp(3, clock.instant().ts()) }.executeUpdate()
            c.prepareStatement("UPDATE incidents SET reasons = reasons || jsonb_build_array(?::jsonb) WHERE id = ?")
                .apply { setString(1, json); setObject(2, id) }.executeUpdate()
            id
        }
        sink.reasonAdded(incidentId)
        return true
    }
}
