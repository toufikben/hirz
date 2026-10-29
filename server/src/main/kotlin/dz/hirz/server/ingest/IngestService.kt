package dz.hirz.server.ingest

import dz.hirz.server.ApiException
import dz.hirz.server.apiJson
import dz.hirz.server.auth.SourcePrincipal
import dz.hirz.server.badRequest
import dz.hirz.server.db.tx
import dz.hirz.server.ts
import dz.hirz.shared.api.CheckInReply
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestResult
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.Rejection
import dz.hirz.shared.rules.RuleConfig
import dz.hirz.shared.signal.AlarmKind
import dz.hirz.shared.signal.FixSource
import dz.hirz.shared.signal.Position
import dz.hirz.shared.signal.Signal
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** An accepted, stored signal as the rules engine and the live hub see it. */
data class IngestedSignal(
    val clientId: String,
    val sourceId: UUID,
    val assetId: UUID,
    val circleId: UUID,
    val signal: Signal,
    /** "ok", or "impossible_jump" (stored as history, never fed to boundary rules). */
    val quality: String,
    val checkInReply: CheckInReply?,
    /** False for backlog fixes older than the newest fix already known for this source. */
    val isLatestFix: Boolean,
)

fun interface IngestListener {
    fun onIngested(s: IngestedSignal)
}

private const val MAX_BATCH = 100
private const val MAX_FUTURE_MS = 5 * 60_000L

/** Accuracy used when a source sends none; the estimate is flagged on the stored row. */
private fun estimatedAccuracyM(source: FixSource?): Double = when (source) {
    FixSource.GNSS, FixSource.BLE_RELAY -> 30.0
    FixSource.WIFI -> 60.0
    FixSource.SMS -> 100.0
    FixSource.CELL -> 1000.0
    null -> 100.0
}

