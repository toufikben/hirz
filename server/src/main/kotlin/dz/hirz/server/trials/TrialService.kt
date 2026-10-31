package dz.hirz.server.trials

import dz.hirz.server.ApiException
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.conflict
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.CONSENT_VERSION
import dz.hirz.shared.api.CircleTrialDto
import dz.hirz.shared.api.CreateTrialRequest
import dz.hirz.shared.api.GuidedTestDto
import dz.hirz.shared.api.GuidedTestRequest
import dz.hirz.shared.api.JoinTrialRequest
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.TrialDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

private val ALGIERS = ZoneId.of("Africa/Algiers")

/**
 * Field trials (P8). Operators (a server allow-list of uids, HIRZ_OPERATORS) open them; a circle's owner joins with
 * the current consent text and can leave at any time, which deletes the circle's trial data. During the trial the
 * family announces guided tests, so a test that did not ring is counted as missed.
 */
class TrialService(private val db: DataSource, private val clock: Clock, private val circles: CircleService, private val operators: Set<String>) {
    private val random = SecureRandom()

    private fun today(): LocalDate = clock.instant().atZone(ALGIERS).toLocalDate()

    private fun dto(rs: ResultSet) = TrialDto(rs.getString("id"), rs.getString("name"), rs.getDate("starts_on").toString(), rs.getDate("ends_on").toString())

    fun requireOperator(uid: String) {
        if (uid !in operators) throw ApiException(HttpStatusCode.Forbidden, "operator_only")
    }

    fun create(uid: String, req: CreateTrialRequest): TrialDto = db.tx { c ->
        requireOperator(uid)
        val starts = runCatching { LocalDate.parse(req.startsOn) }.getOrNull() ?: unprocessable("bad_date")
        val ends = runCatching { LocalDate.parse(req.endsOn) }.getOrNull() ?: unprocessable("bad_date")
        if (ends.isBefore(starts) || req.name.isBlank()) unprocessable("bad_trial")
        c.prepareStatement("INSERT INTO trials (name, starts_on, ends_on, created_by) VALUES (?, ?, ?, ?) RETURNING *")
            .apply { setString(1, req.name.trim().take(80)); setObject(2, starts); setObject(3, ends); setString(4, uid) }
            .executeQuery().use { it.next(); dto(it) }
    }

    /** Trials a circle can join today. */
    fun open(): List<TrialDto> = db.connection.use { c ->
        c.prepareStatement("SELECT * FROM trials WHERE starts_on <= ? AND ends_on >= ? ORDER BY starts_on").apply { setObject(1, today()); setObject(2, today()) }
            .executeQuery().toList(::dto)
    }

    fun join(circleId: UUID, memberId: UUID, req: JoinTrialRequest) = db.tx { c ->
        circles.requireRole(c, circleId, memberId, setOf(Role.OWNER))
        if (req.consentVersion != CONSENT_VERSION) unprocessable("consent_outdated")
        val trialId = uuidOrNotFound(req.trialId)
        val live = c.prepareStatement("SELECT starts_on <= ? AND ends_on >= ? FROM trials WHERE id = ?")
            .apply { setObject(1, today()); setObject(2, today()); setObject(3, trialId) }.executeQuery().use { if (it.next()) it.getBoolean(1) else notFound() }
        if (!live) conflict("trial_not_open")
        val pseudonym = ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        c.prepareStatement(
            """INSERT INTO trial_circles (trial_id, circle_id, consent_at, consent_version, consent_member, pseudonym) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT (trial_id, circle_id) DO UPDATE SET consent_at = EXCLUDED.consent_at, consent_version = EXCLUDED.consent_version,
                 consent_member = EXCLUDED.consent_member, left_at = NULL, pseudonym = EXCLUDED.pseudonym""",
        ).apply {
            setObject(1, trialId); setObject(2, circleId); setTimestamp(3, clock.instant().ts()); setInt(4, req.consentVersion); setObject(5, memberId)
            setString(6, pseudonym)
        }.executeUpdate()
        audit(c, circleId, memberId, "trial_joined", """{"trial":"$trialId","consent":${req.consentVersion}}""")
    }

