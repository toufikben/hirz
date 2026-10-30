package dz.hirz.server.ble

import dz.hirz.server.auth.SourcePrincipal
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.conflict
import dz.hirz.server.db.tx
import dz.hirz.server.ingest.IngestService
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.source
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.RelayBatch
import dz.hirz.shared.api.RelayResult
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.TagRequest
import dz.hirz.shared.ble.BleFrames
import dz.hirz.shared.signal.FixSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.abs
import kotlin.math.round

private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)

/** Thresholds for tags and the relay (starting values, to be measured on real collars). */
data class BleLimits(
    /** A tag this loud is in hand (about 2 m): proof of possession at registration. */
    val proofRssi: Int = -60,
    val proofMaxAge: Duration = Duration.ofMinutes(2),
    /** One sighting per tag per relay this often. */
    val relayEvery: Duration = Duration.ofSeconds(30),
    /** Older sightings (a phone that was offline) are still stored-and-forwarded, up to this. */
    val maxSightingAge: Duration = Duration.ofHours(72),
)

/**
 * BLE tags and the phone relay (design §2, decision §14c). A sighting becomes a fix for the tag's asset at the relay's
 * position, coarsened to about 10 m, with the relay's accuracy plus the radio range. The relay's identity never reaches
 * the tag's circle: positions carry no source, and sightings are not exposed.
 */
class BleService(
    private val db: DataSource, private val clock: Clock, private val circles: CircleService, private val ingest: IngestService,
    private val limits: BleLimits = BleLimits(),
) {
    fun register(assetId: UUID, memberId: UUID, req: TagRequest) = db.tx { c ->
        val circle = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        circles.requireRole(c, circle, memberId, EDITORS)
        val id = req.tagId.trim().lowercase()
        if (id.length !in 8..80 || !id.all { it.isLetterOrDigit() || it in ":-" }) unprocessable("bad_tag_id")
        // Proof of possession (the P3 lesson): the owner's phone heard this tag loudly, just now.
        val age = Duration.between(Instant.ofEpochMilli(req.proofAtMs), clock.instant()).abs()
        if (req.proofRssi < limits.proofRssi || age > limits.proofMaxAge) unprocessable("no_proof_of_possession")
        val taken = c.prepareStatement("SELECT 1 FROM ble_tags WHERE tag_id = ?").apply { setString(1, id) }.executeQuery().use { it.next() }
        if (taken) conflict("tag_taken")
        val source = c.prepareStatement("INSERT INTO sources (asset_id, kind, device_info) VALUES (?, 'ble', ?::jsonb) RETURNING id")
            .apply { setObject(1, assetId); setString(2, """{"format":"${req.format.take(20)}"}""") }
            .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        c.prepareStatement("INSERT INTO ble_tags (tag_id, asset_id, source_id, format) VALUES (?, ?, ?, ?)")
            .apply { setString(1, id); setObject(2, assetId); setObject(3, source); setString(4, req.format.take(20)) }.executeUpdate()
        audit(c, circle, memberId, "ble_tag_registered", """{"asset":"$assetId"}""")
    }

    fun unregister(assetId: UUID, memberId: UUID, tagId: String) = db.tx { c ->
        val circle = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        circles.requireRole(c, circle, memberId, EDITORS)
        val source = c.prepareStatement("DELETE FROM ble_tags WHERE tag_id = ? AND asset_id = ? RETURNING source_id")
            .apply { setString(1, tagId.lowercase()); setObject(2, assetId) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        c.prepareStatement("UPDATE sources SET revoked_at = ? WHERE id = ?").apply { setTimestamp(1, clock.instant().ts()); setObject(2, source) }.executeUpdate()
        audit(c, circle, memberId, "ble_tag_removed", """{"asset":"$assetId"}""")
    }

    /** The tags a relay phone may upload: those registered in its own circle, so strangers' tags never leave a phone. */
    fun tagsFor(relay: SourcePrincipal): List<String> = db.tx { c ->
        c.prepareStatement(
            """SELECT t.tag_id FROM ble_tags t JOIN assets a ON a.id = t.asset_id JOIN sources s ON s.id = t.source_id
               WHERE a.circle_id = ? AND s.revoked_at IS NULL ORDER BY t.tag_id""",
        ).apply { setObject(1, relay.circleId) }.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    fun relay(relay: SourcePrincipal, batch: RelayBatch): RelayResult {
        if (batch.sightings.size > 200) unprocessable("batch_too_large")
        val now = clock.instant()
        var accepted = 0
        var dropped = 0
        for (s in batch.sightings) {
            val target = db.tx { c ->
                val tag = c.prepareStatement(
                    """SELECT t.source_id, t.asset_id, a.circle_id FROM ble_tags t JOIN assets a ON a.id = t.asset_id
                       JOIN sources src ON src.id = t.source_id WHERE t.tag_id = ? AND src.revoked_at IS NULL""",
                ).apply { setString(1, s.tagId.trim().lowercase()) }.executeQuery().use {
                    if (it.next()) SourcePrincipal(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getObject(3, UUID::class.java)) else null
                } ?: return@tx null
                val seen = Instant.ofEpochMilli(s.seenAtMs)
                if (seen.isAfter(now.plusSeconds(60)) || Duration.between(seen, now) > limits.maxSightingAge) return@tx null
                val recent = c.prepareStatement(
                    "SELECT 1 FROM ble_sightings WHERE tag_id = ? AND relay_source_id = ? AND abs(extract(epoch FROM (seen_at - ?))) < ?",
                ).apply { setString(1, s.tagId.lowercase()); setObject(2, relay.sourceId); setTimestamp(3, seen.ts()); setDouble(4, limits.relayEvery.seconds.toDouble()) }
                    .executeQuery().use { it.next() }
                if (recent) return@tx null
                c.prepareStatement("INSERT INTO ble_sightings (tag_id, relay_source_id, seen_at, rssi) VALUES (?, ?, ?, ?)")
                    .apply { setString(1, s.tagId.lowercase()); setObject(2, relay.sourceId); setTimestamp(3, seen.ts()); setInt(4, s.rssi) }.executeUpdate()
                tag
            }
            if (target == null) { dropped++; continue }
            val where = LatLon(coarse(batch.relay.position.lat), coarse(batch.relay.position.lon))
            val accuracy = batch.relay.accuracyM + BleFrames.rangeM(s.rssi)
            val signal = IngestSignal(
                clientId = "ble-${abs(s.tagId.hashCode())}-${s.seenAtMs}".take(64), fixTimeMs = s.seenAtMs, position = where,
                accuracyM = accuracy, fixSource = FixSource.BLE_RELAY,
            )
            accepted += ingest.ingest(target, IngestBatch(listOf(signal))).accepted
        }
        return RelayResult(accepted, dropped)
    }

    /** About 10 m: the tag is near the relay, not at it, and the relay's exact spot is nobody else's business. */
    private fun coarse(v: Double) = round(v * 10_000) / 10_000
}

fun Route.bleMemberRoutes(service: BleService) {
    post("/v1/assets/{id}/tags") {
        service.register(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<TagRequest>())
        call.respond(HttpStatusCode.OK, mapOf("registered" to "true"))
    }
    delete("/v1/assets/{id}/tags/{tagId}") {
        service.unregister(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.parameters["tagId"] ?: notFound())
        call.respond(HttpStatusCode.NoContent)
    }
}

fun Route.bleRelayRoutes(service: BleService) {
    post("/v1/relay/sightings") { call.respond(service.relay(call.source(), call.receive<RelayBatch>())) }
    get("/v1/relay/tags") { call.respond(service.tagsFor(call.source())) }
}
