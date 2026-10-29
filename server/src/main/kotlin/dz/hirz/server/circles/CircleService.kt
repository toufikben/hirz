package dz.hirz.server.circles

import dz.hirz.server.auth.Codes
import dz.hirz.server.conflict
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.gone
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CreateInviteRequest
import dz.hirz.shared.api.InviteDto
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.UpdateMemberRequest
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** Receives circle membership changes (the live hub closes sockets of removed members). */
fun interface CircleEvents {
    fun memberRemoved(circleId: UUID, memberId: UUID)

    companion object {
        val NONE = CircleEvents { _, _ -> }
    }
}

fun Role.wire(): String = name.lowercase()
fun roleOf(wire: String): Role = Role.valueOf(wire.uppercase())

/** Parses a path id; anything malformed is simply "not found" so ids never leak existence. */
fun uuidOrNotFound(raw: String?): UUID = runCatching { UUID.fromString(raw) }.getOrNull() ?: notFound()

class CircleService(private val db: DataSource, private val clock: Clock, private val events: CircleEvents) {

    /** The caller's active role in [circleId], or 404 when not an active member or not in [allowed]. */
    fun requireRole(c: Connection, circleId: UUID, memberId: UUID, allowed: Set<Role> = Role.entries.toSet()): Role {
        val role = c.prepareStatement(
            "SELECT role FROM circle_members WHERE circle_id = ? AND member_id = ? AND (expires_at IS NULL OR expires_at > ?)",
        ).apply { setObject(1, circleId); setObject(2, memberId); setTimestamp(3, clock.instant().ts()) }
            .executeQuery().use { if (it.next()) roleOf(it.getString(1)) else null }
        if (role == null || role !in allowed) notFound()
        return role
    }

    fun create(memberId: UUID, name: String): CircleDto = db.tx { c ->
        require(name.isNotBlank() && name.length <= 80) { "name must be 1–80 characters" }
        val id = c.prepareStatement("INSERT INTO circles (name) VALUES (?) RETURNING id").apply { setString(1, name.trim()) }
            .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        c.prepareStatement("INSERT INTO circle_members (circle_id, member_id, role, priority) VALUES (?, ?, 'owner', 1)")
            .apply { setObject(1, id); setObject(2, memberId) }.executeUpdate()
        CircleDto(id.toString(), name.trim(), Role.OWNER)
    }

    fun list(memberId: UUID): List<CircleDto> = db.connection.use { c ->
        c.prepareStatement(
            """SELECT ci.id, ci.name, cm.role FROM circle_members cm JOIN circles ci ON ci.id = cm.circle_id
               WHERE cm.member_id = ? AND (cm.expires_at IS NULL OR cm.expires_at > ?) ORDER BY ci.created_at""",
        ).apply { setObject(1, memberId); setTimestamp(2, clock.instant().ts()) }
            .executeQuery().toList { CircleDto(it.getString(1), it.getString(2), roleOf(it.getString(3))) }
    }

    fun members(circleId: UUID, memberId: UUID): List<MemberDto> = db.connection.use { c ->
        requireRole(c, circleId, memberId)
        c.prepareStatement(
            """SELECT m.id, m.display_name, cm.role, cm.priority, cm.expires_at
               FROM circle_members cm JOIN members m ON m.id = cm.member_id
               WHERE cm.circle_id = ? AND (cm.expires_at IS NULL OR cm.expires_at > ?)
               ORDER BY cm.priority, cm.added_at""",
        ).apply { setObject(1, circleId); setTimestamp(2, clock.instant().ts()) }.executeQuery().toList {
            MemberDto(it.getString(1), it.getString(2), roleOf(it.getString(3)), it.getInt(4), it.getTimestamp(5)?.time)
        }
    }

