package dz.hirz.server.escalation

import dz.hirz.shared.api.Tier
import dz.hirz.shared.rules.RuleConfig
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

enum class Stage { GUARDIANS, CONTACTS, EVERYONE_UNAVAILABLE, CLAIMED, CLOSED }
enum class WindowFrom { DELIVERED, SENT }

/**
 * Who owns an incident and when the circle widens (design §5.3–5.6). The P1 fan-out already told everyone;
 * the ladder only decides ownership and re-alerts, so it never narrows who may know.
 */
@Serializable
data class LadderState(
    val tier: Tier,
    val stage: Stage,
    val order: List<@Contextual UUID>,
    val index: Int,
    @Contextual val stageStart: Instant,
    @Contextual val windowStart: Instant?,
    val windowFrom: WindowFrom?,
    @Contextual val nextCheck: Instant,
    @Contextual val owner: UUID? = null,
    val delivered: Set<@Contextual UUID> = emptySet(),
) {
    val current: UUID? get() = order.getOrNull(index)
}

sealed interface LadderEvent {
    data class Delivered(val member: UUID, val at: Instant) : LadderEvent
    data class Tick(val now: Instant) : LadderEvent
    data class Claimed(val member: UUID, val at: Instant) : LadderEvent
    data class Contested(val member: UUID, val at: Instant) : LadderEvent
    data class Closed(val at: Instant) : LadderEvent
    /** Every contact who was told declined by SMS: nobody is left before retrying everyone. */
    data class ContactsExhausted(val at: Instant) : LadderEvent
}

sealed interface LadderAction {
    data class NotifyMember(val member: UUID) : LadderAction
    data object NotifyContacts : LadderAction
    data object RetryAll : LadderAction
    data class Log(val kind: String, val member: UUID?) : LadderAction
}

object Ladder {
    private fun windowMs(tier: Tier, cfg: RuleConfig) = if (tier == Tier.P0) cfg.ackWindowP0Ms else cfg.ackWindowP1Ms

    fun open(tier: Tier, order: List<UUID>, now: Instant, cfg: RuleConfig) =
        LadderState(tier, Stage.GUARDIANS, order, 0, now, null, null, now.plusMillis(cfg.ackFallbackMs))

    fun next(st: LadderState, ev: LadderEvent, cfg: RuleConfig): Pair<LadderState, List<LadderAction>> {
        if (st.stage == Stage.CLOSED) return st to emptyList()
        return when (ev) {
            is LadderEvent.Closed -> st.copy(stage = Stage.CLOSED) to listOf(LadderAction.Log("closed", null))
            is LadderEvent.Claimed ->
                if (st.stage == Stage.CLAIMED) st to emptyList()
                else st.copy(stage = Stage.CLAIMED, owner = ev.member) to listOf(LadderAction.Log("claimed", ev.member))
            is LadderEvent.Contested -> contest(st, ev, cfg)
            is LadderEvent.Delivered -> delivered(st, ev, cfg)
            is LadderEvent.Tick -> tick(st, ev.now, cfg)
            is LadderEvent.ContactsExhausted ->
                if (st.stage != Stage.CONTACTS) st to emptyList()
                else st.copy(stage = Stage.EVERYONE_UNAVAILABLE, nextCheck = ev.at.plusMillis(cfg.escalationRetryMs)) to
                    listOf(LadderAction.RetryAll, LadderAction.Log("everyone_unavailable", null))
        }
    }

    private fun delivered(st: LadderState, ev: LadderEvent.Delivered, cfg: RuleConfig): Pair<LadderState, List<LadderAction>> {
        val s = st.copy(delivered = st.delivered + ev.member)
        if (s.stage == Stage.GUARDIANS && ev.member == s.current && s.windowStart == null) {
            return s.copy(windowStart = ev.at, windowFrom = WindowFrom.DELIVERED, nextCheck = ev.at.plusMillis(windowMs(s.tier, cfg))) to
                listOf(LadderAction.Log("window_from_delivered", ev.member))
        }
        val position = s.order.indexOf(ev.member)
        val late = position in 0 until s.index || (s.stage != Stage.GUARDIANS && s.stage != Stage.CLAIMED)
        return s to if (late) listOf(LadderAction.Log("late_delivery", ev.member)) else emptyList()
    }

