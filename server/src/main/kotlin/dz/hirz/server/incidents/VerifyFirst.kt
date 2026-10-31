package dz.hirz.server.incidents

import dz.hirz.server.ts
import dz.hirz.shared.rules.RuleConfig
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * False-alarm history (design §12.2): rules whose incidents on this asset were reviewed as false alarms at least
 * [RuleConfig.verifyFirstReviews] times by an owner (different incidents) within [RuleConfig.verifyFirstWindowMs]. Their P1 starts
 * quietly; a P0 is never affected; the owner can switch it off for the asset.
 */
object VerifyFirst {
    fun rules(c: Connection, assetId: UUID, now: Instant, cfg: RuleConfig): Set<String> {
        val off = c.prepareStatement("SELECT verify_first_off FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { it.next() && it.getBoolean(1) }
        if (off) return emptySet()
        return c.prepareStatement(
            """SELECT i.rule FROM incident_reviews r JOIN incidents i ON i.id = r.incident_id
               JOIN circle_members cm ON cm.circle_id = i.circle_id AND cm.member_id = r.member_id AND cm.role = 'owner'
               WHERE i.asset_id = ? AND r.false_alarm AND r.at >= ? AND i.tier = 'P1'
               GROUP BY i.rule HAVING count(DISTINCT r.incident_id) >= ?""",
        ).apply { setObject(1, assetId); setTimestamp(2, now.minusMillis(cfg.verifyFirstWindowMs).ts()); setInt(3, cfg.verifyFirstReviews) }
            .executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }
}