    fun invite(circleId: UUID, memberId: UUID, req: CreateInviteRequest): InviteDto = db.tx { c ->
        requireRole(c, circleId, memberId, setOf(Role.OWNER))
        if (req.role !in setOf(Role.GUARDIAN, Role.VIEWER, Role.TEMPORARY)) unprocessable("role_not_invitable")
        if (req.expiresInHours !in 1..24) unprocessable("invite_expiry_out_of_range")
        val now = clock.instant()
        val memberExpiry = req.memberExpiresAtMs?.let { java.time.Instant.ofEpochMilli(it) }
        if (req.role == Role.TEMPORARY && (memberExpiry == null || !memberExpiry.isAfter(now))) unprocessable("temporary_needs_expiry")
        val code = Codes.newCode()
        val expiresAt = now.plus(Duration.ofHours(req.expiresInHours.toLong()))
        c.prepareStatement(
            "INSERT INTO invites (circle_id, code_hash, role, member_expires_at, created_by, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
        ).apply {
            setObject(1, circleId); setString(2, Codes.hash(code)); setString(3, req.role.wire())
            setTimestamp(4, if (req.role == Role.TEMPORARY) memberExpiry?.ts() else null)
            setObject(5, memberId); setTimestamp(6, expiresAt.ts())
        }.executeUpdate()
        InviteDto(code, "hirz://invite/$code", expiresAt.toEpochMilli())
    }

