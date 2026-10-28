package app.ridemate.core.invariants

import app.ridemate.core.guardian.ActionKind
import app.ridemate.core.guardian.ActionRecord
import app.ridemate.core.guardian.CaptureRecord
import app.ridemate.core.guardian.DriverCard
import app.ridemate.core.guardian.EvidenceRecord
import app.ridemate.core.guardian.FeeRules
import app.ridemate.core.guardian.Guardian
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.GuardianState
import app.ridemate.core.guardian.LegRecord
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.ReminderState
import app.ridemate.core.guardian.RuleBasedFeeEstimator
import app.ridemate.core.guardian.SessionState
import app.ridemate.core.guardian.SheetBinding
import app.ridemate.core.guardian.SheetKind
import app.ridemate.core.guardian.SwitchRecord
import app.ridemate.core.guardian.TapRecord
import app.ridemate.core.guardian.TimedEffect
import app.ridemate.core.guardian.TransitionLogEntry
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.EventType
import app.ridemate.core.state.LegState
import app.ridemate.core.state.TapId
import app.ridemate.core.time.ManualClock
import app.ridemate.core.watch.Disposition
import app.ridemate.core.watch.PostAuditEntry
import app.ridemate.core.watch.PostKind
import app.ridemate.core.watch.PostOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each checker flags a crafted violation and passes the matching good case. */
class InvariantsTest {
    private val t1 = TapId("t1")

    private fun codes(violations: List<Violation>) = violations.map { it.code }

    private fun leg(id: String, state: RideState, provider: Provider = Provider.UBER) =
        LegRecord(LegState(legId = id, provider = provider, state = state))

    @Test
    fun `I1 - actions need a recorded tap that came first, and an ONDC confirm needs the book tap`() {
        val state = GuardianState(taps = mutableListOf(TapRecord(t1, 100)))
        state.actions += ActionRecord(ActionKind.CANCEL_CHAIN_START, "a", t1, 100, RideState.ASSIGNED, 0)
        assertTrue(Invariants.checkI1(state, emptyList()).isEmpty())
        state.actions += ActionRecord(ActionKind.CANCEL_GUIDE, "b", null, 120, RideState.CANCEL_GUIDED, 0)
        state.actions += ActionRecord(ActionKind.SWITCH, "c", TapId("ghost"), 130, RideState.ASSIGNED, 0)
        state.actions += ActionRecord(ActionKind.BOOKED_HERE, "d", t1, 50, RideState.BOOK_REQUESTED, 0)
        state.actions += ActionRecord(ActionKind.DISMISS, "e", null, 140, RideState.CANCEL_GUIDED, 0)
        val confirm = TransitionLogEntry(150, "n", EventType.PROTOCOL, RideState.INIT, RideState.CONFIRMED, 0, 0, emptyList())
        assertEquals(
            listOf(ViolationCode.ACTION_WITHOUT_TAP, ViolationCode.TAP_NOT_RECORDED, ViolationCode.TAP_AFTER_ACTION, ViolationCode.ONDC_CONFIRM_WITHOUT_BOOK_TAP),
            codes(Invariants.checkI1(state, listOf(confirm))),
        )
    }

    @Test
    fun `I2 - an ETA or distance needs an admissible source`() {
        fun card(eta: Int?, etaSource: EtaSource, dist: Int? = null, distSource: EtaSource = EtaSource.NONE) =
            DriverCard("a", Provider.OLA, 0, eta, etaSource, dist, distSource)
        val ok = listOf(card(4, EtaSource.TEMPLATE, 900, EtaSource.VERIFIED), card(null, EtaSource.NONE))
        val sheet = GuardianEffect.DriverSheet("s", UiKey.SHEET_CANCEL_OTHERS, ok, emptyList(), emptyList())
        assertTrue(Invariants.checkI2(listOf(TimedEffect(0, sheet))).isEmpty())
        val bad = GuardianEffect.BetterRideSheet(
            "s", UiKey.SHEET_BETTER_RIDE, card(4, EtaSource.NONE), card(2, EtaSource.TEMPLATE, 300, EtaSource.NONE),
            app.ridemate.core.guardian.FeeNote(app.ridemate.core.guardian.FeeNoteKind.MAY_APPLY), app.ridemate.core.guardian.FeeNote(app.ridemate.core.guardian.FeeNoteKind.MAY_APPLY), emptyList(),
        )
        assertEquals(listOf("eta", "distance"), Invariants.checkI2(listOf(TimedEffect(0, bad))).map { it.ref })
    }

