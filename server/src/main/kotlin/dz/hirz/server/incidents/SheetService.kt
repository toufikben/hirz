package dz.hirz.server.incidents

import dz.hirz.server.apiJson
import dz.hirz.server.assets.assetTypeOf
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.emergency.EmergencyService
import dz.hirz.server.live.POSITION_COLUMNS
import dz.hirz.server.live.logView
import dz.hirz.server.live.positionRow
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.shared.api.SheetDto
import dz.hirz.shared.api.SheetSourceDto
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

private val LOCAL_ZONE: ZoneId = ZoneId.of("Africa/Algiers")
private const val TRACK_MINUTES = 30L

/** The incident sheet (design §5.8). Reading it is a location view and is logged like the live map. */
class SheetService(
    private val db: DataSource,
    private val clock: Clock,
    private val circles: CircleService,
    private val incidents: IncidentService,
    private val emergency: EmergencyService,
) {
    /** When the incident's public share link expires, if one is active. */
    var shareActiveUntil: (UUID) -> java.time.Instant? = { null }

    fun sheet(incidentId: UUID, memberId: UUID): SheetDto {
        val now = clock.instant()
        val (base, circleId, country) = db.connection.use { c ->
            val (assetId, circleId) = c.prepareStatement("SELECT asset_id, circle_id FROM incidents WHERE id = ?").apply { setObject(1, incidentId) }
                .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java) else notFound() }
            circles.requireRole(c, circleId, memberId)
            val incident = incidents.load(c, incidentId)
            data class Asset(val name: String, val type: String, val descriptors: String, val clothes: String?, val clothesAt: java.time.Instant?, val photo: Boolean, val condition: String?)
            val asset = c.prepareStatement(
                """SELECT a.name, a.type, a.descriptors::text, a.today_clothes, a.today_clothes_at,
                          EXISTS (SELECT 1 FROM asset_photos p WHERE p.asset_id = a.id),
                          CASE WHEN a.condition_on_sheet AND a.condition <> 'none' THEN a.condition END
                   FROM assets a WHERE a.id = ?""",
            ).apply { setObject(1, assetId) }.executeQuery().use {
                it.next(); Asset(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getTimestamp(5)?.toInstant(), it.getBoolean(6), it.getString(7))
            }
            val track = c.prepareStatement(
                """SELECT $POSITION_COLUMNS FROM positions p WHERE p.asset_id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL
                   AND p.fix_time >= ? AND p.fix_time <= ? ORDER BY p.fix_time""",
            ).apply { setObject(1, assetId); setTimestamp(2, now.minusSeconds(TRACK_MINUTES * 60).ts()); setTimestamp(3, now.ts()) }
                .executeQuery().toList { positionRow(it) }
            val last = track.lastOrNull() ?: c.prepareStatement(
                """SELECT $POSITION_COLUMNS FROM positions p WHERE p.asset_id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL
                   ORDER BY p.fix_time DESC NULLS LAST LIMIT 1""",
            ).apply { setObject(1, assetId) }.executeQuery().use { if (it.next()) positionRow(it) else null }
            val sources = c.prepareStatement(
                "SELECT kind, device_info->>'model', device_info->>'imei' FROM sources WHERE asset_id = ? AND revoked_at IS NULL ORDER BY created_at",
            ).apply { setObject(1, assetId) }.executeQuery().toList { SheetSourceDto(it.getString(1), it.getString(2), it.getString(3)) }
            val country = c.prepareStatement("SELECT country FROM circles WHERE id = ?").apply { setObject(1, circleId) }
                .executeQuery().use { it.next(); it.getString(1) }
            if (last != null) logView(c, memberId, assetId, "sheet", now)
            val sameDay = asset.clothesAt?.atZone(LOCAL_ZONE)?.toLocalDate() == now.atZone(LOCAL_ZONE).toLocalDate()
            SheetDto(
                incident = incident,
                assetName = asset.name,
                assetType = assetTypeOf(asset.type),
                descriptors = apiJson.parseToJsonElement(asset.descriptors).jsonObject,
                todayClothes = asset.clothes.takeIf { sameDay },
                hasPhoto = asset.photo,
                // Only when the owner ticked it: it helps searchers (an autistic child may not answer their name).
                condition = asset.condition?.let { dz.hirz.shared.asset.Condition.valueOf(it.uppercase()) },
                lastFix = last,
                lastFixAgeS = last?.let { (now.toEpochMilli() - it.fixTimeMs) / 1000 },
                track = track,
                sources = sources,
                shareActiveUntilMs = shareActiveUntil(incidentId)?.toEpochMilli(),
            ).let { Triple(it, circleId, country) }
        }
        return base.copy(emergencyNumbers = emergency.list(country, circleId, memberId))
    }
}

fun Route.sheetRoutes(service: SheetService) {
    get("/v1/incidents/{id}/sheet") {
        call.respond(service.sheet(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
}
