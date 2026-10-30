package dz.hirz.server.assets

import dz.hirz.server.auth.Codes
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.gone
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.CreateAssetRequest
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.PairRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PairingCodeDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.SourceDto
import dz.hirz.shared.asset.AssetType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)

/** Asset types whose carrier is an adult by definition; their tracking always needs the carrier's consent (design §8.5). */
private val ADULT_TYPES = setOf(AssetType.ELDER, AssetType.LONE_WORKER)

private val PIN = Regex("^[0-9]{4,8}$")

fun AssetType.wire(): String = Json.encodeToString(AssetType.serializer(), this).trim('"')
fun assetTypeOf(wire: String): AssetType = Json.decodeFromString(AssetType.serializer(), "\"$wire\"")

class AssetService(private val db: DataSource, private val clock: Clock, private val circles: CircleService) {

    /** Circle of [assetId] after checking the caller holds one of [roles] there; 404 otherwise. */
    fun requireAssetRole(c: Connection, assetId: UUID, memberId: UUID, roles: Set<Role> = Role.entries.toSet()): UUID {
        val circleId = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        circles.requireRole(c, circleId, memberId, roles)
        return circleId
    }

    fun create(circleId: UUID, memberId: UUID, req: CreateAssetRequest): AssetDto = db.tx { c ->
        circles.requireRole(c, circleId, memberId, EDITORS)
        require(req.name.isNotBlank() && req.name.length <= 80) { "name must be 1–80 characters" }
        val descriptors = buildJsonObject { req.descriptors.forEach { (k, v) -> put(k, v) } }.toString()
        val id = c.prepareStatement("INSERT INTO assets (circle_id, type, name, descriptors) VALUES (?, ?, ?, ?::jsonb) RETURNING id")
            .apply { setObject(1, circleId); setString(2, req.type.wire()); setString(3, req.name.trim()); setString(4, descriptors) }
            .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        audit(c, circleId, memberId, "asset_created", """{"asset":"$id"}""")
        AssetDto(id.toString(), circleId.toString(), req.type, req.name.trim(), "active")
    }

    fun list(circleId: UUID, memberId: UUID): List<AssetDto> = db.connection.use { c ->
        circles.requireRole(c, circleId, memberId)
        val sources = c.prepareStatement(
            """SELECT s.id, s.asset_id, s.kind, s.adult, s.consent_at, s.last_heartbeat_at, s.last_fix_at, s.revoked_at
               FROM sources s JOIN assets a ON a.id = s.asset_id WHERE a.circle_id = ? ORDER BY s.created_at""",
        ).apply { setObject(1, circleId) }.executeQuery().toList {
            it.getString(2) to SourceDto(
                id = it.getString(1), kind = it.getString(3), adult = it.getBoolean(4),
                consentAtMs = it.getTimestamp(5)?.time, lastHeartbeatAtMs = it.getTimestamp(6)?.time,
                lastFixAtMs = it.getTimestamp(7)?.time, revoked = it.getTimestamp(8) != null,
            )
        }.groupBy({ it.first }, { it.second })
        c.prepareStatement("SELECT id, type, name, lifecycle FROM assets WHERE circle_id = ? ORDER BY created_at")
            .apply { setObject(1, circleId) }.executeQuery().toList {
                AssetDto(it.getString(1), circleId.toString(), assetTypeOf(it.getString(2)), it.getString(3), it.getString(4),
                    sources[it.getString(1)].orEmpty())
            }
    }