    @Test
    fun `I3 - a confirmed cancel needs rung 1 to 3 evidence`() {
        val state = GuardianState()
        state.legs["ok"] = leg("ok", RideState.CANCEL_CONFIRMED).apply { leg = leg.copy(confirmedBy = EvidenceRung.TEMPLATE) }
        state.evidence += EvidenceRecord("ok", EvidenceRung.TEMPLATE, 0)
        assertTrue(Invariants.checkI3(state).isEmpty())
        state.legs["noEvidence"] = leg("noEvidence", RideState.CANCEL_CONFIRMED).apply { leg = leg.copy(confirmedBy = EvidenceRung.TEMPLATE) }
        state.legs["user"] = leg("user", RideState.CANCEL_CONFIRMED).apply { leg = leg.copy(confirmedBy = EvidenceRung.USER) }
        state.evidence += EvidenceRecord("user", EvidenceRung.USER, 0)
        assertEquals(listOf("noEvidence", "user"), Invariants.checkI3(state).map { it.legId })
    }

    @Test
    fun `I4 - a switch needs a better-ride sheet bound to the current generations`() {
        val state = GuardianState()
        val bound = listOf(SheetBinding("a", 0), SheetBinding("b", 2))
        state.actions += ActionRecord(ActionKind.SWITCH, "b", t1, 0, RideState.ASSIGNED, 2)
        state.switches += SwitchRecord("s1", SheetKind.BETTER_RIDE, "a", "b", bound, bound, t1, 0)
        assertTrue(Invariants.checkI4(state).isEmpty())
        val t2 = TapId("t2")
        val t3 = TapId("t3")
        val t4 = TapId("t4")
        state.actions += ActionRecord(ActionKind.SWITCH, "b", t2, 0, RideState.ASSIGNED, 3)
        state.switches += SwitchRecord("s2", SheetKind.BETTER_RIDE, "a", "b", bound, listOf(SheetBinding("a", 0), SheetBinding("b", 3)), t2, 0)
        state.actions += ActionRecord(ActionKind.SWITCH, "b", t3, 0, RideState.ASSIGNED, 2)
        state.switches += SwitchRecord("s3", SheetKind.CANCEL_OTHERS, "a", "b", bound, bound, t3, 0)
        state.actions += ActionRecord(ActionKind.SWITCH, "b", t4, 0, RideState.ASSIGNED, 2)
        assertEquals(
            listOf(ViolationCode.SWITCH_ON_STALE_GENERATION, ViolationCode.SWITCH_FROM_WRONG_SHEET, ViolationCode.SWITCH_WITHOUT_RECORD),
            codes(Invariants.checkI4(state)),
        )
    }

    private data class Leaky(val legId: String, val message: String, val nested: Inner, val notes: List<String>, val legIds: List<String>)
    private data class Inner(val count: Int, val label: String)

    @Test
    fun `I5 - UI values carry ids, enums and numbers, never free text`() {
        val effects = listOf(
            GuardianEffect.SameDriverNotice(listOf("a", "b")),
            GuardianEffect.OpenApp("a", Provider.UBER, app.ridemate.core.guardian.OpenPurpose.CANCEL, t1),
            GuardianEffect.Banner(null, null, UiKey.BANNER_ALL_CANCELS_DONE),
        )
        assertTrue(Invariants.checkI5(effects.map { TimedEffect(0, it) }).isEmpty())
        val leaky = Leaky("a", "Your captain Ravi (98765…) is here", Inner(1, "free text"), listOf("x"), listOf("a"))
        assertEquals(
            listOf("Leaky.message", "Leaky.nested.label", "Leaky.notes[0]"),
            Invariants.checkI5Values(listOf(leaky)).map { it.ref },
        )
    }

