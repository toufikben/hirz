package dz.hirz.server.custody

import dz.hirz.server.db.toList
import java.sql.Connection
import java.util.UUID

/** Assets another circle shares with the circles in the first `?` (a uuid[]), under an accepted, unrevoked consent. */
const val SHARED_ASSETS_SQL =
    "SELECT asset_id FROM asset_consents WHERE to_circle = ANY(?) AND accepted_at IS NOT NULL AND revoked_at IS NULL"

/**
 * Who sees an asset (design §12.1): its own circle, plus every circle it is shared with by consent. Seeing means alerts,
 * the live map, the incident and claiming it; closing, exports, links and device commands stay with the owning circle.
 */
object AssetAccess {
    fun circlesSeeing(c: Connection, assetId: UUID): List<UUID> =
        c.prepareStatement(
            """SELECT circle_id FROM assets WHERE id = ?
               UNION SELECT to_circle FROM asset_consents WHERE asset_id = ? AND accepted_at IS NOT NULL AND revoked_at IS NULL""",
        ).apply { setObject(1, assetId); setObject(2, assetId) }.executeQuery().toList { it.getObject(1, UUID::class.java) }
}
