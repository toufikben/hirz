package dz.hirz.server.incidents

import dz.hirz.server.apiJson
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.conflict
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.notFound
import dz.hirz.server.notify.AlertMessage
import dz.hirz.server.notify.Notifier
import dz.hirz.server.notify.PushTarget
import dz.hirz.server.notify.SendResult
import dz.hirz.server.rules.Finding
import dz.hirz.server.rules.IncidentSink
import dz.hirz.server.ts
import dz.hirz.shared.api.CheckInSentDto
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.DeliveryDto
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IncidentEventDto
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.ReceiptState
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/** Observes incident changes (the live hub). */
fun interface IncidentEvents {
    fun changed(circleId: UUID, incidentId: UUID)
}

private const val SEND_TIMEOUT_MS = 10_000L
private val RECIPIENT_ROLES = listOf("owner", "guardian", "viewer", "temporary")
private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)
private val reasonsSerializer = ListSerializer(Reason.serializer())

/**
 * Turns findings into incidents (one open incident per asset and rule) and alerts every member in parallel
 * (design §5). Pushes are attempted for every token at once; a failing token never delays the others, and a
 * member without a token is recorded, not silently skipped.
 */
class IncidentService(
    private val db: DataSource,
    private val clock: Clock,
    private val notifier: Notifier,
    private val circles: CircleService,
) : IncidentSink {
    private val listeners = CopyOnWriteArrayList<IncidentEvents>()

    /** The escalation ladder (P2); null in tests that exercise incidents alone. */
    var escalation: dz.hirz.server.escalation.EscalationEngine? = null

    fun subscribe(l: IncidentEvents) { listeners += l }

    override fun open(finding: Finding, findingId: Long) {
        val now = clock.instant()
        val (incidentId, isNew) = db.tx { c ->
            val inserted = c.prepareStatement(
                """INSERT INTO incidents (asset_id, circle_id, rule, tier, silent, reasons, opened_at)
                   VALUES (?, ?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (asset_id, rule) WHERE state = 'open' DO NOTHING RETURNING id""",
            ).apply {
                setObject(1, finding.assetId); setObject(2, finding.circleId); setString(3, finding.rule.name)
                setString(4, finding.tier.name); setBoolean(5, finding.silent)
                setString(6, apiJson.encodeToString(reasonsSerializer, finding.reasons)); setTimestamp(7, finding.at.ts())
            }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
            val id = inserted ?: c.prepareStatement("SELECT id FROM incidents WHERE asset_id = ? AND rule = ? AND state = 'open'")
                .apply { setObject(1, finding.assetId); setString(2, finding.rule.name) }.executeQuery()
                .use { if (it.next()) it.getObject(1, UUID::class.java) else null }
                ?: throw IllegalStateException("incident closed concurrently; the finding is retried by the next evaluation")
            c.prepareStatement("UPDATE findings SET incident_id = ? WHERE id = ?").apply { setObject(1, id); setLong(2, findingId) }.executeUpdate()
            event(c, id, if (inserted != null) "opened" else "finding", null,
                apiJson.encodeToString(reasonsSerializer, finding.reasons).let { """{"reasons":$it}""" }, now)
            id to (inserted != null)
        }
        if (isNew && finding.tier != Tier.P2) {
            fanOut(incidentId, finding)
            escalation?.onOpened(incidentId, finding.circleId, finding.tier)
        }
        notifyChanged(finding.circleId, incidentId)
    }

    override fun reasonAdded(incidentId: UUID) {
        val circleId = db.connection.use { c ->
            c.prepareStatement("SELECT circle_id FROM incidents WHERE id = ?").apply { setObject(1, incidentId) }
                .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
        } ?: return
        notifyChanged(circleId, incidentId)
    }

    private fun fanOut(incidentId: UUID, f: Finding) {
        data class Recipient(val member: UUID, val token: String?)
        val now = clock.instant()
        val (assetName, recipients) = db.connection.use { c ->
            val name = c.prepareStatement("SELECT name FROM assets WHERE id = ?").apply { setObject(1, f.assetId) }
                .executeQuery().use { it.next(); it.getString(1) }
            val list = c.prepareStatement(
                // A token also registered by one of the asset's own sources is the carrier's phone: never alert it.
                """SELECT cm.member_id, pt.token FROM circle_members cm
                   LEFT JOIN push_tokens pt ON pt.member_id = cm.member_id
                     AND pt.token NOT IN (SELECT push_token FROM sources WHERE asset_id = ? AND push_token IS NOT NULL)
                   WHERE cm.circle_id = ? AND cm.role = ANY(?) AND (cm.expires_at IS NULL OR cm.expires_at > ?)
                   ORDER BY cm.priority""",
            ).apply {
                setObject(1, f.assetId); setObject(2, f.circleId)
                setArray(3, c.createArrayOf("text", RECIPIENT_ROLES.toTypedArray())); setTimestamp(4, now.ts())
            }.executeQuery().toList { Recipient(it.getObject(1, UUID::class.java), it.getString(2)) }
            name to list
        }
        val message = AlertMessage("incident", incidentId, f.tier.name, assetName, f.rule.name)
        val results = runBlocking(Dispatchers.IO) {
            coroutineScope {
                recipients.map { r ->
                    async { r to r.token?.let { safeSend(PushTarget(r.member, it), message) } }
                }.awaitAll()
            }
        }
        db.tx { c ->
            for ((r, result) in results) {
                val sentAt = if (result is SendResult.Ok) clock.instant() else null
                val error = when (result) {
                    null -> "no_push_token"
                    is SendResult.Failed -> result.error
                    is SendResult.Ok -> null
                }
                c.prepareStatement(
                    "INSERT INTO deliveries (incident_id, member_id, channel, token, provider_id, sent_at, error) VALUES (?, ?, 'fcm', ?, ?, ?, ?)",
                ).apply {
                    setObject(1, incidentId); setObject(2, r.member); setString(3, r.token)
                    setString(4, (result as? SendResult.Ok)?.providerId); setTimestamp(5, sentAt?.ts()); setString(6, error)
                }.executeUpdate()
                if (result is SendResult.Failed && result.tokenInvalid) {
                    c.prepareStatement("DELETE FROM push_tokens WHERE token = ?").apply { setString(1, r.token) }.executeUpdate()
                }
            }
        }
    }

    /** One send can neither throw into nor stall the others (P1 review #4). */
    private suspend fun safeSend(to: PushTarget, message: AlertMessage): SendResult =
        runCatching { withTimeout(SEND_TIMEOUT_MS) { notifier.send(to, message) } }
            .getOrElse { SendResult.Failed(it.message ?: it::class.simpleName ?: "send_error", tokenInvalid = false) }

    // ---- member-facing operations -------------------------------------------------------------------------------

    fun list(circleId: UUID, memberId: UUID, openOnly: Boolean): List<IncidentDto> = db.connection.use { c ->
        circles.requireRole(c, circleId, memberId)
        c.prepareStatement(
            "SELECT id FROM incidents WHERE circle_id = ? ${if (openOnly) "AND state = 'open'" else ""} ORDER BY opened_at DESC LIMIT 200",
        ).apply { setObject(1, circleId) }.executeQuery().toList { it.getObject(1, UUID::class.java) }.map { load(c, it) }
    }

    fun get(incidentId: UUID, memberId: UUID): IncidentDto = db.connection.use { c ->
        circles.requireRole(c, circleOf(c, incidentId), memberId)
        load(c, incidentId)
    }

    fun receipt(incidentId: UUID, memberId: UUID, state: ReceiptState) {
        val now = clock.instant()
        db.tx { c ->
            circles.requireRole(c, circleOf(c, incidentId), memberId)
            val column = if (state == ReceiptState.DELIVERED) "delivered_at" else "opened_at"
            c.prepareStatement("UPDATE deliveries SET $column = COALESCE($column, ?) WHERE incident_id = ? AND member_id = ?").apply {
                setTimestamp(1, now.ts()); setObject(2, incidentId); setObject(3, memberId)
            }.executeUpdate()
        }
        // An "opened" receipt also proves delivery; the ladder's window starts at whichever arrives first.
        escalation?.onDelivered(incidentId, memberId, now)
    }

    fun claim(incidentId: UUID, memberId: UUID) {
        var won = false
        mutate(incidentId, memberId) { c, now ->
            val claimed = c.prepareStatement(
                "UPDATE incidents SET owner_member = ?, claimed_at = ? WHERE id = ? AND state = 'open' AND owner_member IS NULL",
            ).apply { setObject(1, memberId); setTimestamp(2, now.ts()); setObject(3, incidentId) }.executeUpdate()
            if (claimed == 1) { event(c, incidentId, "claimed", memberId, "{}", now); won = true }
        }
        // Only the member whose UPDATE won tells the ladder: simultaneous claims yield exactly one owner.
        if (won) escalation?.onClaimed(incidentId, memberId)
    }

    fun contest(incidentId: UUID, memberId: UUID) {
        mutate(incidentId, memberId) { c, now ->
            c.prepareStatement("UPDATE incidents SET owner_member = NULL, claimed_at = NULL WHERE id = ? AND state = 'open'")
                .apply { setObject(1, incidentId) }.executeUpdate()
            event(c, incidentId, "contested", memberId, "{}", now)
        }
        escalation?.onContested(incidentId, memberId)
    }

    fun close(incidentId: UUID, memberId: UUID, reason: CloseReason) = mutate(incidentId, memberId, EDITORS) { c, now ->
        val wire = apiJson.encodeToString(CloseReason.serializer(), reason).trim('"')
        val n = c.prepareStatement(
            "UPDATE incidents SET state = 'closed', closed_at = ?, close_reason = ?, closed_by = ? WHERE id = ? AND state = 'open'",
        ).apply { setTimestamp(1, now.ts()); setString(2, wire); setObject(3, memberId); setObject(4, incidentId) }.executeUpdate()
        if (n == 0) conflict("already_closed")
        c.prepareStatement("DELETE FROM member_locations WHERE incident_id = ?").apply { setObject(1, incidentId) }.executeUpdate()
        event(c, incidentId, "closed", memberId, """{"reason":"$wire"}""", now)
    }.also { escalation?.onClosed(incidentId) }

    /** Accepted only while the incident is an open P0/P1; otherwise 409 (design §5.11). */
    fun recordMyLocation(incidentId: UUID, memberId: UUID, req: dz.hirz.shared.api.MyLocationRequest) = db.tx { c ->
        circles.requireRole(c, circleOf(c, incidentId), memberId)
        val open = c.prepareStatement("SELECT state = 'open' AND tier IN ('P0', 'P1') FROM incidents WHERE id = ?")
            .apply { setObject(1, incidentId) }.executeQuery().use { it.next(); it.getBoolean(1) }
        if (!open) conflict("incident_not_active")
        c.prepareStatement("INSERT INTO member_locations (incident_id, member_id, geog, accuracy_m, at) VALUES (?, ?, ST_GeogFromText(?), ?, ?)").apply {
            setObject(1, incidentId); setObject(2, memberId); setString(3, "SRID=4326;POINT(${req.position.lon} ${req.position.lat})")
            setObject(4, req.accuracyM); setTimestamp(5, clock.instant().ts())
        }.executeUpdate()
    }

    fun setMemberPushToken(memberId: UUID, token: String, platform: String) {
        require(token.isNotBlank() && token.length <= 4096) { "invalid push token" }
        db.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO push_tokens (token, member_id, platform, updated_at) VALUES (?, ?, ?, ?)
                   ON CONFLICT (token) DO UPDATE SET member_id = EXCLUDED.member_id, platform = EXCLUDED.platform, updated_at = EXCLUDED.updated_at""",
            ).apply { setString(1, token); setObject(2, memberId); setString(3, platform); setTimestamp(4, clock.instant().ts()) }.executeUpdate()
        }
    }

    /** Asks every live companion of [assetId] "are you OK?" (design §6.2). */
    fun checkIn(assetId: UUID, memberId: UUID): CheckInSentDto {
        data class Target(val token: String, val assetName: String)
        val (circleId, targets) = db.connection.use { c ->
            val circleId = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
                .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
            circles.requireRole(c, circleId, memberId, EDITORS)
            circleId to c.prepareStatement(
                "SELECT s.push_token, a.name FROM sources s JOIN assets a ON a.id = s.asset_id WHERE s.asset_id = ? AND s.revoked_at IS NULL AND s.push_token IS NOT NULL",
            ).apply { setObject(1, assetId) }.executeQuery().toList { Target(it.getString(1), it.getString(2)) }
        }
        val sent = runBlocking(Dispatchers.IO) {
            targets.map { t -> async { safeSend(PushTarget(null, t.token), AlertMessage("checkin", null, null, t.assetName, null)) } }.awaitAll()
        }.count { it is SendResult.Ok }
        db.connection.use { audit(it, circleId, memberId, "checkin_sent", """{"asset":"$assetId","sent":$sent}""") }
        return CheckInSentDto(sent)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun mutate(incidentId: UUID, memberId: UUID, roles: Set<Role> = Role.entries.toSet(), block: (Connection, java.time.Instant) -> Unit) {
        val circleId = db.tx { c ->
            val circleId = circleOf(c, incidentId)
            circles.requireRole(c, circleId, memberId, roles)
            block(c, clock.instant())
            circleId
        }
        notifyChanged(circleId, incidentId)
    }

    private fun circleOf(c: Connection, incidentId: UUID): UUID =
        c.prepareStatement("SELECT circle_id FROM incidents WHERE id = ?").apply { setObject(1, incidentId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }

    private fun event(c: Connection, incidentId: UUID, kind: String, memberId: UUID?, data: String, at: java.time.Instant) {
        c.prepareStatement("INSERT INTO incident_events (incident_id, kind, member_id, data, at) VALUES (?, ?, ?, ?::jsonb, ?)")
            .apply { setObject(1, incidentId); setString(2, kind); setObject(3, memberId); setString(4, data); setTimestamp(5, at.ts()) }
            .executeUpdate()
    }

    private fun notifyChanged(circleId: UUID, incidentId: UUID) = listeners.forEach { it.changed(circleId, incidentId) }

    fun load(c: Connection, id: UUID): IncidentDto {
        val events = c.prepareStatement("SELECT kind, member_id, data::text, at FROM incident_events WHERE incident_id = ? ORDER BY id")
            .apply { setObject(1, id) }.executeQuery().toList {
                IncidentEventDto(it.getString(1), it.getString(2), apiJson.parseToJsonElement(it.getString(3)).jsonObject, it.getTimestamp(4).time)
            }
        val deliveries = c.prepareStatement(
            "SELECT member_id, channel, sent_at, delivered_at, opened_at, error FROM deliveries WHERE incident_id = ? ORDER BY id",
        ).apply { setObject(1, id) }.executeQuery().toList {
            DeliveryDto(it.getString(1), it.getString(2), it.getTimestamp(3)?.time, it.getTimestamp(4)?.time, it.getTimestamp(5)?.time, it.getString(6))
        }
        return c.prepareStatement(
            """SELECT i.id, i.asset_id, a.name, i.circle_id, i.tier, i.rule, i.state, i.silent, i.reasons::text, i.opened_at,
                      i.owner_member, i.claimed_at, i.closed_at, i.close_reason
               FROM incidents i JOIN assets a ON a.id = i.asset_id WHERE i.id = ?""",
        ).apply { setObject(1, id) }.executeQuery().use { rs -> if (!rs.next()) notFound(); row(rs, events, deliveries) }
    }

    private fun row(rs: ResultSet, events: List<IncidentEventDto>, deliveries: List<DeliveryDto>) = IncidentDto(
        id = rs.getString(1), assetId = rs.getString(2), assetName = rs.getString(3), circleId = rs.getString(4),
        tier = Tier.valueOf(rs.getString(5)), rule = RuleId.valueOf(rs.getString(6)), state = rs.getString(7), silent = rs.getBoolean(8),
        reasons = apiJson.decodeFromString(reasonsSerializer, rs.getString(9)), openedAtMs = rs.getTimestamp(10).time,
        ownerMemberId = rs.getString(11), claimedAtMs = rs.getTimestamp(12)?.time, closedAtMs = rs.getTimestamp(13)?.time,
        closeReason = rs.getString(14)?.let { apiJson.decodeFromString(CloseReason.serializer(), "\"$it\"") },
        events = events, deliveries = deliveries,
    )
}
