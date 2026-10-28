@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.core.replay

import app.ridemate.core.guardian.FeeNoteKind
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.SheetChoice
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.invariants.Invariants
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.replay.Fixtures.OLA
import app.ridemate.core.replay.Fixtures.RAPIDO
import app.ridemate.core.replay.Fixtures.Text
import app.ridemate.core.replay.Fixtures.UBER
import app.ridemate.core.state.Answer
import app.ridemate.core.state.UnknownReason
import app.ridemate.core.watch.ClassifierLabel
import app.ridemate.core.watch.PostAuditEntry
import app.ridemate.core.watch.PostKind
import app.ridemate.core.watch.PostOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nine mandatory replay scenarios of §4.5.11, end to end through RideWatch, the statechart and the guardian on
 * virtual time, with the timing bounds of I9 (rule hits and redacted posts ≤ 3 s; misses ≤ 3 s + the 10 s deadline).
 */
class ReplayScenariosTest {

    private fun assertWithin(entry: PostAuditEntry, boundMs: Long) {
        val resolved = entry.resolvedAtElapsedMs
        assertTrue("post #${entry.seq} unresolved: $entry", resolved != null)
        assertTrue("post #${entry.seq} took ${resolved!! - entry.arrivedAtElapsedMs} ms", resolved - entry.arrivedAtElapsedMs <= boundMs)
    }

    private fun assertClean(h: ReplayHarness) {
        assertEquals(emptyList<Any>(), Invariants.checkI9(h.pipeline.audit, h.now))
        assertEquals(emptyList<Any>(), Invariants.checkGuardian(h.guardian).violations)
    }

