package dz.hirz.server.custody

import dz.hirz.server.auth.Codes
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.gone
import dz.hirz.server.live.POSITION_COLUMNS
import dz.hirz.server.live.positionRow
import dz.hirz.server.assets.assetTypeOf
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.notify.AlertMessage
import dz.hirz.server.notify.Notifier
import dz.hirz.server.notify.PushTarget
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.AssetLiveDto
import dz.hirz.shared.api.ConsentDto
import dz.hirz.shared.api.ConsentRequest
import dz.hirz.shared.api.HandoverDto
import dz.hirz.shared.api.HandoverRequest
import dz.hirz.shared.api.LinkCodeDto
import dz.hirz.shared.api.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)
private val MAX_HANDOVER = Duration.ofDays(14)
private val ALGIERS = java.time.ZoneId.of("Africa/Algiers")

object Custody {
    /** True while a handover that mutes place exits covers [at]. Only PLACE_EXIT ever consults this. */
    fun muted(c: Connection, assetId: UUID, at: Instant): Boolean =
        c.prepareStatement("SELECT EXISTS (SELECT 1 FROM handovers WHERE asset_id = ? AND mute_place_exit AND starts_at <= ? AND ends_at > ?)")
            .apply { setObject(1, assetId); setTimestamp(2, at.ts()); setTimestamp(3, at.ts()) }.executeQuery().use { it.next(); it.getBoolean(1) }
}

/**
 * Custody (design §12.1): temporary handovers inside a circle, and consented shared custody between two circles.
 * Every grant and revocation reaches every member of both circles as a quiet in-app notice.
 */