    @Test
    fun `I6 - a processed frame must follow its tap within the window`() {
        val state = GuardianState()
        state.captures += CaptureRecord("a", t1, tapAtElapsedMs = 1_000, frameAtElapsedMs = 2_500, demo = false, accepted = true)
        state.captures += CaptureRecord("a", t1, tapAtElapsedMs = 1_000, frameAtElapsedMs = 9_000, demo = false, accepted = false)
        state.captures += CaptureRecord("a", t1, tapAtElapsedMs = null, frameAtElapsedMs = 9_000, demo = true, accepted = true)
        assertTrue(Invariants.checkI6(state, 2_000).isEmpty())
        state.captures += CaptureRecord("b", t1, tapAtElapsedMs = 1_000, frameAtElapsedMs = 3_500, demo = false, accepted = true)
        state.captures += CaptureRecord("c", t1, tapAtElapsedMs = null, frameAtElapsedMs = 3_500, demo = false, accepted = true)
        state.captures += CaptureRecord("d", t1, tapAtElapsedMs = 5_000, frameAtElapsedMs = 3_500, demo = false, accepted = true)
        assertEquals(
            listOf(ViolationCode.FRAME_OUTSIDE_TAP_WINDOW, ViolationCode.FRAME_WITHOUT_TAP, ViolationCode.FRAME_OUTSIDE_TAP_WINDOW),
            codes(Invariants.checkI6(state, 2_000)),
        )
    }

    private fun audit(
        seq: Long,
        kind: PostKind = PostKind.TEMPLATE_HIT,
        arrived: Long = 0,
        booked: Boolean = true,
        state: RideState = RideState.SEARCHING,
        pkg: String = Provider.UBER.packageName,
    ) = PostAuditEntry(seq, pkg, "k", arrived, kind, null, false, false, "leg", state, booked)

    @Test
    fun `I7 - other packages are never read and never audited`() {
        val allow = Provider.RIDE_APP_PACKAGES
        assertTrue(Invariants.checkI7(listOf("com.whatsapp" to false, "com.ubercab" to true), listOf(audit(1)), allow).isEmpty())
        assertEquals(
            listOf(ViolationCode.NON_ALLOWLISTED_POST_READ, ViolationCode.NON_ALLOWLISTED_POST_AUDITED),
            codes(Invariants.checkI7(listOf("com.whatsapp" to true), listOf(audit(1, pkg = "com.bank")), allow)),
        )
    }

    @Test
    fun `I8 - an extra leg without a scheduled reminder, or a missing follow-up check, is flagged`() {
        val clock = ManualClock()
        val fees = RuleBasedFeeEstimator(FeeRules())
        val state = GuardianState(session = SessionState("s", demo = false, keepSearching = false, startedAtElapsedMs = 0))
        state.legs["x"] = leg("x", RideState.CANCEL_GUIDED).apply { role = LegRole.EXTRA }
        state.legs["y"] = leg("y", RideState.USER_SAYS_CANCELLED, Provider.OLA).apply { role = LegRole.EXTRA }
        val broken = Guardian(clock, fees, initialState = state)
        assertEquals(
            listOf(ViolationCode.EXTRA_LEG_WITHOUT_REMINDER, ViolationCode.EXTRA_LEG_WITHOUT_REMINDER, ViolationCode.FOLLOW_UP_NOT_SCHEDULED),
            codes(Invariants.checkI8(broken)),
        )
        state.reminders = ReminderState(0)
        state.timers += app.ridemate.core.guardian.TimerRecord(app.ridemate.core.guardian.TimerKind.FOLLOW_UP_CHECK, "y", 0, 60_000)
        assertTrue(Invariants.checkI8(Guardian(clock, fees, initialState = state)).isEmpty())
        state.legs.getValue("x").dismissed = true
        state.legs.getValue("y").leg = state.legs.getValue("y").leg.copy(state = RideState.CANCEL_CONFIRMED)
        state.reminders = null
        assertTrue("dismissed and confirmed legs need no reminders", Invariants.checkI8(Guardian(clock, fees, initialState = state)).isEmpty())
    }