    private fun tick(st: LadderState, now: Instant, cfg: RuleConfig): Pair<LadderState, List<LadderAction>> = when (st.stage) {
        Stage.GUARDIANS -> if (st.current == null) toContacts(st, now, cfg) else {
            val actions = mutableListOf<LadderAction>()
            var s = st
            if (s.windowStart == null && !now.isBefore(s.stageStart.plusMillis(cfg.ackFallbackMs))) {
                s = s.copy(windowStart = s.stageStart, windowFrom = WindowFrom.SENT, nextCheck = s.stageStart.plusMillis(windowMs(s.tier, cfg)))
                actions += LadderAction.Log("window_from_sent", s.current)
            }
            val ws = s.windowStart
            if (ws != null && !now.isBefore(ws.plusMillis(windowMs(s.tier, cfg)))) {
                val (advanced, more) = advance(s, now, cfg)
                advanced to actions + more
            } else s to actions
        }
        Stage.CONTACTS ->
            if (now.isBefore(st.nextCheck)) st to emptyList()
            else st.copy(stage = Stage.EVERYONE_UNAVAILABLE, nextCheck = now.plusMillis(cfg.escalationRetryMs)) to
                listOf(LadderAction.RetryAll, LadderAction.Log("everyone_unavailable", null))
        Stage.EVERYONE_UNAVAILABLE ->
            if (now.isBefore(st.nextCheck)) st to emptyList()
            else st.copy(nextCheck = now.plusMillis(cfg.escalationRetryMs)) to listOf(LadderAction.RetryAll, LadderAction.Log("retry_all", null))
        Stage.CLAIMED, Stage.CLOSED -> st to emptyList()
    }

    private fun startCandidate(st: LadderState, index: Int, now: Instant, cfg: RuleConfig): LadderState {
        val member = st.order[index]
        val already = member in st.delivered
        return st.copy(
            stage = Stage.GUARDIANS, index = index, stageStart = now, owner = null,
            windowStart = if (already) now else null, windowFrom = if (already) WindowFrom.DELIVERED else null,
            nextCheck = if (already) now.plusMillis(windowMs(st.tier, cfg)) else now.plusMillis(cfg.ackFallbackMs),
        )
    }

    private fun advance(st: LadderState, now: Instant, cfg: RuleConfig): Pair<LadderState, List<LadderAction>> {
        val nextIndex = st.index + 1
        if (nextIndex >= st.order.size) return toContacts(st, now, cfg).let { (s, a) -> s to listOf(LadderAction.Log("escalated", st.current)) + a }
        val s = startCandidate(st, nextIndex, now, cfg)
        return s to listOf(LadderAction.Log("escalated", st.current), LadderAction.NotifyMember(s.current!!))
    }

    private fun toContacts(st: LadderState, now: Instant, cfg: RuleConfig) =
        st.copy(stage = Stage.CONTACTS, index = st.order.size, nextCheck = now.plusMillis(cfg.escalationRetryMs)) to
            listOf(LadderAction.NotifyContacts, LadderAction.Log("contacts_notified", null))

    private fun contest(st: LadderState, ev: LadderEvent.Contested, cfg: RuleConfig): Pair<LadderState, List<LadderAction>> {
        if (st.order.isEmpty()) return st to listOf(LadderAction.Log("contested", ev.member))
        val base = st.order.indexOf(st.owner ?: st.current).coerceAtLeast(0)
        val s = startCandidate(st, (base + 1) % st.order.size, ev.at, cfg)
        return s to listOf(LadderAction.Log("contested", ev.member), LadderAction.NotifyMember(s.current!!))
    }
}
