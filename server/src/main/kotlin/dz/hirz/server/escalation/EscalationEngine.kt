package dz.hirz.server.escalation

import dz.hirz.server.circles.CircleService
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.notify.AlertMessage
import dz.hirz.server.notify.Notifier
import dz.hirz.server.notify.PushTarget
import dz.hirz.server.notify.SendResult
import dz.hirz.server.sms.SmsText
import dz.hirz.server.ts
import dz.hirz.shared.api.Tier
import dz.hirz.shared.rules.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

private object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

private val ladderJson = Json {
    serializersModule = SerializersModule { contextual(UuidSerializer); contextual(InstantSerializer) }
}

/** Candidates for ownership, in order; Task 2 replaces the plain priority order with nearest/custodian rules. */
fun interface OwnerOrdering {
    fun order(c: Connection, incidentId: UUID, circleId: UUID, now: Instant): List<UUID>
}

/** Owners, guardians and unexpired temporary members, by circle priority. Viewers cannot own (they cannot claim). */
val PRIORITY_ORDER = OwnerOrdering { c, _, circleId, now ->
    c.prepareStatement(
        """SELECT member_id FROM circle_members WHERE circle_id = ? AND role IN ('owner', 'guardian', 'temporary')
           AND (expires_at IS NULL OR expires_at > ?) ORDER BY priority, added_at, member_id""",
    ).apply { setObject(1, circleId); setTimestamp(2, now.ts()) }.executeQuery().toList { it.getObject(1, UUID::class.java) }
}