    fun createPairingCode(assetId: UUID, memberId: UUID, req: CreatePairingRequest): PairingCodeDto = db.tx { c ->
        val circleId = requireAssetRole(c, assetId, memberId, EDITORS)
        listOfNotNull(req.cancelPin, req.duressPin).forEach { if (!PIN.matches(it)) unprocessable("pin_must_be_4_to_8_digits") }
        if (req.cancelPin != null && req.cancelPin == req.duressPin) unprocessable("pins_must_differ")
        val salt = if (req.cancelPin != null || req.duressPin != null) Codes.newSalt() else null
        val code = Codes.newCode()
        val expiresAt = clock.instant().plus(Duration.ofMinutes(10))
        c.prepareStatement(
            """INSERT INTO pairing_codes (asset_id, code_hash, pin_salt, cancel_pin_hash, duress_pin_hash, created_by, expires_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
        ).apply {
            setObject(1, assetId); setString(2, Codes.hash(code)); setString(3, salt)
            setString(4, req.cancelPin?.let { Codes.pinHash(salt!!, it) })
            setString(5, req.duressPin?.let { Codes.pinHash(salt!!, it) })
            setObject(6, memberId); setTimestamp(7, expiresAt.ts())
        }.executeUpdate()
        audit(c, circleId, memberId, "pairing_code_created", """{"asset":"$assetId"}""")
        PairingCodeDto(code, "hirz://pair/$code", expiresAt.toEpochMilli())
    }

    /** Unauthenticated: the one-time code is the credential. The code is checked here, never on the device (design §6.5). */
    fun pair(req: PairRequest): PairResponse = db.tx { c ->
        val now = clock.instant()
        data class Code(val id: UUID, val assetId: UUID, val salt: String?, val cancel: String?, val duress: String?, val expired: Boolean, val used: Boolean)
        val code = c.prepareStatement(
            """SELECT id, asset_id, pin_salt, cancel_pin_hash, duress_pin_hash, expires_at <= ?, used_at IS NOT NULL
               FROM pairing_codes WHERE code_hash = ? FOR UPDATE""",
        ).apply { setTimestamp(1, now.ts()); setString(2, Codes.hash(Codes.normalize(req.code))) }.executeQuery().use {
            if (!it.next()) notFound()
            Code(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getString(3), it.getString(4),
                it.getString(5), it.getBoolean(6), it.getBoolean(7))
        }
        if (code.used) gone("pairing_code_used")
        if (code.expired) gone("pairing_code_expired")
        val (circleId, type, assetName, circleName) = c.prepareStatement(
            "SELECT a.circle_id, a.type, a.name, ci.name FROM assets a JOIN circles ci ON ci.id = a.circle_id WHERE a.id = ?",
        ).apply { setObject(1, code.assetId) }.executeQuery().use {
            it.next(); listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }
        val adult = req.carrierIsAdult || assetTypeOf(type) in ADULT_TYPES
        if (adult && !req.consentGiven) unprocessable("consent_required")
        val token = Codes.newSourceToken()
        val deviceInfo = buildJsonObject {
            put("model", req.deviceModel); put("os", req.osVersion); put("app", req.appVersion)
        }.toString()
        val sourceId = c.prepareStatement(
            """INSERT INTO sources (asset_id, kind, token_hash, push_token, device_info, adult, consent_at, pin_salt, cancel_pin_hash, duress_pin_hash)
               VALUES (?, 'companion', ?, ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING id""",
        ).apply {
            setObject(1, code.assetId); setString(2, Codes.hash(token)); setString(3, req.pushToken); setString(4, deviceInfo)
            setBoolean(5, adult); setTimestamp(6, if (req.consentGiven) now.ts() else null)
            setString(7, code.salt); setString(8, code.cancel); setString(9, code.duress)
        }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        c.prepareStatement("UPDATE pairing_codes SET used_at = ? WHERE id = ?").apply { setTimestamp(1, now.ts()); setObject(2, code.id) }.executeUpdate()
        c.prepareStatement("INSERT INTO device_ownership_proofs (source_id, method, proved_at) VALUES (?, 'qr_pairing', ?)")
            .apply { setObject(1, sourceId); setTimestamp(2, now.ts()) }.executeUpdate()
        audit(c, UUID.fromString(circleId), null, "source_paired", """{"asset":"${code.assetId}","source":"$sourceId","adult":$adult}""")
        PairResponse(sourceId.toString(), token, assetName, circleName, code.salt)
    }

    fun revokeSource(assetId: UUID, sourceId: UUID, memberId: UUID) = db.tx { c ->
        val circleId = requireAssetRole(c, assetId, memberId, EDITORS)
        val updated = c.prepareStatement("UPDATE sources SET revoked_at = ? WHERE id = ? AND asset_id = ? AND revoked_at IS NULL")
            .apply { setTimestamp(1, clock.instant().ts()); setObject(2, sourceId); setObject(3, assetId) }.executeUpdate()
        if (updated == 0) notFound()
        audit(c, circleId, memberId, "source_revoked", """{"source":"$sourceId"}""")
    }

    fun consent(sourceId: UUID) = db.tx { c ->
        val circleId = c.prepareStatement(
            "UPDATE sources s SET adult = true, consent_at = ?, paused_reason = NULL FROM assets a WHERE s.id = ? AND a.id = s.asset_id RETURNING a.circle_id",
        ).apply { setTimestamp(1, clock.instant().ts()); setObject(2, sourceId) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        audit(c, circleId, null, "source_consent_given", """{"source":"$sourceId"}""")
    }

    fun setSourcePushToken(sourceId: UUID, token: String) {
        require(token.isNotBlank() && token.length <= 4096) { "invalid push token" }
        db.connection.use { c ->
            c.prepareStatement("UPDATE sources SET push_token = ? WHERE id = ?").apply { setString(1, token); setObject(2, sourceId) }.executeUpdate()
        }
    }
}
