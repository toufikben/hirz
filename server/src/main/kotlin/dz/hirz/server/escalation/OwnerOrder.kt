package dz.hirz.server.escalation

import dz.hirz.server.db.toList
import dz.hirz.server.ts
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.rules.RuleConfig
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Candidate(val id: UUID, val priority: Int)

/**
 * Who should own an incident (design §5.3): the nearest guardian, but only with a clear lead of [leadM] over the
 * second (GPS error is 20–50 m; a smaller lead is noise); otherwise the current custodian; then circle priority.
 * Ties break by member id, so the order is deterministic.
 */
object OwnerOrder {
    fun rank(candidates: List<Candidate>, assetFix: LatLon?, locations: Map<UUID, LatLon>, custodian: UUID?, leadM: Double): List<UUID> {
        val byPriority = candidates.sortedWith(compareBy({ it.priority }, { it.id.toString() })).map { it.id }
        val nearest = assetFix?.let { fix ->
            val known = byPriority.mapNotNull { id -> locations[id]?.let { id to metres(fix, it) } }.sortedBy { it.second }
            if (known.size >= 2 && known[1].second - known[0].second >= leadM) known[0].first else null
        }
        val first = listOfNotNull(nearest, custodian?.takeIf { it in byPriority && it != nearest })
        return first + byPriority.filter { it !in first }
    }

    /** Great-circle distance in metres. */
    fun metres(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}

/** Production ordering: candidates, the asset's last good fix, guardian locations from the last 2 minutes, and the custodian. */
class NearestOrdering(private val cfg: RuleConfig) : OwnerOrdering {
    override fun order(c: Connection, incidentId: UUID, circleId: UUID, now: Instant): List<UUID> {
        val candidates = c.prepareStatement(
            """SELECT member_id, priority FROM circle_members WHERE circle_id = ? AND role IN ('owner', 'guardian', 'temporary')
               AND (expires_at IS NULL OR expires_at > ?)""",
        ).apply { setObject(1, circleId); setTimestamp(2, now.ts()) }.executeQuery().toList { Candidate(it.getObject(1, UUID::class.java), it.getInt(2)) }
        val fix = c.prepareStatement(
            """SELECT ST_Y(p.geog::geometry), ST_X(p.geog::geometry) FROM positions p JOIN incidents i ON i.asset_id = p.asset_id
               WHERE i.id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL ORDER BY p.fix_time DESC NULLS LAST LIMIT 1""",
        ).apply { setObject(1, incidentId) }.executeQuery().use { if (it.next()) LatLon(it.getDouble(1), it.getDouble(2)) else null }
        val locations = c.prepareStatement(
            """SELECT DISTINCT ON (member_id) member_id, ST_Y(geog::geometry), ST_X(geog::geometry) FROM member_locations
               WHERE incident_id = ? AND at > ? ORDER BY member_id, at DESC""",
        ).apply { setObject(1, incidentId); setTimestamp(2, now.minusSeconds(120).ts()) }
            .executeQuery().toList { it.getObject(1, UUID::class.java) to LatLon(it.getDouble(2), it.getDouble(3)) }.toMap()
        val custodian = c.prepareStatement(
            """SELECT h.member_id FROM handovers h JOIN incidents i ON i.asset_id = h.asset_id
               WHERE i.id = ? AND h.starts_at <= ? AND h.ends_at > ? ORDER BY h.starts_at DESC LIMIT 1""",
        ).apply { setObject(1, incidentId); setTimestamp(2, now.ts()); setTimestamp(3, now.ts()) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
        return OwnerOrder.rank(candidates, fix, locations, custodian, cfg.nearestLeadM)
    }
}