class CustodyService(
    private val db: DataSource,
    private val clock: Clock,
    private val circles: CircleService,
    private val notifier: Notifier,
) {
    private fun circleOfAsset(c: Connection, assetId: UUID): UUID =
        c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }

    fun handover(assetId: UUID, actor: UUID, req: HandoverRequest): HandoverDto = db.tx { c ->
        val circleId = circleOfAsset(c, assetId)
        circles.requireRole(c, circleId, actor, EDITORS)
        val member = uuidOrNotFound(req.memberId)
        circles.requireRole(c, circleId, member, setOf(Role.OWNER, Role.GUARDIAN, Role.TEMPORARY, Role.VIEWER))
        val starts = Instant.ofEpochMilli(req.startsAtMs)
        val ends = Instant.ofEpochMilli(req.endsAtMs)
        if (!ends.isAfter(starts)) unprocessable("ends_before_start")
        if (Duration.between(starts, ends) > MAX_HANDOVER) unprocessable("handover_too_long")
        val id = c.prepareStatement(
            "INSERT INTO handovers (asset_id, member_id, starts_at, ends_at, mute_place_exit, created_by) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
        ).apply {
            setObject(1, assetId); setObject(2, member); setTimestamp(3, starts.ts()); setTimestamp(4, ends.ts()); setBoolean(5, req.mutePlaceExit); setObject(6, actor)
        }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        audit(c, circleId, actor, "handover_created", """{"asset":"$assetId","member":"$member","mutePlaceExit":${req.mutePlaceExit}}""")
        HandoverDto(id.toString(), assetId.toString(), member.toString(), req.startsAtMs, req.endsAtMs, req.mutePlaceExit)
    }

    fun endHandover(handoverId: UUID, actor: UUID) = db.tx { c ->
        val assetId = c.prepareStatement("SELECT asset_id FROM handovers WHERE id = ?").apply { setObject(1, handoverId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        val circleId = circleOfAsset(c, assetId)
        circles.requireRole(c, circleId, actor, EDITORS)
        val now = clock.instant()
        c.prepareStatement("UPDATE handovers SET ends_at = GREATEST(starts_at + interval '1 millisecond', LEAST(ends_at, ?)) WHERE id = ?")
            .apply { setTimestamp(1, now.ts()); setObject(2, handoverId) }.executeUpdate()
        audit(c, circleId, actor, "handover_ended", """{"handover":"$handoverId"}""")
    }

    fun linkCode(circleId: UUID, actor: UUID): LinkCodeDto = db.tx { c ->
        circles.requireRole(c, circleId, actor, setOf(Role.OWNER))
        val code = Codes.newCode()
        val expires = clock.instant().plus(Duration.ofMinutes(10))
        c.prepareStatement("INSERT INTO circle_link_codes (circle_id, code_hash, created_by, expires_at) VALUES (?, ?, ?, ?)")
            .apply { setObject(1, circleId); setString(2, Codes.hash(code)); setObject(3, actor); setTimestamp(4, expires.ts()) }.executeUpdate()
        LinkCodeDto(code, expires.toEpochMilli())
    }

    fun request(assetId: UUID, actor: UUID, req: ConsentRequest): ConsentDto = db.tx { c ->
        val from = circleOfAsset(c, assetId)
        circles.requireRole(c, from, actor, setOf(Role.OWNER))
        val now = clock.instant()
        val (codeId, to, expired, used) = c.prepareStatement(
            "SELECT id, circle_id, expires_at <= ?, used_at IS NOT NULL FROM circle_link_codes WHERE code_hash = ? FOR UPDATE",
        ).apply { setTimestamp(1, now.ts()); setString(2, Codes.hash(Codes.normalize(req.otherCircleCode))) }.executeQuery().use {
            if (!it.next()) notFound()
            listOf(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getBoolean(3), it.getBoolean(4))
        }
        if (used as Boolean) gone("link_code_used")
        if (expired as Boolean) gone("link_code_expired")
        if (to == from) unprocessable("same_circle")
        // With an institution (P9): only in its hours, and until the end of the school year at most a year ahead.
        val institution = c.prepareStatement("SELECT kind = 'institution' FROM circles WHERE id = ?").apply { setObject(1, to) }
            .executeQuery().use { it.next() && it.getBoolean(1) }
        val today = now.atZone(ALGIERS).toLocalDate()
        val expires = if (!institution) null else {
            if (req.windows.isEmpty()) unprocessable("windows_required")
            val last = req.expiresOn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() ?: unprocessable("bad_date") }
                ?: java.time.LocalDate.of(if (today.monthValue <= 6) today.year else today.year + 1, 6, 30)
            if (last.isBefore(today) || last.isAfter(today.plusDays(366))) unprocessable("bad_date")
            last.plusDays(1).atStartOfDay(ALGIERS).toInstant()
        }
        val windows = if (institution) req.windows else emptyList()
        c.prepareStatement("UPDATE circle_link_codes SET used_at = ? WHERE id = ?").apply { setTimestamp(1, now.ts()); setObject(2, codeId) }.executeUpdate()
        val id = c.prepareStatement(
            """INSERT INTO asset_consents (asset_id, from_circle, to_circle, requested_by, requested_at, scope, windows, expires_at)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id""",
        ).apply {
            setObject(1, assetId); setObject(2, from); setObject(3, to); setObject(4, actor); setTimestamp(5, now.ts())
            setString(6, if (institution) "institution" else "custody")
            setString(7, if (institution) kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(dz.hirz.shared.api.ActiveWindow.serializer()), windows) else null)
            setTimestamp(8, expires?.ts())
        }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        audit(c, from, actor, "custody_requested", """{"consent":"$id","asset":"$assetId","scope":"${if (institution) "institution" else "custody"}"}""")
        ConsentDto(id.toString(), assetId.toString(), from.toString(), to.toString(), "pending",
            if (institution) "institution" else "custody", windows, expires?.toEpochMilli())
    }

    /** Only the owner of the receiving circle accepts; then both circles see the asset. */
    fun accept(consentId: UUID, actor: UUID): ConsentDto {
        val dto = db.tx { c ->
            val row = load(c, consentId, forUpdate = true)
            circles.requireRole(c, UUID.fromString(row.toCircleId), actor, setOf(Role.OWNER))
            if (row.state != "pending") notFound()
            c.prepareStatement("UPDATE asset_consents SET accepted_by = ?, accepted_at = ? WHERE id = ?")
                .apply { setObject(1, actor); setTimestamp(2, clock.instant().ts()); setObject(3, consentId) }.executeUpdate()
            audit(c, UUID.fromString(row.toCircleId), actor, "custody_granted", """{"consent":"$consentId"}""")
            row.copy(state = "active")
        }
        notifyBoth(dto)
        return dto
    }

    /** Either circle's owner may revoke at any time. */
    fun revoke(consentId: UUID, actor: UUID) {
        val dto = db.tx { c ->
            val row = load(c, consentId, forUpdate = true)
            val allowed = listOf(row.fromCircleId, row.toCircleId).any { circleId ->
                runCatching { circles.requireRole(c, UUID.fromString(circleId), actor, setOf(Role.OWNER)) }.isSuccess
            }
            if (!allowed || row.state == "revoked") notFound()
            c.prepareStatement("UPDATE asset_consents SET revoked_by = ?, revoked_at = ? WHERE id = ?")
                .apply { setObject(1, actor); setTimestamp(2, clock.instant().ts()); setObject(3, consentId) }.executeUpdate()
            audit(c, UUID.fromString(row.fromCircleId), actor, "custody_revoked", """{"consent":"$consentId"}""")
            row.copy(state = "revoked")
        }
        notifyBoth(dto)
    }

    /** Assets another circle shares with [circleId] under an active consent, with their last good fix. */
    fun sharedAssets(circleId: UUID, actor: UUID): List<AssetLiveDto> = db.connection.use { c ->
        circles.requireRole(c, circleId, actor)
        data class Row(val id: UUID, val circle: String, val name: String, val type: String, val heartbeat: Long?)
        c.prepareStatement(
            """SELECT a.id, a.circle_id, a.name, a.type, (SELECT max(s.last_heartbeat_at) FROM sources s WHERE s.asset_id = a.id AND s.revoked_at IS NULL)
               FROM asset_consents k JOIN assets a ON a.id = k.asset_id
               WHERE k.to_circle = ? AND k.accepted_at IS NOT NULL AND k.revoked_at IS NULL AND consent_live(k.windows, k.expires_at, ?) ORDER BY a.name""",
        ).apply { setObject(1, circleId); setTimestamp(2, clock.instant().ts()) }.executeQuery()
            .toList { Row(it.getObject(1, UUID::class.java), it.getString(2), it.getString(3), it.getString(4), it.getTimestamp(5)?.time) }
            .map { r ->
                val last = c.prepareStatement(
                    "SELECT $POSITION_COLUMNS FROM positions p WHERE p.asset_id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL ORDER BY p.fix_time DESC NULLS LAST LIMIT 1",
                ).apply { setObject(1, r.id) }.executeQuery().use { if (it.next()) positionRow(it) else null }
                AssetLiveDto(r.id.toString(), r.circle, r.name, assetTypeOf(r.type), last, r.heartbeat)
            }
    }

    private fun load(c: Connection, consentId: UUID, forUpdate: Boolean): ConsentDto =
        c.prepareStatement(
            "SELECT asset_id, from_circle, to_circle, accepted_at IS NOT NULL, revoked_at IS NOT NULL, scope, windows, expires_at FROM asset_consents WHERE id = ?${if (forUpdate) " FOR UPDATE" else ""}",
        ).apply { setObject(1, consentId) }.executeQuery().use {
            if (!it.next()) notFound()
            val state = when { it.getBoolean(5) -> "revoked"; it.getBoolean(4) -> "active"; else -> "pending" }
            val windows = it.getString(7)?.let { w -> kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(dz.hirz.shared.api.ActiveWindow.serializer()), w) }.orEmpty()
            ConsentDto(consentId.toString(), it.getString(1), it.getString(2), it.getString(3), state, it.getString(6), windows, it.getTimestamp(8)?.time)
        }

    /** A quiet P2 notice to every member of both circles; it carries the asset name only. */
    private fun notifyBoth(dto: ConsentDto) {
        data class Target(val member: UUID, val token: String)
        val (assetName, targets) = db.connection.use { c ->
            val name = c.prepareStatement("SELECT name FROM assets WHERE id = ?").apply { setObject(1, UUID.fromString(dto.assetId)) }
                .executeQuery().use { it.next(); it.getString(1) }
            name to c.prepareStatement(
                """SELECT DISTINCT pt.member_id, pt.token FROM circle_members cm JOIN push_tokens pt ON pt.member_id = cm.member_id
                   WHERE cm.circle_id IN (?, ?) AND cm.role IN ('owner', 'guardian', 'viewer', 'temporary')
                     AND (cm.expires_at IS NULL OR cm.expires_at > ?)""",
            ).apply { setObject(1, UUID.fromString(dto.fromCircleId)); setObject(2, UUID.fromString(dto.toCircleId)); setTimestamp(3, clock.instant().ts()) }
                .executeQuery().toList { Target(it.getObject(1, UUID::class.java), it.getString(2)) }
        }
        runBlocking(Dispatchers.IO) {
            targets.map { t ->
                async { runCatching { withTimeout(10_000) { notifier.send(PushTarget(t.member, t.token), AlertMessage("custody_changed", null, "P2", assetName, null)) } } }
            }.awaitAll()
        }
    }
}

fun Route.custodyRoutes(service: CustodyService) {
    post("/v1/assets/{id}/handovers") {
        call.respond(service.handover(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<HandoverRequest>()))
    }
    delete("/v1/handovers/{id}") {
        service.endHandover(uuidOrNotFound(call.parameters["id"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/circles/{id}/link-code") {
        call.respond(service.linkCode(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    post("/v1/assets/{id}/consents") {
        call.respond(service.request(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<ConsentRequest>()))
    }
    post("/v1/consents/{id}/accept") {
        call.respond(service.accept(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    delete("/v1/consents/{id}") {
        service.revoke(uuidOrNotFound(call.parameters["id"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/v1/circles/{id}/shared-assets") {
        call.respond(service.sharedAssets(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
}
