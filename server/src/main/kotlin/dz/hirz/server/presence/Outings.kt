package dz.hirz.server.presence

import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.tx
import dz.hirz.server.escalation.OwnerOrder
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.OutingDto
import dz.hirz.shared.api.OutingPing
import dz.hirz.shared.api.OutingRequest
import dz.hirz.shared.api.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** Closer than this, guardian and asset are together. */
const val TOGETHER_M = 50.0

/**
 * Accompanied outings (design §1 "accompanied by", §12.2): a guardian starts one from the app for up to 6 h; their
 * phone pings its position and the asset's tags it hears. Only that guardian can ping or end it.
 */
class Outings(private val db: DataSource, private val clock: Clock, private val circles: CircleService) {
    fun start(assetId: UUID, memberId: UUID, req: OutingRequest): OutingDto = db.tx { c ->
        val circle = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        circles.requireRole(c, circle, memberId, setOf(Role.OWNER, Role.GUARDIAN))
        if (req.hours !in 1..6) unprocessable("hours_1_to_6")
        val now = clock.instant()
        // One outing at a time per asset: a new one replaces the old.
        c.prepareStatement("UPDATE outings SET ended_at = ? WHERE asset_id = ? AND ended_at IS NULL").apply { setTimestamp(1, now.ts()); setObject(2, assetId) }.executeUpdate()
        val ends = now.plus(Duration.ofHours(req.hours.toLong()))
        val id = c.prepareStatement(
            "INSERT INTO outings (asset_id, guardian_member, starts_at, ends_at, together_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
        ).apply { setObject(1, assetId); setObject(2, memberId); setTimestamp(3, now.ts()); setTimestamp(4, ends.ts()); setTimestamp(5, now.ts()) }
            .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        audit(c, circle, memberId, "outing_started", """{"asset":"$assetId","hours":${req.hours}}""")
        OutingDto(id.toString(), assetId.toString(), now.toEpochMilli(), ends.toEpochMilli())
    }

    private fun mine(c: Connection, outingId: UUID, memberId: UUID): UUID =
        c.prepareStatement("SELECT asset_id FROM outings WHERE id = ? AND guardian_member = ? FOR UPDATE")
            .apply { setObject(1, outingId); setObject(2, memberId) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }

    fun ping(outingId: UUID, memberId: UUID, p: OutingPing) = db.tx { c ->
        val asset = mine(c, outingId, memberId)
        val now = clock.instant()
        val tags = c.prepareStatement("SELECT tag_id FROM ble_tags WHERE asset_id = ?").apply { setObject(1, asset) }
            .executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        val heard = p.seenTags.any { it.lowercase() in tags }
        c.prepareStatement(
            """UPDATE outings SET last_ping_at = ?, last_lat = ?, last_lon = ?,
                 together_at = CASE WHEN ? THEN ? ELSE together_at END,
                 tag_heard_at = CASE WHEN ? THEN ? ELSE tag_heard_at END WHERE id = ?""",
        ).apply {
            setTimestamp(1, now.ts()); setDouble(2, p.position.lat); setDouble(3, p.position.lon)
            setBoolean(4, heard); setTimestamp(5, now.ts()); setBoolean(6, heard); setTimestamp(7, now.ts()); setObject(8, outingId)
        }.executeUpdate()
    }

    fun end(outingId: UUID, memberId: UUID) = db.tx { c ->
        mine(c, outingId, memberId)
        c.prepareStatement("UPDATE outings SET ended_at = ? WHERE id = ? AND ended_at IS NULL").apply { setTimestamp(1, clock.instant().ts()); setObject(2, outingId) }.executeUpdate()
    }

    companion object {
        fun near(a: LatLon, b: LatLon): Boolean = OwnerOrder.metres(a, b) <= TOGETHER_M

        /** Whether a guardian has the asset out on an outing at [at]: its place exits are the guardian's doing (P7). */
        fun accompanied(c: Connection, assetId: UUID, at: java.time.Instant): Boolean =
            c.prepareStatement("SELECT EXISTS (SELECT 1 FROM outings WHERE asset_id = ? AND ended_at IS NULL AND starts_at <= ? AND ends_at > ?)")
                .apply { setObject(1, assetId); setTimestamp(2, at.ts()); setTimestamp(3, at.ts()) }.executeQuery().use { it.next() && it.getBoolean(1) }
    }
}

fun Route.outingRoutes(service: Outings) {
    post("/v1/assets/{id}/outings") {
        call.respond(service.start(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<OutingRequest>()))
    }
    post("/v1/outings/{id}/ping") {
        service.ping(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<OutingPing>())
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/v1/outings/{id}") {
        service.end(uuidOrNotFound(call.parameters["id"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}