/** Persists ladder state per incident, applies events, and performs the actions (design §5.3–5.6). */
class EscalationEngine(
    private val db: DataSource,
    private val clock: Clock,
    private val notifier: Notifier,
    private val cfg: RuleConfig,
    @Suppress("unused") private val circles: CircleService,
    var ordering: OwnerOrdering = PRIORITY_ORDER,
) {
    private val log = LoggerFactory.getLogger(EscalationEngine::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 5_000): Job = scope.launch {
        while (isActive) {
            runCatching { tick() }.onFailure { log.error("escalation tick failed", it) }
            delay(everyMs)
        }
    }

    fun onOpened(incidentId: UUID, circleId: UUID, tier: Tier) {
        if (tier == Tier.P2) return
        val now = clock.instant()
        db.tx { c ->
            val state = Ladder.open(tier, ordering.order(c, incidentId, circleId, now), now, cfg)
            save(c, incidentId, state)
            logRow(c, incidentId, "opened", null, now)
        }
    }

    fun onDelivered(incidentId: UUID, member: UUID, at: Instant) = apply(incidentId, LadderEvent.Delivered(member, at), wait = true)
    fun onClaimed(incidentId: UUID, member: UUID) = apply(incidentId, LadderEvent.Claimed(member, clock.instant()), wait = true)
    fun onContested(incidentId: UUID, member: UUID) = apply(incidentId, LadderEvent.Contested(member, clock.instant()), wait = true)
    fun onClosed(incidentId: UUID) = apply(incidentId, LadderEvent.Closed(clock.instant()), wait = true)

    /** Advances every ladder whose next check is due. Rows are locked, so two servers never double-escalate. */
    fun tick() {
        val now = clock.instant()
        val due = db.connection.use { c ->
            c.prepareStatement("SELECT incident_id FROM escalation_state WHERE next_check_at <= ?").apply { setTimestamp(1, now.ts()) }
                .executeQuery().toList { it.getObject(1, UUID::class.java) }
        }
        due.forEach { apply(it, LadderEvent.Tick(now), wait = false) }
    }

    /** Member events wait for the row lock (never dropped); ticks skip a row another worker holds. */
    private fun apply(incidentId: UUID, event: LadderEvent, wait: Boolean) {
        val now = clock.instant()
        val actions = db.tx { c ->
            val state = c.prepareStatement("SELECT data::text FROM escalation_state WHERE incident_id = ? FOR UPDATE${if (wait) "" else " SKIP LOCKED"}")
                .apply { setObject(1, incidentId) }.executeQuery().use { if (it.next()) ladderJson.decodeFromString(LadderState.serializer(), it.getString(1)) else null }
                ?: return@tx emptyList()
            // Untried candidates are re-ranked at every tick (nearest guardian, custodian); the current owner never changes here.
            val ranked = if (event is LadderEvent.Tick && state.stage == Stage.GUARDIANS) {
                val tried = state.order.take(state.index + 1)
                state.copy(order = tried + ordering.order(c, incidentId, circleOf(c, incidentId), now).filter { it !in tried })
            } else state
            val (next, actions) = Ladder.next(ranked, event, cfg)
            if (next != state) save(c, incidentId, next)
            actions.filterIsInstance<LadderAction.Log>().forEach { logRow(c, incidentId, it.kind, it.member, now) }
            if (LadderAction.NotifyContacts in actions || LadderAction.RetryAll in actions) queueContactSms(c, incidentId, now)
            actions
        }
        val members = actions.filterIsInstance<LadderAction.NotifyMember>().map { it.member }.toMutableList()
        if (LadderAction.RetryAll in actions) members += db.connection.use { c -> ordering.order(c, incidentId, circleOf(c, incidentId), now) }
        if (members.isNotEmpty()) push(incidentId, members.distinct())
    }

    private fun circleOf(c: Connection, incidentId: UUID): UUID =
        c.prepareStatement("SELECT circle_id FROM incidents WHERE id = ?").apply { setObject(1, incidentId) }
            .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }

    private fun save(c: Connection, incidentId: UUID, st: LadderState) {
        c.prepareStatement(
            """INSERT INTO escalation_state (incident_id, data, next_check_at) VALUES (?, ?::jsonb, ?)
               ON CONFLICT (incident_id) DO UPDATE SET data = EXCLUDED.data, next_check_at = EXCLUDED.next_check_at""",
        ).apply {
            setObject(1, incidentId); setString(2, ladderJson.encodeToString(LadderState.serializer(), st))
            setTimestamp(3, (if (st.stage == Stage.CLAIMED || st.stage == Stage.CLOSED) Instant.parse("9999-01-01T00:00:00Z") else st.nextCheck).ts())
        }.executeUpdate()
    }

    private fun logRow(c: Connection, incidentId: UUID, kind: String, member: UUID?, at: Instant, phone: String? = null) {
        c.prepareStatement("INSERT INTO escalation_log (incident_id, kind, member_id, contact_phone, at) VALUES (?, ?, ?, ?, ?)")
            .apply { setObject(1, incidentId); setString(2, kind); setObject(3, member); setString(4, phone); setTimestamp(5, at.ts()) }.executeUpdate()
    }

    /** One SMS per contact with a phone; the text never carries coordinates or the code word (design §12.3.3). */
    private fun queueContactSms(c: Connection, incidentId: UUID, now: Instant) {
        data class Row(val circle: UUID, val asset: String, val tier: String, val member: UUID, val phone: String, val lang: String)
        val rows = c.prepareStatement(
            """SELECT i.circle_id, a.name, i.tier, cm.member_id, cm.phone, m.lang FROM incidents i
               JOIN assets a ON a.id = i.asset_id
               JOIN circle_members cm ON cm.circle_id = i.circle_id AND cm.role = 'contact' AND cm.phone IS NOT NULL
               JOIN members m ON m.id = cm.member_id
               WHERE i.id = ?""",
        ).apply { setObject(1, incidentId) }.executeQuery().toList {
            Row(it.getObject(1, UUID::class.java), it.getString(2), it.getString(3), it.getObject(4, UUID::class.java), it.getString(5), it.getString(6))
        }
        rows.forEach { r ->
            c.prepareStatement(
                "INSERT INTO outbound_sms (circle_id, incident_id, member_id, to_phone, body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).apply {
                setObject(1, r.circle); setObject(2, incidentId); setObject(3, r.member); setString(4, r.phone)
                setString(5, SmsText.alert(r.lang, r.asset, r.tier, SmsText.ref(incidentId))); setTimestamp(6, now.ts())
            }.executeUpdate()
            logRow(c, incidentId, "sms_queued", r.member, now, r.phone)
        }
    }

    /** Re-alerts [members] on every token, except tokens the asset's own sources registered (the carrier's phone). */
    private fun push(incidentId: UUID, members: List<UUID>) {
        data class Target(val member: UUID, val token: String)
        val (message, targets) = db.connection.use { c ->
            val msg = c.prepareStatement("SELECT a.name, i.tier, i.rule, i.asset_id FROM incidents i JOIN assets a ON a.id = i.asset_id WHERE i.id = ?")
                .apply { setObject(1, incidentId) }.executeQuery().use { it.next(); AlertMessage("incident", incidentId, it.getString(2), it.getString(1), it.getString(3)) to it.getObject(4, UUID::class.java) }
            val targets = c.prepareStatement(
                """SELECT pt.member_id, pt.token FROM push_tokens pt WHERE pt.member_id = ANY(?)
                   AND pt.token NOT IN (SELECT push_token FROM sources WHERE asset_id = ? AND push_token IS NOT NULL)""",
            ).apply { setArray(1, c.createArrayOf("uuid", members.toTypedArray())); setObject(2, msg.second) }
                .executeQuery().toList { Target(it.getObject(1, UUID::class.java), it.getString(2)) }
            msg.first to targets
        }
        val results = runBlocking(Dispatchers.IO) {
            targets.map { t ->
                async {
                    t to runCatching { withTimeout(10_000) { notifier.send(PushTarget(t.member, t.token), message) } }
                        .getOrElse { SendResult.Failed(it.message ?: "send_error", false) }
                }
            }.awaitAll()
        }
        db.connection.use { c ->
            results.forEach { (t, r) ->
                c.prepareStatement("INSERT INTO deliveries (incident_id, member_id, channel, token, sent_at, error) VALUES (?, ?, 'fcm_escalation', ?, ?, ?)").apply {
                    setObject(1, incidentId); setObject(2, t.member); setString(3, t.token)
                    setTimestamp(4, if (r is SendResult.Ok) clock.instant().ts() else null); setString(5, (r as? SendResult.Failed)?.error)
                }.executeUpdate()
            }
        }
    }
}
