package dz.hirz.server.exports

import dz.hirz.server.apiJson
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.live.logView
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.sms.SmsText
import dz.hirz.server.ts
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.Role
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.sql.DataSource

private val ALGIERS: ZoneId = ZoneId.of("Africa/Algiers")
private const val WINDOW_BEFORE_S = 2 * 3600L
/** Silence longer than this between two stored fixes is a gap; nothing is ever interpolated across it. */
const val GAP_THRESHOLD_S = 300L
private const val CSV_HEADER =
    "fix_time_utc,fix_time_algiers,server_time_utc,lat,lon,accuracy_m,accuracy_estimated,fix_source,speed_kmh,heading,battery,source_id,source_kind,device_model,quality"

private data class Row(
    val fixTime: Instant, val serverTime: Instant, val lat: Double, val lon: Double, val accuracyM: Double?, val estimated: Boolean,
    val fixSource: String?, val speedKmh: Double?, val heading: Double?, val battery: Int?, val sourceId: String, val sourceKind: String,
    val model: String?, val quality: String,
)

/**
 * The police pack (design §5.8): every stored fix in the window, a GPX track, the incident record, a gap
 * certificate and a SHA-256 manifest, so a recipient can check nothing was altered or invented.
 */
class PolicePack(
    private val db: DataSource,
    private val clock: Clock,
    private val circles: CircleService,
    private val incidents: IncidentService,
    private val serverVersion: String,
) {
    fun build(incidentId: UUID, memberId: UUID): Pair<String, ByteArray> {
        val now = clock.instant()
        return db.tx { c ->
            val (assetId, circleId) = c.prepareStatement("SELECT asset_id, circle_id FROM incidents WHERE id = ?").apply { setObject(1, incidentId) }
                .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java) else notFound() }
            circles.requireRole(c, circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN))
            val incident = incidents.load(c, incidentId)
            val from = Instant.ofEpochMilli(incident.openedAtMs).minusSeconds(WINDOW_BEFORE_S)
            val to = incident.closedAtMs?.let(Instant::ofEpochMilli) ?: now
            val rows = c.prepareStatement(
                """SELECT p.fix_time, p.server_time, ST_Y(p.geog::geometry), ST_X(p.geog::geometry), p.accuracy_m, p.accuracy_estimated,
                          p.fix_source, p.speed_kmh, p.heading, p.battery, s.id, s.kind, s.device_info->>'model', p.quality
                   FROM positions p JOIN sources s ON s.id = p.source_id
                   WHERE p.asset_id = ? AND p.geog IS NOT NULL AND p.fix_time BETWEEN ? AND ? ORDER BY p.fix_time, p.id""",
            ).apply { setObject(1, assetId); setTimestamp(2, from.ts()); setTimestamp(3, to.ts()) }.executeQuery().toList { rs ->
                Row(
                    rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getDouble(3), rs.getDouble(4),
                    rs.getDouble(5).takeUnless { rs.wasNull() }, rs.getBoolean(6), rs.getString(7),
                    rs.getDouble(8).takeUnless { rs.wasNull() }, rs.getDouble(9).takeUnless { rs.wasNull() },
                    rs.getInt(10).takeUnless { rs.wasNull() }, rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                )
            }
            val files = linkedMapOf(
                "positions.csv" to csv(rows).toByteArray(),
                "track.gpx" to gpx(incident, rows).toByteArray(),
                "incident.json" to apiJson.encodeToString(IncidentDto.serializer(), incident).toByteArray(),
                "completeness.txt" to completeness(rows, from, to).toByteArray(),
            )
            files["manifest.json"] = manifest(files, now, memberId).toByteArray()
            c.prepareStatement("UPDATE incidents SET exported_at = ? WHERE id = ?").apply { setTimestamp(1, now.ts()); setObject(2, incidentId) }.executeUpdate()
            audit(c, circleId, memberId, "police_pack_exported", """{"incident":"$incidentId","fixes":${rows.size}}""")
            if (rows.isNotEmpty()) logView(c, memberId, assetId, "police_pack", now)
            "hirz-${SmsText.ref(incidentId)}.zip" to zip(files)
        }
    }

    private fun csv(rows: List<Row>) = buildString {
        appendLine(CSV_HEADER)
        rows.forEach { r ->
            listOf(
                r.fixTime.toString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(r.fixTime.atZone(ALGIERS)), r.serverTime.toString(),
                r.lat.toString(), r.lon.toString(), r.accuracyM?.toString(), r.estimated.toString(), r.fixSource, r.speedKmh?.toString(),
                r.heading?.toString(), r.battery?.toString(), r.sourceId, r.sourceKind, r.model, r.quality,
            ).joinToString(",") { csvCell(it.orEmpty()) }.let(::appendLine)
        }
    }

    private fun csvCell(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Only fixes of good quality are drawn; impossible jumps stay in the CSV, marked. */
    private fun gpx(incident: IncidentDto, rows: List<Row>) = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="Hirz" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <trk><name>${xml(incident.assetName)} ${SmsText.ref(UUID.fromString(incident.id))}</name><trkseg>")
        rows.filter { it.quality == "ok" }.forEach { r ->
            appendLine("""    <trkpt lat="${r.lat}" lon="${r.lon}"><time>${r.fixTime}</time></trkpt>""")
        }
        appendLine("  </trkseg></trk>")
        appendLine("</gpx>")
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun completeness(rows: List<Row>, from: Instant, to: Instant) = buildString {
        appendLine("window_start_utc=$from")
        appendLine("window_end_utc=$to")
        appendLine("fixes=${rows.size}")
        appendLine("fixes_ok=${rows.count { it.quality == "ok" }}")
        appendLine("first_fix_utc=${rows.firstOrNull()?.fixTime ?: "none"}")
        appendLine("last_fix_utc=${rows.lastOrNull()?.fixTime ?: "none"}")
        appendLine("gap_threshold_s=$GAP_THRESHOLD_S")
        val gaps = rows.zipWithNext().mapNotNull { (a, b) ->
            val s = b.fixTime.epochSecond - a.fixTime.epochSecond
            if (s > GAP_THRESHOLD_S) Triple(a.fixTime, b.fixTime, s) else null
        }
        appendLine("gaps=${gaps.size}")
        gaps.forEach { (a, b, s) -> appendLine("gap start=$a end=$b duration_s=$s") }
        appendLine("note=positions are only those received; nothing between two fixes is estimated or interpolated")
    }

    private fun manifest(files: Map<String, ByteArray>, now: Instant, memberId: UUID) = buildJsonObject {
        putJsonArray("files") {
            files.forEach { (name, bytes) ->
                addJsonObject { put("name", name); put("sha256", sha256(bytes)); put("bytes", bytes.size) }
            }
        }
        put("exportedAtUtc", now.toString())
        put("exportedBy", memberId.toString())
        put("serverVersion", serverVersion)
        putJsonArray("algorithms") { add("SHA-256") }
    }.toString()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            files.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name).apply { time = 0 })
                z.write(bytes)
                z.closeEntry()
            }
        }
    }.toByteArray()
}

fun Route.exportRoutes(pack: PolicePack) {
    get("/v1/incidents/{id}/police-pack") {
        val (name, bytes) = pack.build(uuidOrNotFound(call.parameters["id"]), call.member().memberId)
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
        call.respondBytes(bytes, ContentType.Application.Zip)
    }
}