    /** Leaves every trial the circle is in and deletes its trial data. */
    fun leave(circleId: UUID, memberId: UUID) = db.tx { c ->
        circles.requireRole(c, circleId, memberId, setOf(Role.OWNER))
        // Lock the enrolment first: the nightly job takes the same lock, so it cannot write the circle back meanwhile.
        c.prepareStatement("SELECT 1 FROM trial_circles WHERE circle_id = ? FOR UPDATE").apply { setObject(1, circleId) }.executeQuery().close()
        c.prepareStatement(
            """DELETE FROM trial_daily d USING trial_circles t WHERE t.circle_id = ? AND d.trial_id = t.trial_id AND d.circle_pseudo = t.pseudonym""",
        ).apply { setObject(1, circleId) }.executeUpdate()
        c.prepareStatement("DELETE FROM guided_tests WHERE circle_id = ?").apply { setObject(1, circleId) }.executeUpdate()
        // Problem reports sent while in a trial are trial data too.
        c.prepareStatement(
            "DELETE FROM problem_reports p USING trial_circles t WHERE t.circle_id = ? AND p.circle_id = t.circle_id AND p.at >= t.consent_at",
        ).apply { setObject(1, circleId) }.executeUpdate()
        // The pseudonym is forgotten: a rejoin is a new participant to the operator.
        c.prepareStatement("UPDATE trial_circles SET left_at = ?, pseudonym = ? WHERE circle_id = ? AND left_at IS NULL").apply {
            setTimestamp(1, clock.instant().ts()); setString(2, "left-" + ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) })
            setObject(3, circleId)
        }.executeUpdate()
        audit(c, circleId, memberId, "trial_left", "{}")
    }

    /** The circle's trial: a running one, else one that ended and was never left (so the owner can still leave). */
    fun mine(circleId: UUID, memberId: UUID): CircleTrialDto? = db.connection.use { c ->
        circles.requireRole(c, circleId, memberId)
        c.prepareStatement(
            """SELECT t.*, tc.consent_at, tc.consent_version, t.ends_on < ? AS ended FROM trial_circles tc JOIN trials t ON t.id = tc.trial_id
               WHERE tc.circle_id = ? AND tc.left_at IS NULL AND t.starts_on <= ? ORDER BY (t.ends_on < ?), t.ends_on DESC LIMIT 1""",
        ).apply { setObject(1, today()); setObject(2, circleId); setObject(3, today()); setObject(4, today()) }.executeQuery().use {
            if (!it.next()) null
            else CircleTrialDto(dto(it), it.getTimestamp("consent_at").time, it.getBoolean("ended"), it.getInt("consent_version") != CONSENT_VERSION)
        }
    }

    /** The circle's live trial today and when it joined, or null. */
    private fun active(c: Connection, circleId: UUID): Pair<UUID, Long>? =
        c.prepareStatement(
            """SELECT t.id, tc.consent_at FROM trial_circles tc JOIN trials t ON t.id = tc.trial_id
               WHERE tc.circle_id = ? AND tc.left_at IS NULL AND t.starts_on <= ? AND t.ends_on >= ? LIMIT 1""",
        ).apply { setObject(1, circleId); setObject(2, today()); setObject(3, today()) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to it.getTimestamp(2).time else null }

    /** A member's problem report; text capped at 2000 characters. */
    fun problem(memberId: UUID, req: dz.hirz.shared.api.ProblemReportRequest) = db.tx { c ->
        val circleId = uuidOrNotFound(req.circleId)
        circles.requireRole(c, circleId, memberId)
        val text = req.text.trim().take(2000)
        if (text.isEmpty()) unprocessable("empty_report")
        c.prepareStatement("INSERT INTO problem_reports (circle_id, member_id, text, app_version, device, at) VALUES (?, ?, ?, ?, ?, ?)").apply {
            setObject(1, circleId); setObject(2, memberId); setString(3, text); setString(4, req.appVersion.take(40)); setString(5, req.device.take(80))
            setTimestamp(6, clock.instant().ts())
        }.executeUpdate()
    }

    fun guidedTest(circleId: UUID, memberId: UUID, req: GuidedTestRequest): GuidedTestDto = db.tx { c ->
        circles.requireRole(c, circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN))
        val assetId = uuidOrNotFound(req.assetId)
        val inCircle = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM assets WHERE id = ? AND circle_id = ?)")
            .apply { setObject(1, assetId); setObject(2, circleId) }.executeQuery().use { it.next() && it.getBoolean(1) }
        if (!inCircle) notFound()
        val (trialId, _) = active(c, circleId) ?: conflict("trial_over")
        val now = clock.instant()
        // Only a rule that alerts on this asset can be tested: SOS and duress always; others at P0/P1 in its profile.
        val tier = if (req.rule == dz.hirz.shared.api.RuleId.SOS || req.rule == dz.hirz.shared.api.RuleId.DURESS) dz.hirz.shared.api.Tier.P0
            else dz.hirz.server.assets.ProfileService.loadAt(c, assetId, now)?.tierOf(req.rule)
        if (tier != dz.hirz.shared.api.Tier.P0 && tier != dz.hirz.shared.api.Tier.P1) unprocessable("rule_does_not_alert")
        if (req.rule == dz.hirz.shared.api.RuleId.MISSED_ARRIVAL) {
            val routine = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM routines WHERE asset_id = ? AND active)").apply { setObject(1, assetId) }
                .executeQuery().use { it.next() && it.getBoolean(1) }
            if (!routine) unprocessable("no_routine")
        }
        // An open incident of the rule would swallow the test's alarm.
        val open = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM incidents WHERE asset_id = ? AND rule = ? AND state = 'open')")
            .apply { setObject(1, assetId); setString(2, req.rule.name) }.executeQuery().use { it.next() && it.getBoolean(1) }
        if (open) conflict("incident_open")
        val id = c.prepareStatement(
            "INSERT INTO guided_tests (trial_id, circle_id, asset_id, rule, started_at, member_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
        ).apply { setObject(1, trialId); setObject(2, circleId); setObject(3, assetId); setString(4, req.rule.name); setTimestamp(5, now.ts()); setObject(6, memberId) }
            .executeQuery().use { it.next(); it.getString(1) }
        GuidedTestDto(id, req.assetId, req.rule, now.toEpochMilli())
    }
}

fun Route.trialRoutes(service: TrialService) {
    post("/v1/operator/trials") { call.respond(service.create(call.member().uid, call.receive<CreateTrialRequest>())) }
    get("/v1/trials/open") { call.respond(service.open()) }
    get("/v1/circles/{id}/trial") {
        val t = service.mine(uuidOrNotFound(call.parameters["id"]), call.member().memberId)
        if (t == null) call.respond(HttpStatusCode.NoContent) else call.respond(t)
    }
    post("/v1/circles/{id}/trial") {
        service.join(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<JoinTrialRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/v1/circles/{id}/trial") {
        service.leave(uuidOrNotFound(call.parameters["id"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/feedback") {
        service.problem(call.member().memberId, call.receive<dz.hirz.shared.api.ProblemReportRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/circles/{id}/trial/tests") {
        call.respond(service.guidedTest(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<GuidedTestRequest>()))
    }
}
