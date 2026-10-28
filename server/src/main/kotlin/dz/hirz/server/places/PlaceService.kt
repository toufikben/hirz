package dz.hirz.server.places

import dz.hirz.server.apiJson
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.notFound
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.ShapeDto
import kotlinx.serialization.builtins.ListSerializer
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)

class PlaceService(private val db: DataSource, private val circles: CircleService, private val minMarginM: Int) {

    fun create(circleId: UUID, memberId: UUID, req: CreatePlaceRequest): PlaceDto = db.tx { c ->
        circles.requireRole(c, circleId, memberId, EDITORS)
        require(req.name.isNotBlank() && req.name.length <= 80) { "name must be 1–80 characters" }
        if (req.marginM < minMarginM) unprocessable("margin_below_minimum")
        val id = c.prepareStatement(
            """INSERT INTO places (circle_id, name, kind, geom, shape, margin_m, active_hours)
               VALUES (?, ?, ?, ST_GeogFromText(?), ?::jsonb, ?, ?::jsonb) RETURNING id""",
        ).apply {
            setObject(1, circleId); setString(2, req.name.trim()); setString(3, req.kind.wire())
            setString(4, "SRID=4326;" + Geometry.toWkt(req.shape))
            setString(5, apiJson.encodeToString(ShapeDto.serializer(), req.shape))
            setInt(6, req.marginM)
            setString(7, apiJson.encodeToString(ListSerializer(ActiveWindow.serializer()), req.activeHours))
        }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        audit(c, circleId, memberId, "place_created", """{"place":"$id"}""")
        PlaceDto(id.toString(), circleId.toString(), req.name.trim(), req.kind, req.shape, req.marginM, req.activeHours)
    }

    fun list(circleId: UUID, memberId: UUID): List<PlaceDto> = db.connection.use { c ->
        circles.requireRole(c, circleId, memberId)
        placesOf(c, circleId)
    }

    fun delete(circleId: UUID, placeId: UUID, memberId: UUID) = db.tx { c ->
        circles.requireRole(c, circleId, memberId, EDITORS)
        val n = c.prepareStatement("DELETE FROM places WHERE id = ? AND circle_id = ?").apply { setObject(1, placeId); setObject(2, circleId) }.executeUpdate()
        if (n == 0) notFound()
        audit(c, circleId, memberId, "place_deleted", """{"place":"$placeId"}""")
    }

    companion object {
        fun placesOf(c: Connection, circleId: UUID): List<PlaceDto> =
            c.prepareStatement("SELECT id, circle_id, name, kind, shape::text, margin_m, active_hours::text FROM places WHERE circle_id = ? ORDER BY created_at")
                .apply { setObject(1, circleId) }.executeQuery().toList(::row)

        private fun row(rs: ResultSet) = PlaceDto(
            id = rs.getString(1), circleId = rs.getString(2), name = rs.getString(3),
            kind = apiJson.decodeFromString(PlaceKind.serializer(), "\"${rs.getString(4)}\""),
            shape = apiJson.decodeFromString(ShapeDto.serializer(), rs.getString(5)),
            marginM = rs.getInt(6),
            activeHours = apiJson.decodeFromString(ListSerializer(ActiveWindow.serializer()), rs.getString(7)),
        )
    }
}

fun PlaceKind.wire(): String = apiJson.encodeToString(PlaceKind.serializer(), this).trim('"')
