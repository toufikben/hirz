package dz.hirz.server.incidents

import dz.hirz.server.apiJson
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.ApiException
import dz.hirz.server.badRequest
import dz.hirz.server.conflict
import dz.hirz.server.gone
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
import dz.hirz.shared.rules.RuleConfig
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
/** Viewers watch and may contest, but never own an incident (P1 minor #14). */
private val CLAIMERS = setOf(Role.OWNER, Role.GUARDIAN, Role.TEMPORARY)
private val ACTION_KINDS = setOf("called_number", "went_to_last_position", "checked_school", "other")
/** The same number called twice within this window is reported, not repeated (design §5.8). */
private const val DUPLICATE_CALL_S = 600L
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
    private val rules: RuleConfig = RuleConfig(),
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
        mutate(incidentId, memberId, CLAIMERS) { c, now ->
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

    /**
     * Closes at once when policy allows; otherwise records a close request that a second guardian (or the only
     * guardian's second tap) confirms within [RuleConfig.singleGuardianConfirmMs] (design §5.7). Tapping close on
     * a pending request is a confirmation.
     */
    fun close(incidentId: UUID, memberId: UUID, reason: CloseReason) {
        var askSoleGuardian = false
        val closed = mutateResult(incidentId, memberId, EDITORS) { c, now, circleId ->
            val tier = openTier(c, incidentId) ?: conflict("already_closed")
            ClosePolicy.pending(c, incidentId, forUpdate = true)?.let { req ->
                if (now.isBefore(req.expiresAt)) return@mutateResult confirmLocked(c, incidentId, circleId, memberId, req, now)
                expire(c, incidentId, now)
            }
            val needsQuorum = tier == Tier.P0 || (reason == CloseReason.FOUND_SAFE && !ClosePolicy.foundSafeProof(c, incidentId, now))
            if (!needsQuorum) {
                closeLocked(c, incidentId, memberId, reason, null, now)
                return@mutateResult true
            }
            ClosePolicy.request(c, incidentId, memberId, reason, now, now.plusMillis(rules.singleGuardianConfirmMs))
            event(c, incidentId, "close_requested", memberId, """{"reason":"${ClosePolicy.wire(reason)}"}""", now)
            askSoleGuardian = ClosePolicy.editorCount(c, circleId, now) == 1
            false
        }
        if (closed) escalation?.onClosed(incidentId)
        if (askSoleGuardian) askToConfirm(incidentId, memberId)
    }

    /** A second guardian confirms; the only guardian may confirm their own request. Expired requests answer 410. */
    fun confirmClose(incidentId: UUID, memberId: UUID) {
        var expired = false
        mutateResult(incidentId, memberId, EDITORS) { c, now, circleId ->
            openTier(c, incidentId) ?: conflict("already_closed")
            val req = ClosePolicy.pending(c, incidentId, forUpdate = true) ?: conflict("no_close_request")
            if (!now.isBefore(req.expiresAt)) {
                expire(c, incidentId, now)
                expired = true
                return@mutateResult false
            }
            confirmLocked(c, incidentId, circleId, memberId, req, now)
        }
        if (expired) gone("close_request_expired")
        escalation?.onClosed(incidentId)
    }

    /** Lapses every close request past its window; the incident stays open (design §5.7). */
    fun expireCloseRequests() {
        val now = clock.instant()
        val lapsed = db.tx { c ->
            c.prepareStatement(
                "SELECT r.incident_id, i.circle_id FROM close_requests r JOIN incidents i ON i.id = r.incident_id WHERE r.expires_at <= ? FOR UPDATE OF r SKIP LOCKED",
            ).apply { setTimestamp(1, now.ts()) }.executeQuery()
                .toList { it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java) }
                .onEach { (id, _) -> expire(c, id, now) }
        }
        lapsed.forEach { (id, circleId) -> notifyChanged(circleId, id) }
    }

    private fun confirmLocked(c: Connection, incidentId: UUID, circleId: UUID, memberId: UUID, req: CloseRequestRow, now: java.time.Instant): Boolean {
        if (req.requestedBy == memberId && ClosePolicy.editorCount(c, circleId, now) >= 2) conflict("needs_second_guardian")
        closeLocked(c, incidentId, req.requestedBy, req.reason, memberId, now)
        return true
    }

    private fun closeLocked(c: Connection, incidentId: UUID, closedBy: UUID, reason: CloseReason, confirmedBy: UUID?, now: java.time.Instant) {
        val wire = ClosePolicy.wire(reason)
        val n = c.prepareStatement(
            "UPDATE incidents SET state = 'closed', closed_at = ?, close_reason = ?, closed_by = ? WHERE id = ? AND state = 'open'",
        ).apply { setTimestamp(1, now.ts()); setString(2, wire); setObject(3, closedBy); setObject(4, incidentId) }.executeUpdate()
        if (n == 0) conflict("already_closed")
        ClosePolicy.drop(c, incidentId)
        c.prepareStatement("DELETE FROM member_locations WHERE incident_id = ?").apply { setObject(1, incidentId) }.executeUpdate()
        val confirmed = confirmedBy?.let { ""","confirmedBy":"$it"""" } ?: ""
        event(c, incidentId, "closed", closedBy, """{"reason":"$wire"$confirmed}""", now)
    }

    private fun expire(c: Connection, incidentId: UUID, now: java.time.Instant) {
        ClosePolicy.drop(c, incidentId)
        event(c, incidentId, "close_request_expired", null, "{}", now)
    }

    private fun openTier(c: Connection, incidentId: UUID): Tier? =
        c.prepareStatement("SELECT tier FROM incidents WHERE id = ? AND state = 'open' FOR UPDATE").apply { setObject(1, incidentId) }
            .executeQuery().use { if (it.next()) Tier.valueOf(it.getString(1)) else null }

    /** The only guardian gets a push asking for the second tap; it carries no reason and no position. */
    private fun askToConfirm(incidentId: UUID, memberId: UUID) {
        val (assetName, tokens) = db.connection.use { c ->
            val name = c.prepareStatement("SELECT a.name FROM incidents i JOIN assets a ON a.id = i.asset_id WHERE i.id = ?")
                .apply { setObject(1, incidentId) }.executeQuery().use { it.next(); it.getString(1) }
            name to c.prepareStatement("SELECT token FROM push_tokens WHERE member_id = ?").apply { setObject(1, memberId) }
                .executeQuery().toList { it.getString(1) }
        }
        runBlocking(Dispatchers.IO) {
            tokens.map { t -> async { safeSend(PushTarget(memberId, t), AlertMessage("close_confirm", incidentId, null, assetName, null)) } }.awaitAll()
        }
    }

    /** Appends a shared action; the same number called twice within 10 minutes answers 409 with who already did it. */
    fun action(incidentId: UUID, memberId: UUID, kind: String, value: String?) = mutate(incidentId, memberId) { c, now ->
        if (kind !in ACTION_KINDS) badRequest("unknown_action")
        val v = value?.trim()?.take(120)
        if (kind == "called_number") {
            if (v.isNullOrEmpty()) badRequest("number_required")
            c.prepareStatement(
                """SELECT member_id, at FROM incident_events WHERE incident_id = ? AND kind = 'action'
                   AND data->>'kind' = 'called_number' AND data->>'value' = ? AND at > ? ORDER BY at DESC LIMIT 1""",
            ).apply { setObject(1, incidentId); setString(2, v); setTimestamp(3, now.minusSeconds(DUPLICATE_CALL_S).ts()) }
                .executeQuery().use {
                    if (it.next()) {
                        throw ApiException(HttpStatusCode.Conflict, "already_done", """{"memberId":"${it.getString(1)}","atMs":${it.getTimestamp(2).time}}""")
                    }
                }
        }
        val data = buildJsonObject {
            put("kind", kind)
            if (v != null) put("value", v)
        }
        event(c, incidentId, "action", memberId, data.toString(), now)
    }

    /** Post-close review by an owner or guardian; the latest answer per reviewer counts. */
    fun review(incidentId: UUID, memberId: UUID, falseAlarm: Boolean, note: String?) = mutate(incidentId, memberId, EDITORS) { c, now ->
        if (openTier(c, incidentId) != null) conflict("not_closed")
        c.prepareStatement(
            """INSERT INTO incident_reviews (incident_id, member_id, false_alarm, note, at) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT (incident_id, member_id) DO UPDATE SET false_alarm = EXCLUDED.false_alarm, note = EXCLUDED.note, at = EXCLUDED.at""",
        ).apply { setObject(1, incidentId); setObject(2, memberId); setBoolean(3, falseAlarm); setString(4, note?.take(500)); setTimestamp(5, now.ts()) }
            .executeUpdate()
        event(c, incidentId, "reviewed", memberId, """{"falseAlarm":$falseAlarm}""", now)
    }

    /** Incidents judged false alarms by at least one reviewer, per rule (design §5.9). */
    fun falseAlarms(circleId: UUID, memberId: UUID): Map<String, Int> = db.connection.use { c ->
        circles.requireRole(c, circleId, memberId)
        c.prepareStatement(
            """SELECT i.rule, count(DISTINCT i.id) FROM incidents i JOIN incident_reviews r ON r.incident_id = i.id
               WHERE i.circle_id = ? AND r.false_alarm GROUP BY i.rule""",
        ).apply { setObject(1, circleId) }.executeQuery().toList { it.getString(1) to it.getInt(2) }.toMap()
    }

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

    private fun <T> mutateResult(incidentId: UUID, memberId: UUID, roles: Set<Role>, block: (Connection, java.time.Instant, UUID) -> T): T {
        val (circleId, result) = db.tx { c ->
            val circleId = circleOf(c, incidentId)
            circles.requireRole(c, circleId, memberId, roles)
            circleId to block(c, clock.instant(), circleId)
        }
        notifyChanged(circleId, incidentId)
        return result
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
            .copy(pendingClose = ClosePolicy.pending(c, id)?.takeIf { clock.instant().isBefore(it.expiresAt) }?.dto())
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