    @Test
    fun `I9 - every covered post resolves in time, directly or through the post that covered it`() {
        val onTime = audit(1).apply { resolve(PostOutcome.EVENT, 2_000, EventType.SEARCHING, disposition = Disposition.TRANSITION) }
        val dup = audit(2, arrived = 5_000).apply { resolve(PostOutcome.DUPLICATE_DROPPED, 5_000) }
        val missOnTime = audit(3, PostKind.RULE_MISS, arrived = 10_000).apply { resolve(PostOutcome.EVENT, 23_000) }
        val pendingYoung = audit(4, PostKind.RULE_MISS, arrived = 25_000)
        val notBooked = audit(5, booked = false, arrived = 0)
        val terminal = audit(6, state = RideState.SEARCH_EXPIRED)
        val cover = audit(8, PostKind.RULE_MISS, arrived = 21_000).apply { resolve(PostOutcome.EVENT, 26_000) }
        val coalesced = audit(7, PostKind.RULE_MISS, arrived = 20_000).apply { resolve(PostOutcome.COALESCED, 26_000, coveredBySeq = 8) }
        val good = listOf(onTime, dup, missOnTime, pendingYoung, notBooked, terminal, coalesced, cover)
        assertTrue(Invariants.checkI9(good, nowElapsedMs = 30_000).isEmpty())

        val late = audit(10, arrived = 0).apply { resolve(PostOutcome.EVENT, 3_001) }
        val lateMiss = audit(11, PostKind.RULE_MISS, arrived = 0).apply { resolve(PostOutcome.EVENT, Invariants.MISS_BOUND_MS + 1) }
        val stuck = audit(12, PostKind.RULE_MISS, arrived = 0)
        val orphan = audit(13, PostKind.RULE_MISS, arrived = 0).apply { resolve(PostOutcome.COALESCED, 1_000, coveredBySeq = 99) }
        val dropped = audit(14, arrived = 0).apply { resolve(PostOutcome.NO_ACTIVE_LEG, 0) }
        assertEquals(
            listOf(
                ViolationCode.POST_RESOLVED_LATE, ViolationCode.POST_RESOLVED_LATE, ViolationCode.POST_UNRESOLVED,
                ViolationCode.POST_DROPPED_UNEXPLAINED, ViolationCode.POST_DROPPED_UNEXPLAINED,
            ),
            codes(Invariants.checkI9(listOf(late, lateMiss, stuck, orphan, dropped), nowElapsedMs = 30_000)),
        )
    }

    @Test
    fun `I10 - no chain and no ride banner for a leg without a driver`() {
        val state = GuardianState()
        state.actions += ActionRecord(ActionKind.CANCEL_CHAIN_START, "a", t1, 0, RideState.ARRIVING, 1)
        val banner = GuardianEffect.RideBanner(DriverCard("a", Provider.UBER, 1, null, EtaSource.NONE, null, EtaSource.NONE), RideState.ASSIGNED)
        assertTrue(Invariants.checkI10(state, listOf(TimedEffect(0, banner))).isEmpty())
        state.actions += ActionRecord(ActionKind.CANCEL_CHAIN_START, "b", t1, 0, RideState.MAYBE_CHANGED, 1)
        val bad = banner.copy(state = RideState.DRIVER_CANCELLED)
        assertEquals(
            listOf(ViolationCode.CHAIN_FOR_LEG_WITHOUT_DRIVER, ViolationCode.RIDE_BANNER_WITHOUT_DRIVER),
            codes(Invariants.checkI10(state, listOf(TimedEffect(0, bad)))),
        )
    }

    @Test
    fun `violations crash debug builds and are logged in release`() {
        val report = InvariantReport(listOf(Violation(Invariant.I3, ViolationCode.CONFIRMED_WITHOUT_EVIDENCE, "a")))
        assertThrows(InvariantViolationException::class.java) { report.enforce(InvariantMode.CRASH) }
        val logged = mutableListOf<Violation>()
        report.enforce(InvariantMode.LOG) { logged += it }
        assertEquals(report.violations, logged)
        InvariantReport(emptyList()).enforce(InvariantMode.CRASH)
        assertEquals(report.violations, (report + InvariantReport(emptyList())).of(Invariant.I3))
    }
}
