package dz.hirz.server.assets

import dz.hirz.server.ApiException
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.tx
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.TodayRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.sql.Connection
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

const val MAX_PHOTO_BYTES = 512 * 1024
private val CARERS = setOf(Role.OWNER, Role.GUARDIAN, Role.TEMPORARY)

/** The asset photo and today's clothes (design §5.8): descriptors that help a stranger or the police recognise the asset. */
class AssetMediaService(private val db: DataSource, private val clock: Clock, private val circles: CircleService) {
    private fun circleOfAsset(c: Connection, assetId: UUID): UUID =
        c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }

    fun setPhoto(assetId: UUID, memberId: UUID, jpeg: ByteArray) = db.tx { c ->
        val circleId = circleOfAsset(c, assetId)
        circles.requireRole(c, circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN))
        if (jpeg.size > MAX_PHOTO_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, "photo_too_large")
        if (!isJpeg(jpeg)) throw ApiException(HttpStatusCode.UnsupportedMediaType, "jpeg_only")
        c.prepareStatement(
            """INSERT INTO asset_photos (asset_id, jpeg, updated_at) VALUES (?, ?, ?)
               ON CONFLICT (asset_id) DO UPDATE SET jpeg = EXCLUDED.jpeg, updated_at = EXCLUDED.updated_at""",
        ).apply { setObject(1, assetId); setBytes(2, jpeg); setTimestamp(3, clock.instant().ts()) }.executeUpdate()
        audit(c, circleId, memberId, "asset_photo_set", """{"asset":"$assetId"}""")
    }

    fun photo(assetId: UUID, memberId: UUID): ByteArray = db.connection.use { c ->
        circles.requireRole(c, circleOfAsset(c, assetId), memberId)
        c.prepareStatement("SELECT jpeg FROM asset_photos WHERE asset_id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getBytes(1) else notFound() }
    }

    fun setToday(assetId: UUID, memberId: UUID, clothes: String) = db.tx { c ->
        circles.requireRole(c, circleOfAsset(c, assetId), memberId, CARERS)
        val text = clothes.trim()
        require(text.length <= 200) { "clothes must be at most 200 characters" }
        c.prepareStatement("UPDATE assets SET today_clothes = ?, today_clothes_at = ? WHERE id = ?").apply {
            setString(1, text.ifEmpty { null }); setTimestamp(2, if (text.isEmpty()) null else clock.instant().ts()); setObject(3, assetId)
        }.executeUpdate()
    }

    private fun isJpeg(b: ByteArray) = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
}

fun Route.assetMediaRoutes(service: AssetMediaService) {
    put("/v1/assets/{id}/photo") {
        val id = uuidOrNotFound(call.parameters["id"])
        val declared = call.request.headers["Content-Length"]?.toLongOrNull()
        if (declared != null && declared > MAX_PHOTO_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, "photo_too_large")
        // Read at most one byte past the limit, so an undeclared huge body is never buffered whole.
        val bytes = call.receiveChannel().readRemaining(MAX_PHOTO_BYTES + 1L).readByteArray()
        service.setPhoto(id, call.member().memberId, bytes)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/v1/assets/{id}/photo") {
        call.respondBytes(service.photo(uuidOrNotFound(call.parameters["id"]), call.member().memberId), ContentType.Image.JPEG)
    }
    put("/v1/assets/{id}/today") {
        service.setToday(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<TodayRequest>().clothes)
        call.respond(HttpStatusCode.NoContent)
    }
}