    @Test
    fun `(a) assigned, driver cancelled, assigned again on the same key with no searching post`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoSearching)
        h.advance(40_000)

        val first = h.post(RAPIDO, "r1", Text.rapidoAssigned(eta = 4, plate = "AS01AB1234"))
        assertWithin(first, 3_000)
        assertEquals(RideState.ASSIGNED, h.state(Provider.RAPIDO))
        assertEquals(0, h.generation(Provider.RAPIDO))
        val sheet1 = h.effectsOf<GuardianEffect.DriverSheet>().single()
        assertEquals(UiKey.SHEET_CANCEL_OTHERS, sheet1.key)
        val card = sheet1.drivers.single()
        assertEquals(0, card.generation)
        assertEquals(4, card.etaMin)
        assertEquals(EtaSource.TEMPLATE, card.etaSource)
        assertEquals(1200, card.distanceMeters)
        assertEquals(FeeNoteKind.FREE_WHILE_SEARCHING, sheet1.others.single { it.provider == Provider.UBER }.fee.kind)

        h.advance(20_000)
        val mark = h.mark()
        val cancel = h.post(RAPIDO, "r1", Text.rapidoDriverCancelled)
        assertWithin(cancel, 3_000)
        assertEquals(RideState.DRIVER_CANCELLED, h.state(Provider.RAPIDO))
        assertEquals(1, h.generation(Provider.RAPIDO))
        val withdrawn = h.effectsOf<GuardianEffect.SheetWithdrawn>(mark).single()
        assertEquals(sheet1.sheetId, withdrawn.sheetId)
        assertEquals(UiKey.SHEET_WITHDRAWN_DRIVER_CANCELLED, withdrawn.key)

        h.advance(15_000)
        val mark2 = h.mark()
        val second = h.post(RAPIDO, "r1", Text.rapidoAssigned(eta = 6, plate = "AS02XY9876"))
        assertWithin(second, 3_000)
        assertEquals(RideState.ASSIGNED, h.state(Provider.RAPIDO))
        assertEquals(1, h.generation(Provider.RAPIDO))
        val sheet2 = h.effectsOf<GuardianEffect.DriverSheet>(mark2).single()
        assertEquals(1, sheet2.drivers.single().generation)
        assertEquals(6, sheet2.drivers.single().etaMin)

        // A tap on the withdrawn sheet is refused (I10); the live sheet starts the chain.
        val stale = h.act(UserAction.AnswerSheet(sheet1.sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        assertTrue(stale.any { it is GuardianEffect.AnswerRejected })
        assertEquals(RideState.SEARCHING, h.state(Provider.UBER))
        h.act(UserAction.AnswerSheet(sheet2.sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertEquals(LegRole.KEPT, h.record(Provider.RAPIDO).role)
        assertClean(h)
    }

    @Test
    fun `(a) on the kept leg - finding another captain, then the new driver`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned(plate = "AS01AB1234"))
        val sheet = h.effectsOf<GuardianEffect.DriverSheet>().single()
        h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        val mark = h.mark()
        h.post(RAPIDO, "r1", Text.rapidoDriverCancelled)
        val banner = h.effectsOf<GuardianEffect.Banner>(mark).single()
        assertEquals(UiKey.BANNER_FINDING_ANOTHER_CAPTAIN, banner.key)
        assertEquals("the chain keeps going: the booking is still alive", RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        val mark2 = h.mark()
        h.post(RAPIDO, "r1", Text.rapidoAssigned(eta = 7, plate = "AS02XY9876"))
        val ride = h.effectsOf<GuardianEffect.RideBanner>(mark2).single()
        assertEquals(1, ride.driver.generation)
        assertEquals(RideState.ASSIGNED, ride.state)
        assertClean(h)
    }

    @Test
    fun `(b) assigned, then searching with no cancel post, then assigned`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.OLA, Provider.RAPIDO))
        h.book(Provider.OLA, Provider.RAPIDO)
        h.post(OLA, "o1", Text.olaSearching)
        h.post(RAPIDO, "r1", Text.rapidoSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val sheet1 = h.effectsOf<GuardianEffect.DriverSheet>().single()
        h.advance(30_000)
        val mark = h.mark()
        val research = h.post(RAPIDO, "r1", Text.rapidoSearching)
        assertWithin(research, 3_000)
        assertEquals(PostOutcome.EVENT, research.outcome)
        assertEquals(RideState.SEARCHING, h.state(Provider.RAPIDO))
        assertEquals(1, h.generation(Provider.RAPIDO))
        assertEquals(UiKey.SHEET_WITHDRAWN_SEARCHING_AGAIN, h.effectsOf<GuardianEffect.SheetWithdrawn>(mark).single { it.sheetId == sheet1.sheetId }.key)
        h.advance(30_000)
        val reassigned = h.post(RAPIDO, "r1", Text.rapidoAssigned())
        assertWithin(reassigned, 3_000)
        assertEquals(RideState.ASSIGNED, h.state(Provider.RAPIDO))
        assertEquals(1, h.generation(Provider.RAPIDO))
        assertEquals(1, h.effectsOf<GuardianEffect.DriverSheet>(mark).single().drivers.single().generation)
        assertClean(h)
    }

    @Test
    fun `(c) a reworded re-search on DRIVER_CANCELLED with the backend down gives exactly one prompt within 3 s`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.OLA), classifier = FakeClassifier(FakeClassifier.Mode.DOWN))
        h.book(Provider.UBER, Provider.OLA)
        h.post(OLA, "o1", Text.olaSearching)
        h.post(UBER, "u1", Text.uberAssigned())
        h.post(UBER, "u1", Text.uberDriverCancelled)
        assertEquals(RideState.DRIVER_CANCELLED, h.state(Provider.UBER))
        h.advance(5_000)
        val mark = h.mark()
        val reworded = h.post(UBER, "u1", "Uber", "Hold on, we're matching you with someone new (0:05)")
        assertWithin(reworded, 3_000)
        assertEquals(UnknownReason.CLASSIFIER_FAILED, reworded.unknownReason)
        assertEquals(RideState.MAYBE_NEW_DRIVER, h.state(Provider.UBER))
        // The countdown keeps updating and the wording changes again: still one prompt.
        for (s in listOf(7, 9, 11)) {
            h.advance(2_000)
            assertEquals(PostOutcome.DUPLICATE_DROPPED, h.post(UBER, "u1", "Uber", "Hold on, we're matching you with someone new (0:%02d)".format(s)).outcome)
        }
        h.advance(2_000)
        assertEquals(PostOutcome.EVENT, h.post(UBER, "u1", "Uber", "Still matching you, almost there").outcome)
        val prompts = h.timedOf<GuardianEffect.MaybePrompt>(mark)
        assertEquals(1, prompts.size)
        assertEquals(UiKey.PROMPT_MAYBE_NEW_DRIVER, prompts.single().second.key)
        assertTrue(prompts.single().first - reworded.arrivedAtElapsedMs <= 3_000)
        assertEquals(listOf(UiAction.YES, UiAction.NO_IT_ENDED, UiAction.CHECK_SCREEN), prompts.single().second.actions)
        assertClean(h)
    }

    @Test
    fun `(d) an unanswered MAYBE_CHANGED, then the exact cancelled template, gives a loud alert`() = runTest {
        val classifier = FakeClassifier().apply { rules += "traffic" to ClassifierLabel.OTHER }
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO), classifier = classifier)
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.act(UserAction.AnswerSheet(h.effectsOf<GuardianEffect.DriverSheet>().single().sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))

        val miss = h.post(RAPIDO, "r1", "Rapido", "Your captain is stuck in traffic")
        h.advance(1_500)
        assertWithin(miss, 3_000 + 10_000)
        assertEquals(RideState.MAYBE_CHANGED, h.state(Provider.RAPIDO))
        val prompt = h.effectsOf<GuardianEffect.MaybePrompt>().single()
        assertEquals(UiKey.PROMPT_MAYBE_CHANGED, prompt.key)

        h.advance(30_000)
        val mark = h.mark()
        val cancelled = h.post(RAPIDO, "r1", Text.rapidoCancelled)
        assertWithin(cancelled, 3_000)
        assertEquals(RideState.PROVIDER_CANCELLED, h.state(Provider.RAPIDO))
        assertEquals(UiKey.PROMPT_WITHDRAWN_PROVIDER_CANCELLED, h.effectsOf<GuardianEffect.PromptWithdrawn>(mark).single().key)
        val alert = h.timedOf<GuardianEffect.LoudAlert>(mark).single()
        assertEquals(UiKey.ALERT_KEPT_LEG_LOST, alert.second.key)
        assertEquals(listOf(UiAction.COMPARE_AGAIN, UiAction.OPEN_APP), alert.second.actions)
        assertTrue(alert.first - cancelled.arrivedAtElapsedMs <= 3_000)
        // The booking the chain had not cancelled yet is the user's again.
        assertEquals(RideState.SEARCHING, h.state(Provider.UBER))
        assertEquals(LegRole.NONE, h.record(Provider.UBER).role)

        val again = h.act(UserAction.CompareAgain(h.tap()))
        assertEquals(GuardianEffect.CompareAgain(ReplayHarness.SESSION), again.single { it is GuardianEffect.CompareAgain })
        assertEquals(RideState.SELECTED, h.guardian.state.legs.values.single { it.provider == Provider.RAPIDO }.leg.state)
        assertClean(h)
    }

    @Test
    fun `(e) an extra leg going ASSIGNED then ARRIVING keeps its reminders`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.OLA))
        h.book(Provider.UBER, Provider.OLA)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(OLA, "o1", Text.olaAssigned())
        val yesAt = h.now
        h.act(UserAction.AnswerSheet(h.effectsOf<GuardianEffect.DriverSheet>().single().sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))

        h.advance(10_000)
        val mark = h.mark()
        h.post(UBER, "u1", Text.uberAssigned(eta = 4))
        val card = h.effectsOf<GuardianEffect.CancelCard>(mark).single()
        assertEquals(RideState.ASSIGNED, card.providerState)
        assertEquals(FeeNoteKind.FREE_WINDOW, card.fee.kind)
        h.advance(30_000)
        h.post(UBER, "u1", "Your driver is arriving now", "Meet at the pickup point")
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertEquals(RideState.ARRIVING, h.record(Provider.UBER).leg.providerState)

        h.advance(600_000)
        val reminders = h.timedOf<GuardianEffect.Reminder>()
        assertEquals(listOf(30L, 60L, 120L, 240L, 360L, 480L, 600L).map { yesAt + it * 1000 }, reminders.map { it.first })
        assertTrue(reminders.all { (_, r) -> r.legs.single().legId == h.legId(Provider.UBER) && r.headsUp && r.bubblePulse })
        assertTrue(h.guardian.needsForegroundService)
        assertClean(h)
    }

    @Test
    fun `(f) BOOK_REQUESTED goes straight to ASSIGNED with no searching post`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.OLA))
        h.book(Provider.UBER, Provider.OLA)
        assertEquals(RideState.BOOK_REQUESTED, h.state(Provider.OLA))
        h.advance(45_000)
        val assigned = h.post(OLA, "o1", Text.olaAssigned(eta = 5))
        assertWithin(assigned, 3_000)
        assertEquals(RideState.ASSIGNED, h.state(Provider.OLA))
        val sheet = h.effectsOf<GuardianEffect.DriverSheet>().single()
        assertEquals(5, sheet.drivers.single().etaMin)
        assertEquals(Provider.UBER, sheet.others.single().provider)
        assertClean(h)
    }

    @Test
    fun `(g) the ongoing post updates every 2 s and the assignment 1 s after an update lands within 3 s`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        val updates = (0 until 15).map { i ->
            if (i > 0) h.advance(2_000)
            h.post(RAPIDO, "r1", "Looking for a captain", "Searching for 00:%02d".format(i * 2))
        }
        assertEquals(PostOutcome.EVENT, updates.first().outcome)
        assertTrue(updates.drop(1).all { it.outcome == PostOutcome.DUPLICATE_DROPPED && it.resolvedAtElapsedMs == it.arrivedAtElapsedMs })
        h.advance(1_000)
        val assigned = h.post(RAPIDO, "r1", Text.rapidoAssigned())
        assertWithin(assigned, 3_000)
        assertEquals(RideState.ASSIGNED, h.state(Provider.RAPIDO))
        assertEquals(1, h.effectsOf<GuardianEffect.DriverSheet>().size)
        assertClean(h)
    }

    @Test
    fun `(h) a redacted PIN at booking, then a redacted assignment, gives a second prompt`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        val pin = h.post(RAPIDO, "r-pin", null, null, id = 7, channel = "otp", ongoing = false)
        assertEquals(PostKind.REDACTED, pin.kind)
        assertWithin(pin, 3_000)
        assertEquals(RideState.MAYBE_ASSIGNED, h.state(Provider.RAPIDO))
        assertEquals(1, h.effectsOf<GuardianEffect.MaybePrompt>().size)

        h.advance(4_000)
        val mark = h.mark()
        val assignment = h.post(RAPIDO, "r-ride", null, null, id = 8, channel = "ride_status", ongoing = true)
        assertEquals("matched structurally", PostKind.STRUCTURAL_HIT, assignment.kind)
        assertWithin(assignment, 3_000)
        assertEquals(RideState.ASSIGNED, h.state(Provider.RAPIDO))
        assertEquals(UiKey.PROMPT_WITHDRAWN_DRIVER_ASSIGNED, h.effectsOf<GuardianEffect.PromptWithdrawn>(mark).single().key)
        val second = h.timedOf<GuardianEffect.DriverSheet>(mark).single()
        assertTrue(second.first - assignment.arrivedAtElapsedMs <= 3_000)
        assertNull("no template ETA: 'see in Rapido'", second.second.drivers.single().etaMin)
        assertClean(h)
    }

    @Test
    fun `(h) without a structural template the second redacted post is not deduped and prompts again`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO), templates = Fixtures.templatesWithoutStructural)
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", null, null, id = 7, channel = "otp", ongoing = false)
        val first = h.effectsOf<GuardianEffect.MaybePrompt>().single()
        h.act(UserAction.AnswerPrompt(first.promptId, Answer.NOT_YET, h.tap()))
        assertEquals(RideState.SEARCHING, h.state(Provider.RAPIDO))

        h.advance(3_000)
        val mark = h.mark()
        val assignment = h.post(RAPIDO, "r1", null, null, id = 8, channel = "ride_status", ongoing = true)
        assertEquals(PostKind.REDACTED, assignment.kind)
        assertEquals(PostOutcome.EVENT, assignment.outcome)
        assertWithin(assignment, 3_000)
        val second = h.timedOf<GuardianEffect.MaybePrompt>(mark).single()
        assertNotEquals(first.promptId, second.second.promptId)
        assertTrue(second.first - assignment.arrivedAtElapsedMs <= 3_000)
        // The same redacted post again (same id, same 10 s bucket) is an exact repeat.
        assertEquals(PostOutcome.DUPLICATE_DROPPED, h.post(RAPIDO, "r1", null, null, id = 8, channel = "ride_status", ongoing = true).outcome)
        assertClean(h)
    }

    @Test
    fun `(i) a reworded assignment with an anonymous user or the backend down prompts within 3 s`() = runTest {
        for (mode in listOf(FakeClassifier.Mode.ANONYMOUS, FakeClassifier.Mode.DOWN)) {
            val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO), classifier = FakeClassifier(mode))
            h.book(Provider.UBER, Provider.RAPIDO)
            h.post(UBER, "u1", Text.uberSearching)
            h.post(RAPIDO, "r1", Text.rapidoSearching)
            h.advance(20_000)
            val mark = h.mark()
            val reworded = h.post(RAPIDO, "r1", "Rapido", "Great news! Your captain accepted and is heading over")
            assertEquals(PostKind.RULE_MISS, reworded.kind)
            assertWithin(reworded, 3_000)
            val prompt = h.timedOf<GuardianEffect.MaybePrompt>(mark).single()
            assertEquals(mode.name, UiKey.PROMPT_MAYBE_ASSIGNED, prompt.second.key)
            assertEquals(UiAction.YES_CANCEL_OTHERS, prompt.second.actions.first())
            assertTrue(prompt.first - reworded.arrivedAtElapsedMs <= 3_000)
            assertClean(h)
        }
    }

    @Test
    fun `(i) a hanging backend prompts at the 10 s deadline, a working one proposes ASSIGNED with 'see in app'`() = runTest {
        val hang = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO), classifier = FakeClassifier(FakeClassifier.Mode.HANG))
        hang.book(Provider.UBER, Provider.RAPIDO)
        hang.post(UBER, "u1", Text.uberSearching)
        hang.post(RAPIDO, "r1", Text.rapidoSearching)
        val slow = hang.post(RAPIDO, "r1", "Rapido", "Great news! Your captain accepted and is heading over")
        hang.advance(9_999)
        assertEquals(RideState.SEARCHING, hang.state(Provider.RAPIDO))
        hang.advance(1)
        assertEquals(UnknownReason.CLASSIFIER_TIMEOUT, slow.unknownReason)
        assertWithin(slow, 3_000 + 10_000)
        assertEquals(RideState.MAYBE_ASSIGNED, hang.state(Provider.RAPIDO))
        assertClean(hang)

        val classifier = FakeClassifier(latencyMs = 1_500).apply { rules += "accepted" to ClassifierLabel.ASSIGNED }
        val ok = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO), classifier = classifier)
        ok.book(Provider.UBER, Provider.RAPIDO)
        ok.post(UBER, "u1", Text.uberSearching)
        ok.post(RAPIDO, "r1", Text.rapidoSearching)
        val labelled = ok.post(RAPIDO, "r1", "Rapido", "Great news! Your captain accepted and is heading over")
        ok.advance(1_500)
        assertWithin(labelled, 3_000 + 10_000)
        assertEquals(RideState.ASSIGNED, ok.state(Provider.RAPIDO))
        val sheet = ok.effectsOf<GuardianEffect.DriverSheet>().single()
        assertNull(sheet.drivers.single().etaMin)
        assertEquals(EtaSource.NONE, sheet.drivers.single().etaSource)
        assertClean(ok)
    }

    @Test
    fun `opening the app to cancel links to the user's Yes tap`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val yes = h.tap()
        val effects = h.act(UserAction.AnswerSheet(h.effectsOf<GuardianEffect.DriverSheet>().single().sheetId, SheetChoice.YES_CANCEL_OTHERS, yes))
        val open = effects.filterIsInstance<GuardianEffect.OpenApp>().single()
        assertEquals(OpenPurpose.CANCEL, open.purpose)
        assertEquals(yes, open.tapId)
        assertEquals(Provider.UBER, open.provider)
        assertClean(h)
    }
}
