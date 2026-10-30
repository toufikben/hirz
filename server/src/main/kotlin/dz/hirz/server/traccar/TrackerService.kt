package dz.hirz.server.traccar

import dz.hirz.server.ApiException
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
import dz.hirz.shared.devices.DeviceCatalog
import dz.hirz.shared.devices.DeviceModel
import dz.hirz.shared.devices.SupportLevel
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

private val CLAIM_TTL: Duration = Duration.ofMinutes(30)
/** Fixes needed at the challenge interval, and the tolerance on each gap. */
private const val PROOF_REPORTS = 3
private const val PROOF_TOLERANCE = 0.3
/** Claims one member may start per day: an IMEI cannot be squatted by repeated claims. */
private const val CLAIMS_PER_DAY = 5
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
    /** Public host the setup SMS points trackers at (HIRZ_DEVICE_HOST). */
    private val deviceHost: String = "localhost",
    /** Range the proof interval is drawn from, seconds (HIRZ_CLAIM_PROOF_S, e.g. "45-95"; CI uses a short range). */
    private val proofRangeS: IntRange = 45..95,
) {
    private val random = java.security.SecureRandom()
    private val log = LoggerFactory.getLogger(TrackerService::class.java)

    /**
     * The setup SMS for this claim, from the catalogue. The new device password is generated here, shown once in the
     * password command, and never stored: only the owner keeps it.
     */
    private fun commands(model: DeviceModel, req: TrackerClaimRequest, intervalS: Int): List<SmsCommandDto> {
        val needed = model.commands.filter { it.purpose != "sos_number" || req.sosPhone != null }
        if (needed.any { "{apn}" in it.template } && req.apn.isNullOrBlank()) unprocessable("apn_required")
        val values = buildMap {
            put("host", deviceHost); put("port", model.port.toString()); put("seconds", intervalS.toString())
            put("password", req.devicePassword?.trim()?.takeIf { it.isNotEmpty() } ?: model.defaultPassword.orEmpty())
            put("newPassword", (0 until 6).joinToString("") { random.nextInt(10).toString() })
            req.apn?.trim()?.let { put("apn", it) }
            req.sosPhone?.trim()?.let { put("phone", it) }
        }
        return DeviceCatalog.render(model.copy(commands = needed), values, req.simPhone?.trim())
    }

    private fun circleOfAsset(c: Connection, assetId: UUID): UUID =
        c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }

    fun claim(assetId: UUID, memberId: UUID, req: TrackerClaimRequest): TrackerClaimDto {
        val imei = req.imei.trim()
        val now = clock.instant()
        val id = UUID.randomUUID()
        val interval = proofRangeS.first + random.nextInt(proofRangeS.last - proofRangeS.first + 1)
        val model = DeviceCatalog.byId(req.model)
        // One claim per IMEI at a time, decided under a per-IMEI lock, recorded before Traccar is touched (review I1).
        val stale = db.tx { c ->
            circles.requireRole(c, circleOfAsset(c, assetId), memberId, EDITORS)
            if (!isValidImei(imei)) unprocessable("invalid_imei")
            model ?: notFound()
            if (model.level == SupportLevel.REJECTED) conflict("model_rejected")
            val recent = c.prepareStatement("SELECT count(*) FROM tracker_claims WHERE created_by = ? AND created_at > ?")
                .apply { setObject(1, memberId); setTimestamp(2, now.minus(Duration.ofDays(1)).ts()) }.executeQuery().use { it.next(); it.getInt(1) }
            if (recent >= CLAIMS_PER_DAY) throw ApiException(HttpStatusCode.TooManyRequests, "too_many_claims")
            c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, "imei:$imei") }.executeQuery().close()
            val stale = closeExpired(c, now, imei)
            val taken = c.prepareStatement(
                """SELECT EXISTS (SELECT 1 FROM sources WHERE device_uid = ? AND revoked_at IS NULL)
                   OR EXISTS (SELECT 1 FROM tracker_claims WHERE imei = ? AND closed_at IS NULL)""",
            ).apply { setString(1, imei); setString(2, imei) }.executeQuery().use { it.next(); it.getBoolean(1) }
            if (taken) conflict("imei_in_use")
            c.prepareStatement(
                """INSERT INTO tracker_claims (id, asset_id, imei, model, sim_phone, created_by, created_at, expires_at, proof_interval_s)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            ).apply {
                setObject(1, id); setObject(2, assetId); setString(3, imei); setString(4, req.model); setString(5, req.simPhone)
                setObject(6, memberId); setTimestamp(7, now.ts()); setTimestamp(8, now.plus(CLAIM_TTL).ts()); setInt(9, interval)
            }.executeUpdate()
            audit(c, circleOfAsset(c, assetId), memberId, "tracker_claimed", """{"claim":"$id","asset":"$assetId"}""")
            stale
        }
        stale.forEach { d -> runCatching { admin.deleteDevice(d) } }
        val rendered = runCatching { commands(model!!, req, interval) }.onFailure { dropClaim(id) }.getOrThrow()
        val deviceId = try {
            admin.createDevice(imei, "hirz-" + id.toString().take(8))
        } catch (e: TraccarUnavailable) {
            dropClaim(id)
            throw ApiException(HttpStatusCode.ServiceUnavailable, "device_gateway_unavailable")
        }
        db.connection.use { c ->
            c.prepareStatement("UPDATE tracker_claims SET traccar_device_id = ? WHERE id = ?").apply { setLong(1, deviceId); setObject(2, id) }.executeUpdate()
        }
        return TrackerClaimDto(id.toString(), "pending", now.plus(CLAIM_TTL).toEpochMilli(), commands = rendered, proofIntervalS = interval)
    }

    /** A claim that never reached Traccar leaves nothing behind, so the owner can retry at once. */
    private fun dropClaim(id: UUID) = db.connection.use { c ->
        c.prepareStatement("DELETE FROM tracker_claims WHERE id = ? AND activated_at IS NULL").apply { setObject(1, id) }.executeUpdate()
    }

    fun claimStatus(claimId: UUID, memberId: UUID): TrackerClaimDto = db.connection.use { c ->
        val row = c.prepareStatement(
            "SELECT asset_id, expires_at, activated_at, closed_at, source_id, proof_interval_s FROM tracker_claims WHERE id = ?",
        ).apply { setObject(1, claimId) }.executeQuery().use {
            if (!it.next()) notFound()
            listOf(it.getObject(1, UUID::class.java), it.getTimestamp(2).toInstant(), it.getTimestamp(3), it.getTimestamp(4), it.getString(5), it.getInt(6))
        }
        circles.requireRole(c, circleOfAsset(c, row[0] as UUID), memberId)
        val expires = row[1] as Instant
        val state = when {
            row[2] != null -> "active"
            row[3] != null || !clock.instant().isBefore(expires) -> "expired"
            else -> "pending"
        }
        TrackerClaimDto(claimId.toString(), state, expires.toEpochMilli(), row[4] as String?, proofIntervalS = row[5] as Int)
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
     * The source a forwarded report belongs to, or null (dropped). A pending claim records each report with a fix that
     * arrives inside its window, and activates when the last [PROOF_REPORTS] arrive at the claim's challenge interval
     * (±30 %). A device that was already reporting to Hirz keeps its old interval, so it cannot activate a stranger's
     * claim; only someone who sent it the setup SMS can (P3 review C1). Traccar's server time is the clock.
     */
    fun sourceFor(imei: String, serverTime: String?, hasFix: Boolean = true): SourcePrincipal? {
        val reportedAt = serverTime?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: clock.instant()
        return db.tx { c ->
            active(c, imei)?.let { return@tx it }
            data class Claim(val id: UUID, val asset: UUID, val model: String, val sim: String?, val device: Long?, val by: UUID?, val interval: Int, val times: List<Instant>)
            val claim = c.prepareStatement(
                """SELECT id, asset_id, model, sim_phone, traccar_device_id, created_by, proof_interval_s, proof_times FROM tracker_claims
                   WHERE imei = ? AND closed_at IS NULL AND created_at < ? AND expires_at > ? FOR UPDATE""",
            ).apply { setString(1, imei); setTimestamp(2, reportedAt.ts()); setTimestamp(3, reportedAt.ts()) }.executeQuery().use {
                if (it.next()) Claim(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getString(3), it.getString(4),
                    it.getLong(5).takeIf { _ -> !it.wasNull() }, it.getObject(6, UUID::class.java), it.getInt(7),
                    (it.getArray(8).array as Array<*>).map { t -> (t as java.sql.Timestamp).toInstant() }) else null
            }
            if (claim == null) {
                // A concurrent forward of the same report may have just activated the claim (review minor).
                return@tx active(c, imei) ?: null.also { log.info("Traccar report from an unclaimed device dropped") }
            }
            if (!hasFix) return@tx null
            val times = (claim.times + reportedAt).distinct().sorted().takeLast(PROOF_REPORTS)
            c.prepareStatement("UPDATE tracker_claims SET proof_times = ? WHERE id = ?")
                .apply { setArray(1, c.createArrayOf("timestamptz", times.map { it.ts() }.toTypedArray())); setObject(2, claim.id) }.executeUpdate()
            val proven = times.size == PROOF_REPORTS && times.zipWithNext().all { (x, y) ->
                val gapS = Duration.between(x, y).toMillis() / 1000.0
                gapS >= claim.interval * (1 - PROOF_TOLERANCE) && gapS <= claim.interval * (1 + PROOF_TOLERANCE)
            }
            if (!proven) return@tx null
            val now = clock.instant()
            val sourceId = c.prepareStatement(
                """INSERT INTO sources (asset_id, kind, device_uid, model, sim_phone, traccar_device_id, adult, consent_at, created_at)
                   VALUES (?, 'traccar', ?, ?, ?, ?, false, NULL, ?) RETURNING id""",
            ).apply {
                setObject(1, claim.asset); setString(2, imei); setString(3, claim.model); setString(4, claim.sim); setObject(5, claim.device)
                setTimestamp(6, now.ts())
            }
                .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            c.prepareStatement("UPDATE tracker_claims SET activated_at = ?, closed_at = ?, source_id = ? WHERE id = ?")
                .apply { setTimestamp(1, now.ts()); setTimestamp(2, now.ts()); setObject(3, sourceId); setObject(4, claim.id) }.executeUpdate()
            val circleId = circleOfAsset(c, claim.asset)
            audit(c, circleId, claim.by, "tracker_activated", """{"claim":"${claim.id}","source":"$sourceId"}""")
            SourcePrincipal(sourceId, claim.asset, circleId)
        }
    }

    private fun active(c: Connection, imei: String): SourcePrincipal? =
        c.prepareStatement(
            "SELECT s.id, s.asset_id, a.circle_id FROM sources s JOIN assets a ON a.id = s.asset_id WHERE s.device_uid = ? AND s.revoked_at IS NULL",
        ).apply { setString(1, imei) }.executeQuery().use {
            if (it.next()) SourcePrincipal(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getObject(3, UUID::class.java)) else null
        }
}