    fun accept(rawCode: String, memberId: UUID): CircleDto = db.tx { c ->
        val now = clock.instant()
        val row = c.prepareStatement(
            "SELECT id, circle_id, role, member_expires_at, expires_at, used_at FROM invites WHERE code_hash = ? FOR UPDATE",
        ).apply { setString(1, Codes.hash(Codes.normalize(rawCode))) }.executeQuery().use { rs ->
            if (!rs.next()) notFound()
            Invite(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3),
                rs.getTimestamp(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6))
        }
        if (row.usedAt != null) gone("invite_used")
        if (!row.expiresAt.isAfter(now)) gone("invite_expired")
        val already = c.prepareStatement("SELECT 1 FROM circle_members WHERE circle_id = ? AND member_id = ?")
            .apply { setObject(1, row.circleId); setObject(2, memberId) }.executeQuery().use { it.next() }
        if (already) conflict("already_member")
        val nextPriority = c.prepareStatement("SELECT COALESCE(max(priority), 0) + 1 FROM circle_members WHERE circle_id = ?")
            .apply { setObject(1, row.circleId) }.executeQuery().use { it.next(); it.getInt(1) }
        c.prepareStatement("INSERT INTO circle_members (circle_id, member_id, role, priority, expires_at) VALUES (?, ?, ?, ?, ?)").apply {
            setObject(1, row.circleId); setObject(2, memberId); setString(3, row.role); setInt(4, nextPriority)
            setTimestamp(5, row.memberExpiresAt)
        }.executeUpdate()
        c.prepareStatement("UPDATE invites SET used_at = ?, used_by = ? WHERE id = ?")
            .apply { setTimestamp(1, now.ts()); setObject(2, memberId); setObject(3, row.id) }.executeUpdate()
        audit(c, row.circleId, memberId, "member_joined", """{"role":"${row.role}"}""")
        val name = c.prepareStatement("SELECT name FROM circles WHERE id = ?").apply { setObject(1, row.circleId) }
            .executeQuery().use { it.next(); it.getString(1) }
        CircleDto(row.circleId.toString(), name, roleOf(row.role))
    }

    /** A no-app contact reached by SMS (design §5.6). Local Algerian numbers become +213. */
    fun addContact(circleId: UUID, actor: UUID, req: dz.hirz.shared.api.ContactRequest): MemberDto = db.tx { c ->
        requireRole(c, circleId, actor, setOf(Role.OWNER))
        require(req.name.isNotBlank() && req.name.length <= 80) { "name must be 1–80 characters" }
        if (req.lang !in setOf("ar", "fr", "en")) unprocessable("unsupported_lang")
        val phone = normalizePhone(req.phone) ?: unprocessable("invalid_phone")
        val id = c.prepareStatement("INSERT INTO members (firebase_uid, display_name, lang) VALUES (?, ?, ?) RETURNING id")
            .apply { setString(1, "contact:" + UUID.randomUUID()); setString(2, req.name.trim()); setString(3, req.lang) }
            .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        val priority = c.prepareStatement("SELECT COALESCE(max(priority), 0) + 1 FROM circle_members WHERE circle_id = ?")
            .apply { setObject(1, circleId) }.executeQuery().use { it.next(); it.getInt(1) }
        c.prepareStatement("INSERT INTO circle_members (circle_id, member_id, role, priority, phone) VALUES (?, ?, 'contact', ?, ?)")
            .apply { setObject(1, circleId); setObject(2, id); setInt(3, priority); setString(4, phone) }.executeUpdate()
        audit(c, circleId, actor, "contact_added", """{"member":"$id"}""")
        MemberDto(id.toString(), req.name.trim(), Role.CONTACT, priority)
    }

    fun update(circleId: UUID, actor: UUID, target: UUID, req: UpdateMemberRequest) = db.tx { c ->
        requireRole(c, circleId, actor, setOf(Role.OWNER))
        val current = currentRole(c, circleId, target) ?: notFound()
        req.role?.let { newRole ->
            if (newRole == Role.CONTACT || newRole == Role.TEMPORARY) unprocessable("role_not_assignable")
            if (current == Role.OWNER && newRole != Role.OWNER && ownerCount(c, circleId) <= 1) conflict("last_owner")
            c.prepareStatement("UPDATE circle_members SET role = ? WHERE circle_id = ? AND member_id = ?")
                .apply { setString(1, newRole.wire()); setObject(2, circleId); setObject(3, target) }.executeUpdate()
        }
        req.priority?.let { p ->
            require(p in 0..1000) { "priority must be 0–1000" }
            c.prepareStatement("UPDATE circle_members SET priority = ? WHERE circle_id = ? AND member_id = ?")
                .apply { setInt(1, p); setObject(2, circleId); setObject(3, target) }.executeUpdate()
        }
        audit(c, circleId, actor, "member_updated", """{"member":"$target"}""")
    }

    fun remove(circleId: UUID, actor: UUID, target: UUID) {
        db.tx { c ->
            val actorRole = requireRole(c, circleId, actor)
            if (actor != target && actorRole != Role.OWNER) notFound()
            val current = currentRole(c, circleId, target) ?: notFound()
            if (current == Role.OWNER && ownerCount(c, circleId) <= 1) conflict("last_owner")
            c.prepareStatement("DELETE FROM circle_members WHERE circle_id = ? AND member_id = ?")
                .apply { setObject(1, circleId); setObject(2, target) }.executeUpdate()
            audit(c, circleId, actor, "member_removed", """{"member":"$target"}""")
        }
        events.memberRemoved(circleId, target)
    }

    private fun currentRole(c: Connection, circleId: UUID, member: UUID): Role? =
        c.prepareStatement("SELECT role FROM circle_members WHERE circle_id = ? AND member_id = ?")
            .apply { setObject(1, circleId); setObject(2, member) }.executeQuery().use { if (it.next()) roleOf(it.getString(1)) else null }

    private fun ownerCount(c: Connection, circleId: UUID): Int =
        c.prepareStatement("SELECT count(*) FROM circle_members WHERE circle_id = ? AND role = 'owner'")
            .apply { setObject(1, circleId) }.executeQuery().use { it.next(); it.getInt(1) }

    private data class Invite(
        val id: UUID, val circleId: UUID, val role: String,
        val memberExpiresAt: java.sql.Timestamp?, val expiresAt: java.time.Instant, val usedAt: java.sql.Timestamp?,
    )
}

/** Appends to the circle's audit log; [data] is a JSON object literal. */
fun audit(c: Connection, circleId: UUID?, actor: UUID?, action: String, data: String = "{}") {
    c.prepareStatement("INSERT INTO audit_log (circle_id, actor_member, action, data) VALUES (?, ?, ?, ?::jsonb)")
        .apply { setObject(1, circleId); setObject(2, actor); setString(3, action); setString(4, data) }.executeUpdate()
}

/** "0550 12 34 56" → "+213550123456"; "+33…" kept; anything else → null. */
fun normalizePhone(raw: String): String? {
    val digits = raw.filter { it.isDigit() || it == '+' }
    return when {
        Regex("^\\+[1-9][0-9]{7,14}$").matches(digits) -> digits
        Regex("^0[5-7][0-9]{8}$").matches(digits) -> "+213" + digits.drop(1)
        Regex("^0[1-4][0-9]{7}$").matches(digits) -> "+213" + digits.drop(1)
        else -> null
    }
}
