package dz.hirz.server.sms

import dz.hirz.server.auth.Codes
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.normalizePhone
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.gone
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.shared.api.GatewayCodeDto
import dz.hirz.shared.api.GatewayPairResponse
import dz.hirz.shared.api.OutboxItemDto
import dz.hirz.shared.api.Role
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

private const val RETAKE_AFTER_S = 60L
private const val MAX_ATTEMPTS = 3
private const val OUTBOX_BATCH = 20

data class GatewayPrincipal(val gatewayId: UUID, val circleId: UUID)

/**
 * The circle's SMS gateway (design §12.3.3): it drains [outbound_sms] for its own circle only and relays contact
 * replies. "1" claims the latest open incident that contact was told about; "2" declines; anything else is logged.
 */
class GatewayService(
    private val db: DataSource,
    private val clock: Clock,
    private val circles: CircleService,
    private val incidents: IncidentService,
) {
    fun createCode(circleId: UUID, memberId: UUID): GatewayCodeDto = db.tx { c ->
        circles.requireRole(c, circleId, memberId, setOf(Role.OWNER))
        val code = Codes.newCode()
        val expires = clock.instant().plus(Duration.ofMinutes(10))
        c.prepareStatement("INSERT INTO gateway_codes (circle_id, code_hash, created_by, expires_at) VALUES (?, ?, ?, ?)")
            .apply { setObject(1, circleId); setString(2, Codes.hash(code)); setObject(3, memberId); setTimestamp(4, expires.ts()) }.executeUpdate()
        audit(c, circleId, memberId, "gateway_code_created")
        GatewayCodeDto(code, expires.toEpochMilli())
    }

    fun pair(code: String, pushToken: String?): GatewayPairResponse = db.tx { c ->
        val now = clock.instant()
        val (codeId, circleId, expired, used) = c.prepareStatement(
            "SELECT id, circle_id, expires_at <= ?, used_at IS NOT NULL FROM gateway_codes WHERE code_hash = ? FOR UPDATE",
        ).apply { setTimestamp(1, now.ts()); setString(2, Codes.hash(Codes.normalize(code))) }.executeQuery().use {
            if (!it.next()) notFound()
            listOf(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getBoolean(3), it.getBoolean(4))
        }
        if (used as Boolean) gone("pairing_code_used")
        if (expired as Boolean) gone("pairing_code_expired")
        c.prepareStatement("UPDATE gateway_codes SET used_at = ? WHERE id = ?").apply { setTimestamp(1, now.ts()); setObject(2, codeId) }.executeUpdate()
        val token = Codes.newSourceToken()
        c.prepareStatement("INSERT INTO gateways (circle_id, token_hash, push_token, created_at) VALUES (?, ?, ?, ?)").apply {
            setObject(1, circleId); setString(2, Codes.hash(token)); setString(3, pushToken?.takeIf { it.isNotBlank() }); setTimestamp(4, now.ts())
        }.executeUpdate()
        audit(c, circleId as UUID, null, "gateway_paired")
        GatewayPairResponse(token, circleId.toString())
    }

    fun find(token: String): GatewayPrincipal? = db.connection.use { c ->
        c.prepareStatement("UPDATE gateways SET last_seen_at = ? WHERE token_hash = ? AND revoked_at IS NULL RETURNING id, circle_id")
            .apply { setTimestamp(1, clock.instant().ts()); setString(2, Codes.hash(token)) }.executeQuery()
            .use { if (it.next()) GatewayPrincipal(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java)) else null }
    }

    /** Pending items of this gateway's circle; each is marked taken, so two pollers never both send it. */
    fun outbox(g: GatewayPrincipal): List<OutboxItemDto> = db.tx { c ->
        val now = clock.instant()
        c.prepareStatement(
            """UPDATE outbound_sms SET taken_at = ?, attempts = attempts + 1, gateway_id = ? WHERE id IN (
                 SELECT id FROM outbound_sms WHERE circle_id = ? AND sent_at IS NULL AND attempts < ?
                   AND (taken_at IS NULL OR taken_at <= ?)
                 ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)
               RETURNING id, to_phone, body""",
        ).apply {
            setTimestamp(1, now.ts()); setObject(2, g.gatewayId); setObject(3, g.circleId); setInt(4, MAX_ATTEMPTS)
            setTimestamp(5, now.minusSeconds(RETAKE_AFTER_S).ts()); setInt(6, OUTBOX_BATCH)
        }.executeQuery().toList { OutboxItemDto(it.getLong(1), it.getString(2), it.getString(3)) }.sortedBy { it.id }
    }

    fun result(g: GatewayPrincipal, id: Long, status: String, error: String?) = db.tx { c ->
        require(status == "sent" || status == "failed") { "status must be sent or failed" }
        val n = c.prepareStatement(
            "UPDATE outbound_sms SET result = ?, error = ?, sent_at = CASE WHEN ? THEN ? ELSE sent_at END WHERE id = ? AND circle_id = ?",
        ).apply {
            setString(1, status); setString(2, error?.take(200)); setBoolean(3, status == "sent"); setTimestamp(4, clock.instant().ts())
            setLong(5, id); setObject(6, g.circleId)
        }.executeUpdate()
        if (n == 0) notFound()
    }

    fun inbox(g: GatewayPrincipal, from: String, body: String, receivedAt: java.time.Instant) {
        val phone = normalizePhone(from) ?: from.trim()
        val text = normalizeReply(body)
        data class Target(val member: UUID?, val incident: UUID?)
        val target = db.connection.use { c ->
            val member = c.prepareStatement(
                "SELECT member_id FROM circle_members WHERE circle_id = ? AND role = 'contact' AND phone = ? LIMIT 1",
            ).apply { setObject(1, g.circleId); setString(2, phone) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
            // The latest open incident this contact was told about.
            val incident = member?.let { m ->
                c.prepareStatement(
                    """SELECT o.incident_id FROM outbound_sms o JOIN incidents i ON i.id = o.incident_id
                       WHERE o.circle_id = ? AND o.member_id = ? AND i.state = 'open' ORDER BY o.created_at DESC LIMIT 1""",
                ).apply { setObject(1, g.circleId); setObject(2, m) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
            }
            Target(member, incident)
        }
        val action = when {
            target.member == null -> "unknown_sender"
            target.incident == null -> "no_open_incident"
            text == "1" -> if (incidents.claimBySms(target.incident, target.member)) "claimed" else "already_claimed"
            text == "2" -> { incidents.escalation?.onContactDeclined(target.incident, target.member); "declined" }
            else -> "ignored"
        }
        db.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO inbound_sms (circle_id, gateway_id, from_phone, body, received_at, member_id, incident_id, action) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).apply {
                setObject(1, g.circleId); setObject(2, g.gatewayId); setString(3, phone); setString(4, body.take(500)); setTimestamp(5, receivedAt.ts())
                setObject(6, target.member); setObject(7, target.incident); setString(8, action)
            }.executeUpdate()
        }
    }

    companion object {
        /** Arabic-Indic and Extended Arabic-Indic digits become ASCII; whitespace is trimmed. */
        fun normalizeReply(body: String): String = body.map { ch ->
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                else -> ch
            }
        }.joinToString("").trim()
    }
}
