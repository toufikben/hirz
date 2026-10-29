package dz.hirz.server.escalation

import dz.hirz.shared.api.Tier
import dz.hirz.shared.rules.RuleConfig
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LadderTest {
    private val cfg = RuleConfig.DEFAULT
    private val t0 = Instant.parse("2026-11-01T08:00:00Z")
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private fun s(n: Long) = t0.plusSeconds(n)

    private fun opened(tier: Tier = Tier.P1) = Ladder.open(tier, listOf(a, b), t0, cfg)

    @Test
    fun firstOwnerIsFirstCandidate() {
        val st = opened()
        assertEquals(Stage.GUARDIANS, st.stage)
        assertEquals(a, st.current)
    }

    @Test
    fun windowStartsAtDeliveredWhenItArrives() {
        val (st, actions) = Ladder.next(opened(), LadderEvent.Delivered(a, s(5)), cfg)
        assertEquals(s(5), st.windowStart)
        assertEquals(WindowFrom.DELIVERED, st.windowFrom)
        assertEquals(s(5 + 180), st.nextCheck)
        assertTrue(actions.contains(LadderAction.Log("window_from_delivered", a)))
    }

    @Test
    fun windowFallsBackToSentAfter20sAndLogsIt() {
        val (st, actions) = Ladder.next(opened(), LadderEvent.Tick(s(20)), cfg)
        assertEquals(t0, st.windowStart)
        assertEquals(WindowFrom.SENT, st.windowFrom)
        assertTrue(actions.contains(LadderAction.Log("window_from_sent", a)))
    }

    @Test
    fun noClaimWithinP0WindowMovesToNextCandidate() {
        var st = Ladder.open(Tier.P0, listOf(a, b), t0, cfg)
        st = Ladder.next(st, LadderEvent.Delivered(a, s(4)), cfg).first
        val (before, none) = Ladder.next(st, LadderEvent.Tick(s(63)), cfg)
        assertEquals(a, before.current)
        assertTrue(none.none { it is LadderAction.NotifyMember })
        val (after, actions) = Ladder.next(st, LadderEvent.Tick(s(64)), cfg)
        assertEquals(b, after.current)
        assertTrue(actions.contains(LadderAction.NotifyMember(b)))
    }

    @Test
    fun alreadyDeliveredCandidateStartsItsWindowAtOnce() {
        var st = Ladder.open(Tier.P0, listOf(a, b), t0, cfg)
        st = Ladder.next(st, LadderEvent.Delivered(b, s(2)), cfg).first // b got the fan-out push early
        st = Ladder.next(st, LadderEvent.Tick(s(20)), cfg).first        // a: fallback to sent
        val (after, _) = Ladder.next(st, LadderEvent.Tick(s(60)), cfg)  // a times out
        assertEquals(b, after.current)
        assertEquals(s(60), after.windowStart)
        assertEquals(WindowFrom.DELIVERED, after.windowFrom)
    }

    @Test
    fun afterLastGuardianContactsAreNotified() {
        var st = Ladder.open(Tier.P0, listOf(a), t0, cfg)
        st = Ladder.next(st, LadderEvent.Tick(s(20)), cfg).first
        val (after, actions) = Ladder.next(st, LadderEvent.Tick(s(60)), cfg)
        assertEquals(Stage.CONTACTS, after.stage)
        assertTrue(actions.contains(LadderAction.NotifyContacts))
    }

    @Test
    fun everyoneUnavailableRetriesEvery120s() {
        var st = Ladder.open(Tier.P0, listOf(a), t0, cfg)
        st = Ladder.next(st, LadderEvent.Tick(s(20)), cfg).first
        st = Ladder.next(st, LadderEvent.Tick(s(60)), cfg).first
        val (unavailable, first) = Ladder.next(st, LadderEvent.Tick(s(180)), cfg)
        assertEquals(Stage.EVERYONE_UNAVAILABLE, unavailable.stage)
        assertTrue(first.contains(LadderAction.RetryAll))
        val (_, early) = Ladder.next(unavailable, LadderEvent.Tick(s(299)), cfg)
        assertTrue(early.none { it == LadderAction.RetryAll })
        val (_, again) = Ladder.next(unavailable, LadderEvent.Tick(s(300)), cfg)
        assertTrue(again.contains(LadderAction.RetryAll))
    }

    @Test
    fun claimStopsTheLadder() {
        val (st, _) = Ladder.next(opened(), LadderEvent.Claimed(b, s(3)), cfg)
        assertEquals(Stage.CLAIMED, st.stage)
        assertEquals(b, st.owner)
        val (later, actions) = Ladder.next(st, LadderEvent.Tick(s(10_000)), cfg)
        assertEquals(st, later)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun contestRestartsAtNextCandidate() {
        val claimed = Ladder.next(opened(), LadderEvent.Claimed(a, s(3)), cfg).first
        val (st, actions) = Ladder.next(claimed, LadderEvent.Contested(b, s(30)), cfg)
        assertEquals(Stage.GUARDIANS, st.stage)
        assertEquals(b, st.current)
        assertEquals(null, st.owner)
        assertTrue(actions.contains(LadderAction.NotifyMember(b)))
    }

    @Test
    fun lateReceiptAfterEscalationIsRecorded() {
        var st = Ladder.open(Tier.P0, listOf(a, b), t0, cfg)
        st = Ladder.next(st, LadderEvent.Tick(s(20)), cfg).first
        st = Ladder.next(st, LadderEvent.Tick(s(60)), cfg).first // now b
        val (after, actions) = Ladder.next(st, LadderEvent.Delivered(a, s(61)), cfg)
        assertEquals(b, after.current)
        assertTrue(actions.contains(LadderAction.Log("late_delivery", a)))
    }

    @Test
    fun closedIgnoresEverything() {
        val closed = Ladder.next(opened(), LadderEvent.Closed(s(5)), cfg).first
        assertEquals(Stage.CLOSED, closed.stage)
        listOf(LadderEvent.Tick(s(999)), LadderEvent.Delivered(a, s(6)), LadderEvent.Claimed(a, s(7))).forEach {
            val (st, actions) = Ladder.next(closed, it, cfg)
            assertEquals(closed, st)
            assertTrue(actions.isEmpty())
        }
    }
}
