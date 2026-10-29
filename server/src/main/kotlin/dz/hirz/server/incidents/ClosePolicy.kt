package dz.hirz.server.incidents

import dz.hirz.server.apiJson
import dz.hirz.server.ts
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.PendingCloseDto
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** A recent fix counts as proof only if it is this fresh. */
private const val PROOF_FIX_AGE_S = 120L
/** A guardian standing this close to the last fix proves "found safe". */
private const val PROOF_MEMBER_RADIUS_M = 100.0
private val SAFE_KINDS = listOf("home", "school", "relative")

data class CloseRequestRow(val requestedBy: UUID, val reason: CloseReason, val at: Instant, val expiresAt: Instant) {
    fun dto() = PendingCloseDto(requestedBy.toString(), reason, at.toEpochMilli(), expiresAt.toEpochMilli())
}

/**
 * Who may close an incident alone (design §5.7). A P0 always needs a second guardian (or the only guardian's second
 * tap); "found safe" without proof needs the same even for P1, so one mistaken tap never silences a real alarm.
 */
object ClosePolicy {
    fun wire(reason: CloseReason) = apiJson.encodeToString(CloseReason.serializer(), reason).trim('"')
    private fun reasonOf(wire: String) = apiJson.decodeFromString(CloseReason.serializer(), "\"$wire\"")

    fun pending(c: Connection, incidentId: UUID, forUpdate: Boolean = false): CloseRequestRow? =
        c.prepareStatement("SELECT requested_by, reason, at, expires_at FROM close_requests WHERE incident_id = ?${if (forUpdate) " FOR UPDATE" else ""}")
            .apply { setObject(1, incidentId) }.executeQuery().use {
                if (it.next()) CloseRequestRow(it.getObject(1, UUID::class.java), reasonOf(it.getString(2)), it.getTimestamp(3).toInstant(), it.getTimestamp(4).toInstant())
                else null
            }

    fun request(c: Connection, incidentId: UUID, memberId: UUID, reason: CloseReason, now: Instant, expiresAt: Instant) {
        c.prepareStatement(
            """INSERT INTO close_requests (incident_id, requested_by, reason, at, expires_at) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT (incident_id) DO UPDATE SET requested_by = EXCLUDED.requested_by, reason = EXCLUDED.reason,
                 at = EXCLUDED.at, expires_at = EXCLUDED.expires_at""",
        ).apply {
            setObject(1, incidentId); setObject(2, memberId); setString(3, wire(reason)); setTimestamp(4, now.ts()); setTimestamp(5, expiresAt.ts())
        }.executeUpdate()
    }

    fun drop(c: Connection, incidentId: UUID) {
        c.prepareStatement("DELETE FROM close_requests WHERE incident_id = ?").apply { setObject(1, incidentId) }.executeUpdate()
    }

    /** Owners and guardians who could confirm; temporary members and viewers never count toward the quorum. */
    fun editorCount(c: Connection, circleId: UUID, now: Instant): Int =
        c.prepareStatement(
            "SELECT count(*) FROM circle_members WHERE circle_id = ? AND role IN ('owner', 'guardian') AND (expires_at IS NULL OR expires_at > ?)",
        ).apply { setObject(1, circleId); setTimestamp(2, now.ts()) }.executeQuery().use { it.next(); it.getInt(1) }

    /**
     * "Found safe" is proven by the asset's last good fix (≤ 120 s old) inside a home, school or relative place,
     * or by a guardian location (≤ 120 s old) within 100 m of that fix.
     */
    fun foundSafeProof(c: Connection, incidentId: UUID, now: Instant): Boolean {
        val fresh = now.minusSeconds(PROOF_FIX_AGE_S).ts()
        return c.prepareStatement(
            """WITH fix AS (
                 SELECT p.geog, i.circle_id, i.id AS incident_id FROM positions p JOIN incidents i ON i.asset_id = p.asset_id
                 WHERE i.id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL AND p.fix_time >= ?
                 ORDER BY p.fix_time DESC LIMIT 1)
               SELECT EXISTS (
                 SELECT 1 FROM fix JOIN places pl ON pl.circle_id = fix.circle_id AND pl.kind = ANY(?)
                 WHERE CASE WHEN GeometryType(pl.geom::geometry) = 'POINT'
                            THEN ST_DWithin(pl.geom, fix.geog, (pl.shape->>'radiusM')::float8)
                            ELSE ST_Covers(pl.geom, fix.geog) END
               ) OR EXISTS (
                 SELECT 1 FROM fix JOIN member_locations ml ON ml.incident_id = fix.incident_id
                 WHERE ml.at >= ? AND ST_DWithin(ml.geog, fix.geog, ?)
               )""",
        ).apply {
            setObject(1, incidentId); setTimestamp(2, fresh); setArray(3, c.createArrayOf("text", SAFE_KINDS.toTypedArray()))
            setTimestamp(4, fresh); setDouble(5, PROOF_MEMBER_RADIUS_M)
        }.executeQuery().use { it.next(); it.getBoolean(1) }
    }
}
