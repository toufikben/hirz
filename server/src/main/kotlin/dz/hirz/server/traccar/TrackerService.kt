package dz.hirz.server.traccar

import dz.hirz.server.auth.SourcePrincipal
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.conflict
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.SmsCommandDto
import dz.hirz.shared.api.TrackerClaimDto
import dz.hirz.shared.api.TrackerClaimRequest
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

private val CLAIM_TTL: Duration = Duration.ofMinutes(30)
private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)

/** 15 digits with a valid Luhn check digit. */
fun isValidImei(raw: String): Boolean {
    if (raw.length != 15 || !raw.all(Char::isDigit)) return false
    val sum = raw.reversed().mapIndexed { i, ch ->
        val d = ch - '0'
        if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
    }.sum()
    return sum % 10 == 0
}

/**
 * Tracker claims (design §12.5.1, §12.5.8). Knowing an IMEI is not enough: a claim activates only when the device
 * reports after the claim was made, i.e. the claimant redirected it to Hirz. An IMEI active anywhere is refused
 * until its owner releases it, and the API never says which circle holds it.
 */
class TrackerService(
    private val db: DataSource,
    private val clock: Clock,
    private val circles: CircleService,
    private val admin: TraccarAdmin,
) {
    private val log = LoggerFactory.getLogger(TrackerService::class.java)

    /** The SMS a claim returns; Task 4's catalogue fills it. */
    var commands: (TrackerClaimRequest) -> List<SmsCommandDto> = { emptyList() }

    private fun circleOfAsset(c: Connection, assetId: UUID): UUID =
        c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }

    fun claim(assetId: UUID, memberId: UUID, req: TrackerClaimRequest): TrackerClaimDto {
        val imei = req.imei.trim()
        val now = clock.instant()
        db.tx { c ->
            circles.requireRole(c, circleOfAsset(c, assetId), memberId, EDITORS)
            if (!isValidImei(imei)) unprocessable("invalid_imei")
            if (req.intervalS !in 30..600) unprocessable("invalid_interval")
            closeExpired(c, now, imei)
            val taken = c.prepareStatement(
                """SELECT EXISTS (SELECT 1 FROM sources WHERE device_uid = ? AND revoked_at IS NULL)
                   OR EXISTS (SELECT 1 FROM tracker_claims WHERE imei = ? AND closed_at IS NULL)""",
            ).apply { setString(1, imei); setString(2, imei) }.executeQuery().use { it.next(); it.getBoolean(1) }
            if (taken) conflict("imei_in_use")
        }
        val rendered = commands(req)
        val id = UUID.randomUUID()
        val deviceId = admin.createDevice(imei, "hirz-" + id.toString().take(8))
        runCatching {
            db.tx { c ->
                c.prepareStatement(
                    """INSERT INTO tracker_claims (id, asset_id, imei, model, sim_phone, traccar_device_id, created_by, created_at, expires_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ).apply {
                    setObject(1, id); setObject(2, assetId); setString(3, imei); setString(4, req.model); setString(5, req.simPhone)
                    setLong(6, deviceId); setObject(7, memberId); setTimestamp(8, now.ts()); setTimestamp(9, now.plus(CLAIM_TTL).ts())
                }.executeUpdate()
                audit(c, circleOfAsset(c, assetId), memberId, "tracker_claimed", """{"claim":"$id","asset":"$assetId"}""")
            }
        }.onFailure { e ->
            // Two claims raced for one IMEI: the loser's Traccar device must not stay behind.
            runCatching { admin.deleteDevice(deviceId) }
            if (e is java.sql.SQLException && e.sqlState == "23505") conflict("imei_in_use")
            throw e
        }
        return TrackerClaimDto(id.toString(), "pending", now.plus(CLAIM_TTL).toEpochMilli(), commands = rendered)
    }

    fun claimStatus(claimId: UUID, memberId: UUID): TrackerClaimDto = db.connection.use { c ->
        val row = c.prepareStatement(
            "SELECT asset_id, expires_at, activated_at, closed_at, source_id FROM tracker_claims WHERE id = ?",
        ).apply { setObject(1, claimId) }.executeQuery().use {
            if (!it.next()) notFound()
            listOf(it.getObject(1, UUID::class.java), it.getTimestamp(2).toInstant(), it.getTimestamp(3), it.getTimestamp(4), it.getString(5))
        }
        circles.requireRole(c, circleOfAsset(c, row[0] as UUID), memberId)
        val expires = row[1] as Instant
        val state = when {
            row[2] != null -> "active"
            row[3] != null || !clock.instant().isBefore(expires) -> "expired"
            else -> "pending"
        }
        TrackerClaimDto(claimId.toString(), state, expires.toEpochMilli(), row[4] as String?)
    }

    /** The owner releases a tracker: the source stops, Traccar forgets the device, the IMEI is free again. */
    fun release(assetId: UUID, sourceId: UUID, memberId: UUID) {
        val deviceId = db.tx { c ->
            val circleId = circleOfAsset(c, assetId)
            circles.requireRole(c, circleId, memberId, setOf(Role.OWNER))
            val deviceId = c.prepareStatement(
                "UPDATE sources SET revoked_at = ? WHERE id = ? AND asset_id = ? AND kind = 'traccar' AND revoked_at IS NULL RETURNING traccar_device_id",
            ).apply { setTimestamp(1, clock.instant().ts()); setObject(2, sourceId); setObject(3, assetId) }
                .executeQuery().use { if (it.next()) it.getLong(1).takeIf { _ -> !it.wasNull() } else notFound() }
            audit(c, circleId, memberId, "tracker_released", """{"source":"$sourceId"}""")
            deviceId
        }
        deviceId?.let { id -> runCatching { admin.deleteDevice(id) }.onFailure { log.warn("Traccar device $id not deleted", it) } }
    }

    /** Closes pending claims past their 30 minutes and removes their Traccar devices. */
    fun expireClaims() {
        val devices = db.tx { c -> closeExpired(c, clock.instant(), null) }
        devices.forEach { id -> runCatching { admin.deleteDevice(id) }.onFailure { log.warn("Traccar device $id not deleted", it) } }
    }

    private fun closeExpired(c: Connection, now: Instant, imei: String?): List<Long> =
        c.prepareStatement(
            """UPDATE tracker_claims SET closed_at = ? WHERE closed_at IS NULL AND expires_at <= ? AND (?::text IS NULL OR imei = ?)
               RETURNING traccar_device_id""",
        ).apply { setTimestamp(1, now.ts()); setTimestamp(2, now.ts()); setString(3, imei); setString(4, imei) }
            .executeQuery().toList { it.getLong(1) }.filter { it > 0 }

    /**
     * The source a forwarded position belongs to, or null (dropped). A pending claim activates here when the report's
     * Traccar server time is after the claim and before its expiry.
     */
    fun sourceFor(imei: String, serverTime: String?): SourcePrincipal? {
        val reportedAt = serverTime?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: clock.instant()
        return db.tx { c ->
            c.prepareStatement(
                "SELECT s.id, s.asset_id, a.circle_id FROM sources s JOIN assets a ON a.id = s.asset_id WHERE s.device_uid = ? AND s.revoked_at IS NULL",
            ).apply { setString(1, imei) }.executeQuery().use {
                if (it.next()) return@tx SourcePrincipal(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getObject(3, UUID::class.java))
            }
            data class Claim(val id: UUID, val asset: UUID, val model: String, val sim: String?, val device: Long?, val by: UUID?)
            val claim = c.prepareStatement(
                """SELECT id, asset_id, model, sim_phone, traccar_device_id, created_by FROM tracker_claims
                   WHERE imei = ? AND closed_at IS NULL AND created_at < ? AND expires_at > ? FOR UPDATE""",
            ).apply { setString(1, imei); setTimestamp(2, reportedAt.ts()); setTimestamp(3, reportedAt.ts()) }.executeQuery().use {
                if (it.next()) Claim(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getString(3), it.getString(4),
                    it.getLong(5).takeIf { _ -> !it.wasNull() }, it.getObject(6, UUID::class.java)) else null
            } ?: return@tx null.also { log.info("Traccar report from an unclaimed device dropped") }
            val now = clock.instant()
            val sourceId = c.prepareStatement(
                """INSERT INTO sources (asset_id, kind, device_uid, model, sim_phone, traccar_device_id, adult, consent_at)
                   VALUES (?, 'traccar', ?, ?, ?, ?, false, NULL) RETURNING id""",
            ).apply { setObject(1, claim.asset); setString(2, imei); setString(3, claim.model); setString(4, claim.sim); setObject(5, claim.device) }
                .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            c.prepareStatement("UPDATE tracker_claims SET activated_at = ?, closed_at = ?, source_id = ? WHERE id = ?")
                .apply { setTimestamp(1, now.ts()); setTimestamp(2, now.ts()); setObject(3, sourceId); setObject(4, claim.id) }.executeUpdate()
            val circleId = circleOfAsset(c, claim.asset)
            audit(c, circleId, claim.by, "tracker_activated", """{"claim":"${claim.id}","source":"$sourceId"}""")
            SourcePrincipal(sourceId, claim.asset, circleId)
        }
    }
}