class IngestService(
    private val db: DataSource,
    private val clock: Clock,
    private val rules: RuleConfig,
    private val listeners: List<IngestListener>,
) {
    private data class Pins(val cancel: String?, val duress: String?)
    private val log = LoggerFactory.getLogger(IngestService::class.java)

    fun ingest(p: SourcePrincipal, batch: IngestBatch): IngestResult {
        if (batch.signals.isEmpty()) badRequest("empty_batch")
        if (batch.signals.size > MAX_BATCH) throw ApiException(HttpStatusCode.PayloadTooLarge, "batch_too_large")
        val now = clock.instant()
        // Fix-time order, so rules see the asset's story in the order it happened.
        val ordered = batch.signals.sortedBy { it.fixTimeMs ?: now.toEpochMilli() }
        val rejected = mutableListOf<Rejection>()
        val toEvaluate = mutableListOf<Pair<Long, IngestedSignal>>()
        var accepted = 0
        var duplicates = 0

        db.tx { c ->
            val pins = c.prepareStatement("SELECT cancel_pin_hash, duress_pin_hash FROM sources WHERE id = ?")
                .apply { setObject(1, p.sourceId) }.executeQuery().use { it.next(); Pins(it.getString(1), it.getString(2)) }
            for (s in ordered) {
                if (s.clientId.isBlank() || s.clientId.length > 64) { rejected += Rejection(s.clientId, "bad_client_id"); continue }
                if ((s.fixTimeMs ?: Long.MIN_VALUE) > now.toEpochMilli() + MAX_FUTURE_MS) {
                    rejected += Rejection(s.clientId, "future_fix"); continue
                }
                if (s.fixTimeMs != null && s.position == null) { rejected += Rejection(s.clientId, "fix_time_without_position"); continue }
                val stored = store(c, p, s, pins, now)
                if (stored.duplicate) duplicates++ else accepted++
                stored.pending?.let { toEvaluate += stored.positionId to it }
            }
        }
        // Alarms first, so nothing in front of an SOS can delay or block it; each listener isolated (P1 review #3).
        for ((positionId, signal) in toEvaluate.sortedBy { if (it.second.signal.alarms.isEmpty()) 1 else 0 }) {
            val ok = listeners.map { l -> runCatching { l.onIngested(signal) }.onFailure { log.error("listener failed for ${signal.clientId}", it) } }
                .all { it.isSuccess }
            if (ok) markEvaluated(positionId)
        }
        return IngestResult(accepted, duplicates, rejected, highRate(p.assetId))
    }

    private fun markEvaluated(positionId: Long) = db.connection.use { c ->
        c.prepareStatement("UPDATE positions SET evaluated_at = ? WHERE id = ?").apply { setTimestamp(1, clock.instant().ts()); setLong(2, positionId) }.executeUpdate()
    }

    /** A stored signal: [pending] is set when listeners still have to see it (new, or stored but never evaluated). */
    private class Stored(val positionId: Long, val duplicate: Boolean, val pending: IngestedSignal?)

    private fun store(c: Connection, p: SourcePrincipal, s: IngestSignal, pins: Pins, now: Instant): Stored {
        val alarms = s.alarms.toMutableSet()
        var reply = s.checkInReply
        s.pinHash?.let { typed ->
            when {
                pins.duress != null && same(typed, pins.duress) -> alarms += AlarmKind.DURESS
                pins.cancel != null && same(typed, pins.cancel) -> reply = CheckInReply.OK
            }
        }
        val fixTime = s.fixTimeMs?.let(Instant::ofEpochMilli)
        val quality = if (s.position != null && fixTime != null && isImpossibleJump(c, p.sourceId, s, fixTime)) "impossible_jump" else "ok"
        val accuracy = s.accuracyM ?: s.position?.let { estimatedAccuracyM(s.fixSource) }

        val inserted = c.prepareStatement(
            """INSERT INTO positions (source_id, asset_id, client_id, fix_time, server_time, geog, accuracy_m, accuracy_estimated,
                                      fix_source, speed_kmh, heading, battery, charging, alarms, quality, checkin_reply)
               VALUES (?, ?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE ST_GeogFromText(?) END, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (source_id, client_id) DO NOTHING RETURNING id""",
        ).apply {
            val wkt = s.position?.let { "SRID=4326;POINT(${it.lon} ${it.lat})" }
            setObject(1, p.sourceId); setObject(2, p.assetId); setString(3, s.clientId)
            setTimestamp(4, fixTime?.ts()); setTimestamp(5, now.ts()); setString(6, wkt); setString(7, wkt)
            setObject(8, accuracy); setBoolean(9, s.accuracyM == null && s.position != null)
            setString(10, s.fixSource?.let { apiJson.encodeToString(FixSource.serializer(), it).trim('"') })
            setObject(11, s.speedKmh); setObject(12, s.heading); setObject(13, s.battery); setObject(14, s.charging)
            setArray(15, c.createArrayOf("text", alarms.map { apiJson.encodeToString(AlarmKind.serializer(), it).trim('"') }.toTypedArray()))
            setString(16, quality)
            setString(17, reply?.let { apiJson.encodeToString(CheckInReply.serializer(), it).trim('"') })
        }.executeQuery().use { if (it.next()) it.getLong(1) else null }
        if (inserted == null) return duplicate(c, p, s, fixTime, alarms, reply)

        // last_fix_at only moves forward; the previous value tells whether this fix is backlog.
        val previousLastFix = c.prepareStatement(
            """UPDATE sources SET
                 last_heartbeat_at = CASE WHEN ? THEN GREATEST(COALESCE(last_heartbeat_at, ?), ?) ELSE last_heartbeat_at END,
                 last_fix_at = CASE WHEN ?::timestamptz IS NULL THEN last_fix_at ELSE GREATEST(COALESCE(last_fix_at, ?::timestamptz), ?::timestamptz) END
               WHERE id = ? RETURNING (SELECT last_fix_at FROM sources WHERE id = ?)""",
        ).apply {
            setBoolean(1, s.heartbeat); setTimestamp(2, now.ts()); setTimestamp(3, now.ts())
            setTimestamp(4, fixTime?.ts()); setTimestamp(5, fixTime?.ts()); setTimestamp(6, fixTime?.ts())
            setObject(7, p.sourceId); setObject(8, p.sourceId)
        }.executeQuery().use { it.next(); it.getTimestamp(1)?.toInstant() }

        val signal = Signal(
            sourceId = p.sourceId.toString(), assetId = p.assetId.toString(),
            fixTimeMs = s.fixTimeMs, serverTimeMs = now.toEpochMilli(),
            position = s.position?.let { Position(it.lat, it.lon, accuracy, s.accuracyM == null) },
            fixSource = s.fixSource, speedKmh = s.speedKmh, heading = s.heading, heartbeat = s.heartbeat,
            battery = s.battery, charging = s.charging, externalPower = null,
            ignition = null, motion = null, tilt = null, alarms = alarms,
        )
        val isLatest = fixTime != null && (previousLastFix == null || !fixTime.isBefore(previousLastFix))
        return Stored(inserted, duplicate = false, IngestedSignal(s.clientId, p.sourceId, p.assetId, p.circleId, signal, quality, reply, isLatest))
    }

    /** A resent signal: evaluated before → nothing to do; stored but never evaluated → evaluate it now. */
    private fun duplicate(c: Connection, p: SourcePrincipal, s: IngestSignal, fixTime: Instant?, alarms: Set<AlarmKind>, reply: CheckInReply?): Stored {
        data class Row(val id: Long, val quality: String, val evaluated: Boolean, val serverMs: Long, val accuracy: Double?, val estimated: Boolean)
        val row = c.prepareStatement(
            "SELECT id, quality, evaluated_at IS NOT NULL, server_time, accuracy_m, accuracy_estimated FROM positions WHERE source_id = ? AND client_id = ?",
        ).apply { setObject(1, p.sourceId); setString(2, s.clientId) }.executeQuery().use {
            it.next(); Row(it.getLong(1), it.getString(2), it.getBoolean(3), it.getTimestamp(4).time, it.getDouble(5).takeUnless { _ -> it.wasNull() }, it.getBoolean(6))
        }
        if (row.evaluated) return Stored(row.id, duplicate = true, pending = null)
        val lastFix = c.prepareStatement("SELECT last_fix_at FROM sources WHERE id = ?").apply { setObject(1, p.sourceId) }
            .executeQuery().use { it.next(); it.getTimestamp(1)?.toInstant() }
        val signal = Signal(
            sourceId = p.sourceId.toString(), assetId = p.assetId.toString(), fixTimeMs = s.fixTimeMs, serverTimeMs = row.serverMs,
            position = s.position?.let { Position(it.lat, it.lon, row.accuracy, row.estimated) }, fixSource = s.fixSource,
            speedKmh = s.speedKmh, heading = s.heading, heartbeat = s.heartbeat, battery = s.battery, charging = s.charging,
            externalPower = null, ignition = null, motion = null, tilt = null, alarms = alarms,
        )
        val isLatest = fixTime != null && (lastFix == null || !fixTime.isBefore(lastFix))
        return Stored(row.id, duplicate = true, IngestedSignal(s.clientId, p.sourceId, p.assetId, p.circleId, signal, row.quality, reply, isLatest))
    }

    /** Implied speed from the previous good fix (by fix time) above the configured limit. */
    private fun isImpossibleJump(c: Connection, sourceId: UUID, s: IngestSignal, fixTime: Instant): Boolean {
        val pos = s.position ?: return false
        val point = "SRID=4326;POINT(${pos.lon} ${pos.lat})"
        return c.prepareStatement(
            """SELECT ST_Distance(geog, ST_GeogFromText(?)), fix_time FROM positions
               WHERE source_id = ? AND quality = 'ok' AND geog IS NOT NULL AND fix_time <= ?
               ORDER BY fix_time DESC LIMIT 1""",
        ).apply { setString(1, point); setObject(2, sourceId); setTimestamp(3, fixTime.ts()) }.executeQuery().use { rs ->
            if (!rs.next()) return@use false
            val meters = rs.getDouble(1)
            val dtMs = fixTime.toEpochMilli() - rs.getTimestamp(2).time
            if (dtMs <= 0) return@use meters > 1_000
            meters / 1000.0 / (dtMs / 3_600_000.0) > rules.impossibleJumpKmh
        }
    }

    private fun highRate(assetId: UUID): Boolean = db.connection.use { c ->
        c.prepareStatement("SELECT EXISTS (SELECT 1 FROM incidents WHERE asset_id = ? AND state = 'open' AND tier IN ('P0', 'P1'))")
            .apply { setObject(1, assetId) }.executeQuery().use { it.next(); it.getBoolean(1) }
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
