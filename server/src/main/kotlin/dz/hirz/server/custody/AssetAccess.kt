package dz.hirz.server.custody

import dz.hirz.server.db.toList
import java.sql.Connection
import java.util.UUID

/** Assets another circle shares with the circles in the first `?` (a uuid[]), under an accepted, unrevoked consent. */
const val SHARED_ASSETS_SQL =
    "SELECT asset_id FROM asset_consents WHERE to_circle = ANY(?) AND accepted_at IS NOT NULL AND revoked_at IS NULL AND consent_live(windows, expires_at, ?)"

/**
 * Who sees an asset (design §12.1): its own circle, plus every circle it is shared with by consent (SHARED_ASSETS_SQL takes
 * the circles and the instant). Seeing means alerts,
 * the live map, the incident and claiming it; closing, exports, links and device commands stay with the owning circle.
 */
object AssetAccess {
    /** The circles that see [assetId] at [at]: an institution only inside its consent's hours and before it ends (P9). */
    fun circlesSeeing(c: Connection, assetId: UUID, at: java.time.Instant): List<UUID> =
        c.prepareStatement(
            """SELECT circle_id FROM assets WHERE id = ?
               UNION SELECT to_circle FROM asset_consents WHERE asset_id = ? AND accepted_at IS NOT NULL AND revoked_at IS NULL
                 AND consent_live(windows, expires_at, ?)""",
        ).apply { setObject(1, assetId); setObject(2, assetId); setTimestamp(3, java.sql.Timestamp.from(at)) }.executeQuery().toList { it.getObject(1, UUID::class.java) }
}
