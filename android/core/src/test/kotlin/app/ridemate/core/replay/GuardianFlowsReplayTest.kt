@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.core.replay

import app.ridemate.core.guardian.ActionKind
import app.ridemate.core.guardian.FeeNote
import app.ridemate.core.guardian.FeeNoteKind
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.OndcCommandKind
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.SheetChoice
import app.ridemate.core.guardian.SheetKind
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.invariants.Invariants
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.replay.Fixtures.OLA
import app.ridemate.core.replay.Fixtures.RAPIDO
import app.ridemate.core.replay.Fixtures.Text
import app.ridemate.core.replay.Fixtures.UBER
import app.ridemate.core.state.Answer
import app.ridemate.core.state.Protocol
import app.ridemate.core.state.ProtocolKind
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end guardian flows (goal items 10–18) through RideWatch on virtual time. */
class GuardianFlowsReplayTest {

    private fun ReplayHarness.yes() =
        act(UserAction.AnswerSheet(effectsOf<GuardianEffect.DriverSheet>().last().sheetId, SheetChoice.YES_CANCEL_OTHERS, tap()))

    private fun assertClean(h: ReplayHarness) {
        assertEquals(emptyList<Any>(), Invariants.checkI9(h.pipeline.audit, h.now))
        assertEquals(emptyList<Any>(), Invariants.checkGuardian(h.guardian).violations)
    }

    @Test
    fun `kept leg lost - driver cancelled, no new driver after 3 min, the user says it ended`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.yes()
        h.post(RAPIDO, "r1", Text.rapidoDriverCancelled)
        assertEquals(UiKey.BANNER_FINDING_ANOTHER_CAPTAIN, h.effectsOf<GuardianEffect.Banner>().last().key)
        h.advance(179_000)
        assertEquals(RideState.DRIVER_CANCELLED, h.state(Provider.RAPIDO))
        h.advance(1_000)
        assertEquals("the ongoing ride post is still there after 3 min", RideState.MAYBE_NEW_DRIVER, h.state(Provider.RAPIDO))
        val prompt = h.effectsOf<GuardianEffect.MaybePrompt>().single()
        assertEquals(UiKey.PROMPT_MAYBE_NEW_DRIVER, prompt.key)
        val mark = h.mark()
        h.act(UserAction.AnswerPrompt(prompt.promptId, Answer.NO_IT_ENDED, h.tap()))
        assertEquals(RideState.PROVIDER_CANCELLED, h.state(Provider.RAPIDO))
        val alert = h.effectsOf<GuardianEffect.LoudAlert>(mark).single()
        assertEquals(UiKey.ALERT_KEPT_LEG_LOST, alert.key)
        assertEquals(Provider.RAPIDO, alert.provider)
        assertNull(h.guardian.state.session!!.keptLegId)
        assertClean(h)
    }

    @Test
    fun `a driver-cancelled leg whose ride post is gone gets no new-driver question`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.post(RAPIDO, "r1", Text.rapidoDriverCancelled)
        h.remove(RAPIDO, "r1")
        h.advance(200_000)
        assertEquals(RideState.DRIVER_CANCELLED, h.state(Provider.RAPIDO))
        assertTrue(h.effectsOf<GuardianEffect.MaybePrompt>().isEmpty())
    }

    @Test
    fun `an unanswered MAYBE prompt is asked again every 2 minutes`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.OLA))
        h.book(Provider.UBER, Provider.OLA)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(OLA, "o1", null, null, id = 3)
        h.advance(250_000)
        val prompts = h.timedOf<GuardianEffect.MaybePrompt>()
        assertEquals(listOf(0, 1, 2), prompts.map { it.second.repeat })
        assertEquals(listOf(0L, 120_000L, 240_000L), prompts.map { it.first - prompts.first().first })
        assertEquals(1, prompts.map { it.second.promptId }.distinct().size)
    }

    @Test
    fun `a Yes on 'Got a driver' is the Yes for the chain - no second sheet`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", null, null, id = 4, channel = "otp", ongoing = false)
        val prompt = h.effectsOf<GuardianEffect.MaybePrompt>().single()
        assertEquals(UiAction.YES_CANCEL_OTHERS, prompt.actions.first())
        val yes = h.tap()
        val effects = h.act(UserAction.AnswerPrompt(prompt.promptId, Answer.YES, yes))
        assertEquals(RideState.ASSIGNED, h.state(Provider.RAPIDO))
        assertEquals(LegRole.KEPT, h.record(Provider.RAPIDO).role)
        assertTrue(h.effectsOf<GuardianEffect.DriverSheet>().isEmpty())
        val open = effects.filterIsInstance<GuardianEffect.OpenApp>().single()
        assertEquals(Provider.UBER, open.provider)
        assertEquals(yes, open.tapId)
        val ride = effects.filterIsInstance<GuardianEffect.RideBanner>().single()
        assertNull("ETA: see in Rapido", ride.driver.etaMin)
        assertClean(h)
    }

    @Test
    fun `search expired withdraws the open question and says no cancel is needed`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.OLA))
        h.book(Provider.UBER, Provider.OLA)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(OLA, "o1", null, null, id = 2)
        assertEquals(RideState.MAYBE_ASSIGNED, h.state(Provider.OLA))
        val mark = h.mark()
        h.post(OLA, "o1", Text.olaNoDrivers)
        assertEquals(RideState.SEARCH_EXPIRED, h.state(Provider.OLA))
        assertEquals(UiKey.PROMPT_WITHDRAWN_SEARCH_EXPIRED, h.effectsOf<GuardianEffect.PromptWithdrawn>(mark).single().key)
        assertEquals(GuardianEffect.Banner(h.legId(Provider.OLA), Provider.OLA, UiKey.BANNER_ENDED_BY_APP), h.effectsOf<GuardianEffect.Banner>(mark).single())
        h.advance(300_000)
        assertTrue("no re-prompt for an ended leg", h.effectsOf<GuardianEffect.MaybePrompt>(mark).isEmpty())
        assertClean(h)
    }

    @Test
    fun `an extra leg the app ends stops its reminders, and proof of the last one stops them all`() = runTest {
        val h = ReplayHarness(this)
        h.book(Provider.UBER, Provider.OLA, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(OLA, "o1", Text.olaSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.yes()
        h.advance(35_000)
        val mark = h.mark()
        h.post(OLA, "o1", Text.olaNoDrivers)
        assertEquals(RideState.SEARCH_EXPIRED, h.state(Provider.OLA))
        assertTrue(h.record(Provider.OLA).leg.endedByApp)
        assertTrue(h.effectsOf<GuardianEffect.Banner>(mark).any { it.key == UiKey.BANNER_ENDED_BY_APP && it.provider == Provider.OLA })
        h.advance(30_000)
        assertEquals(listOf(Provider.UBER), h.effectsOf<GuardianEffect.Reminder>(mark).last().legs.map { it.provider })
        h.post(UBER, "u1", Text.uberCancelled)
        assertEquals(RideState.CANCEL_CONFIRMED, h.state(Provider.UBER))
        val after = h.mark()
        h.advance(900_000)
        assertTrue(h.effectsOf<GuardianEffect.Reminder>(after).isEmpty())
        assertFalse(h.guardian.needsForegroundService)
        assertClean(h)
    }

    @Test
    fun `the first-driver sheet is withdrawn when that driver's ride ends before an answer`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val sheet = h.effectsOf<GuardianEffect.DriverSheet>().single()
        h.post(RAPIDO, "r1", Text.rapidoCancelled)
        assertEquals(GuardianEffect.SheetWithdrawn(sheet.sheetId, UiKey.SHEET_WITHDRAWN_PROVIDER_CANCELLED), h.effectsOf<GuardianEffect.SheetWithdrawn>().single())
        val late = h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        assertTrue(late.any { it is GuardianEffect.AnswerRejected })
        assertEquals(RideState.SEARCHING, h.state(Provider.UBER))
        assertClean(h)
    }

    @Test
    fun `two drivers within 10 s - pick one, never auto-picked`() = runTest {
        val h = ReplayHarness(this)
        h.book(Provider.UBER, Provider.OLA, Provider.RAPIDO)
        h.post(OLA, "o1", Text.olaSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val first = h.effectsOf<GuardianEffect.DriverSheet>().single()
        h.advance(6_000)
        h.post(UBER, "u1", Text.uberAssigned())
        assertEquals(GuardianEffect.SheetWithdrawn(first.sheetId, UiKey.SHEET_REPLACED), h.effectsOf<GuardianEffect.SheetWithdrawn>().single())
        val pick = h.effectsOf<GuardianEffect.DriverSheet>().last()
        assertEquals(UiKey.SHEET_TWO_DRIVERS_PICK_ONE, pick.key)
        assertEquals(listOf(Provider.RAPIDO, Provider.UBER), pick.drivers.map { it.provider })
        assertEquals(listOf(Provider.OLA), pick.others.map { it.provider })
        assertEquals(listOf(UiAction.PICK, UiAction.NOT_NOW), pick.actions)

        h.advance(600_000)
        assertNull("never auto-picked", h.guardian.state.session!!.keptLegId)
        assertTrue(h.effectsOf<GuardianEffect.OpenApp>().isEmpty())

        val pickTap = h.tap()
        h.act(UserAction.AnswerSheet(pick.sheetId, SheetChoice.PICK, pickTap, pickedLegId = h.legId(Provider.UBER)))
        assertEquals(LegRole.KEPT, h.record(Provider.UBER).role)
        // Searching legs first (usually free), then the driver leg.
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.OLA))
        assertEquals(RideState.CANCEL_REQUESTED, h.state(Provider.RAPIDO))
        assertEquals(listOf(Provider.OLA), h.effectsOf<GuardianEffect.OpenApp>().map { it.provider })
        h.post(OLA, "o1", Text.olaCancelled)
        assertEquals(listOf(Provider.OLA, Provider.RAPIDO), h.effectsOf<GuardianEffect.OpenApp>().map { it.provider })
        assertEquals(pickTap, h.effectsOf<GuardianEffect.OpenApp>().last().tapId)
        assertClean(h)
    }

    @Test
    fun `assignments 12 s apart keep the first driver's sheet`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.advance(12_000)
        h.post(UBER, "u1", Text.uberAssigned())
        val sheets = h.effectsOf<GuardianEffect.DriverSheet>()
        assertEquals(1, sheets.size)
        assertEquals(UiKey.SHEET_CANCEL_OTHERS, sheets.single().key)
        assertEquals(Provider.RAPIDO, sheets.single().drivers.single().provider)
        h.yes()
        assertEquals("the second driver's booking is cancelled too", RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertEquals(FeeNoteKind.FREE_WINDOW, h.effectsOf<GuardianEffect.CancelCard>().last().fee.kind)
    }

    @Test
    fun `same driver on two apps - keep one`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.OLA, Provider.RAPIDO))
        h.book(Provider.OLA, Provider.RAPIDO)
        h.post(RAPIDO, "r1", Text.rapidoAssigned(plate = "AS01AB1234"))
        h.advance(30_000)
        h.post(OLA, "o1", Text.olaAssigned(plate = "AS 01 AB 1234"))
        val sheet = h.effectsOf<GuardianEffect.DriverSheet>().last()
        assertEquals(UiKey.SHEET_SAME_DRIVER_KEEP_ONE, sheet.key)
        assertEquals(setOf(Provider.OLA, Provider.RAPIDO), sheet.drivers.map { it.provider }.toSet())
        h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.PICK, h.tap(), pickedLegId = h.legId(Provider.OLA)))
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.RAPIDO))
        assertClean(h)
    }

    @Test
    fun `same driver showing up on a leg being cancelled is pointed out`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.OLA, Provider.RAPIDO))
        h.book(Provider.OLA, Provider.RAPIDO)
        h.post(OLA, "o1", Text.olaSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned(plate = "AS01AB1234"))
        h.yes()
        val mark = h.mark()
        h.post(OLA, "o1", Text.olaAssigned(plate = "AS01AB1234"))
        assertEquals(listOf(h.legId(Provider.OLA), h.legId(Provider.RAPIDO)).toSet(), h.effectsOf<GuardianEffect.SameDriverNotice>(mark).single().legIds.toSet())
        h.post(OLA, "o1", Text.olaAssigned(eta = 4, plate = "AS01AB1234"))
        assertEquals("reported once", 1, h.effectsOf<GuardianEffect.SameDriverNotice>().size)
    }

    @Test
    fun `riding a driver without answering the sheet makes it the kept ride and points out the other bookings`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val sheet = h.effectsOf<GuardianEffect.DriverSheet>().single()
        h.advance(240_000)
        val mark = h.mark()
        h.post(RAPIDO, "r1", Text.rapidoTripStarted)
        assertEquals(RideState.ON_TRIP, h.state(Provider.RAPIDO))
        assertEquals(UiKey.SHEET_WITHDRAWN_TRIP_UNDERWAY, h.effectsOf<GuardianEffect.SheetWithdrawn>(mark).single { it.sheetId == sheet.sheetId }.key)
        assertEquals(LegRole.KEPT, h.record(Provider.RAPIDO).role)
        val alert = h.effectsOf<GuardianEffect.LoudAlert>(mark).single()
        assertEquals(h.legId(Provider.UBER), alert.legId)
        assertEquals(UiKey.ALERT_STILL_LIVE, alert.key)

        // Uber then finds a driver while the user is already riding: never silent.
        val mark2 = h.mark()
        h.post(UBER, "u1", Text.uberAssigned())
        assertEquals(h.legId(Provider.UBER), h.effectsOf<GuardianEffect.LoudAlert>(mark2).single().legId)
        assertTrue(h.effectsOf<GuardianEffect.DriverSheet>(mark2).isEmpty())
        h.post(RAPIDO, "r1", Text.rapidoEnded)
        assertEquals(h.legId(Provider.RAPIDO), h.effectsOf<GuardianEffect.PayHandoffOffer>().single().legId)
        assertClean(h)
    }

    @Test
    fun `a booking that finds a driver while the kept captain is being replaced is pointed out, then offered`() = runTest {
        val h = keepSearchingWith(this, keptEta = 4)
        h.post(RAPIDO, "r1", Text.rapidoDriverCancelled)
        assertEquals(RideState.DRIVER_CANCELLED, h.state(Provider.RAPIDO))
        val mark = h.mark()
        h.post(UBER, "u1", Text.uberAssigned(eta = 3))
        val alert = h.effectsOf<GuardianEffect.LoudAlert>(mark).single()
        assertEquals(h.legId(Provider.UBER), alert.legId)
        assertEquals(listOf(UiAction.OPEN_APP, UiAction.DISMISS), alert.actions)

        // Rapido's new captain: now both bookings have a driver, so the user keeps one and cancels the other.
        val mark2 = h.mark()
        h.post(RAPIDO, "r1", Text.rapidoAssigned(eta = 4, plate = "AS02XY9876"))
        val sheet = h.effectsOf<GuardianEffect.BetterRideSheet>(mark2).single()
        assertEquals(h.legId(Provider.RAPIDO), sheet.current.legId)
        assertEquals(h.legId(Provider.UBER), sheet.candidate.legId)
        h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.KEEP_CURRENT, h.tap()))
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertEquals(LegRole.EXTRA, h.record(Provider.UBER).role)
        assertClean(h)
    }

    @Test
    fun `a leg that may already have a driver is never called free to cancel`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", null, null)
        assertEquals(RideState.MAYBE_ASSIGNED, h.state(Provider.UBER))
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val sheet = h.effectsOf<GuardianEffect.DriverSheet>().single()
        assertEquals(FeeNote(FeeNoteKind.MAY_APPLY, upToInr = 100), sheet.others.single().fee)
        h.yes()
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertEquals(FeeNoteKind.MAY_APPLY, h.effectsOf<GuardianEffect.CancelCard>().single().fee.kind)
        // Uber's own searching post settles it.
        val mark = h.mark()
        h.post(UBER, "u1", Text.uberSearching)
        assertEquals(FeeNoteKind.FREE_WHILE_SEARCHING, h.effectsOf<GuardianEffect.CancelCard>(mark).single().fee.kind)
        assertClean(h)
    }

    private fun keepSearchingWith(test: kotlinx.coroutines.test.TestScope, keptEta: Int): ReplayHarness {
        val h = ReplayHarness(test, providers = listOf(Provider.UBER, Provider.RAPIDO), keepSearching = true)
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned(eta = keptEta))
        val sheet = h.effectsOf<GuardianEffect.DriverSheet>().single()
        assertEquals(listOf(UiAction.YES_CANCEL_OTHERS, UiAction.KEEP_SEARCHING, UiAction.NOT_NOW), sheet.actions)
        h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.KEEP_SEARCHING, h.tap()))
        assertEquals(LegRole.KEPT, h.record(Provider.RAPIDO).role)
        assertEquals(LegRole.CANDIDATE, h.record(Provider.UBER).role)
        return h
    }

    @Test
    fun `better ride - a much closer driver is offered with both ETAs and fees, and Yes switches`() = runTest {
        val h = keepSearchingWith(this, keptEta = 12)
        h.advance(60_000)
        h.post(UBER, "u1", Text.uberAssigned(eta = 5, dist = "800 m"))
        val sheet = h.effectsOf<GuardianEffect.BetterRideSheet>().single()
        assertEquals(UiKey.SHEET_BETTER_RIDE, sheet.key)
        assertEquals(12, sheet.current.etaMin)
        assertEquals(1200, sheet.current.distanceMeters)
        assertEquals(5, sheet.candidate.etaMin)
        assertEquals(800, sheet.candidate.distanceMeters)
        assertEquals(EtaSource.TEMPLATE, sheet.candidate.etaSource)
        assertEquals(FeeNoteKind.MAY_APPLY, sheet.currentFee.kind)
        assertEquals(FeeNoteKind.FREE_WINDOW, sheet.candidateFee.kind)
        assertEquals(listOf(UiAction.SWITCH, UiAction.KEEP_CURRENT), sheet.actions)
        h.advance(30_000)
        assertNull("nothing switches without the Yes", h.guardian.state.switches.firstOrNull())
        assertEquals(LegRole.KEPT, h.record(Provider.RAPIDO).role)

        val yes = h.tap()
        h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.SWITCH, yes))
        assertEquals(LegRole.KEPT, h.record(Provider.UBER).role)
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.RAPIDO))
        val open = h.effectsOf<GuardianEffect.OpenApp>().single()
        assertEquals(Provider.RAPIDO, open.provider)
        assertEquals(yes, open.tapId)
        assertEquals(ActionKind.SWITCH, h.guardian.state.actions.single { it.tapId == yes && it.legId == h.legId(Provider.UBER) }.kind)
        assertClean(h)
    }

    @Test
    fun `better ride - No cancels the new driver instead`() = runTest {
        val h = keepSearchingWith(this, keptEta = 10)
        h.post(UBER, "u1", Text.uberAssigned(eta = 5))
        val sheet = h.effectsOf<GuardianEffect.BetterRideSheet>().single()
        assertEquals("gain 5 = max(5 min, 40% of 10)", UiKey.SHEET_BETTER_RIDE, sheet.key)
        h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.KEEP_CURRENT, h.tap()))
        assertEquals(LegRole.KEPT, h.record(Provider.RAPIDO).role)
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertEquals(Provider.UBER, h.effectsOf<GuardianEffect.OpenApp>().single().provider)
        assertClean(h)
    }

    @Test
    fun `better ride thresholds - a smaller gain or an unverified ETA is not 'better'`() = runTest {
        val notEnough = keepSearchingWith(this, keptEta = 20)
        notEnough.post(UBER, "u1", Text.uberAssigned(eta = 13))
        assertEquals("gain 7 < 40% of 20", UiKey.SHEET_SECOND_DRIVER, notEnough.effectsOf<GuardianEffect.BetterRideSheet>().single().key)

        val enough = keepSearchingWith(this, keptEta = 20)
        enough.post(UBER, "u1", Text.uberAssigned(eta = 12))
        assertEquals("gain 8 = 40% of 20", UiKey.SHEET_BETTER_RIDE, enough.effectsOf<GuardianEffect.BetterRideSheet>().single().key)

        val unverified = keepSearchingWith(this, keptEta = 20)
        unverified.post(UBER, "u1", null, null, id = 9)
        val prompt = unverified.effectsOf<GuardianEffect.MaybePrompt>().single()
        assertEquals("a candidate's Yes is just 'got a driver'", UiAction.YES, prompt.actions.first())
        unverified.act(UserAction.AnswerPrompt(prompt.promptId, Answer.YES, unverified.tap()))
        val sheet = unverified.effectsOf<GuardianEffect.BetterRideSheet>().single()
        assertEquals("no ETA to compare: never 'better'", UiKey.SHEET_SECOND_DRIVER, sheet.key)
        assertNull(sheet.candidate.etaMin)
    }

    @Test
    fun `better ride alerts stop once the current driver is 2 min away, and the rest are offered for cancelling`() = runTest {
        val h = keepSearchingWith(this, keptEta = 4)
        h.advance(119_000)
        assertTrue(h.effectsOf<GuardianEffect.DriverSheet>().size == 1)
        h.advance(1_000)
        assertTrue(h.guardian.state.session!!.betterRideStopped)
        val offer = h.effectsOf<GuardianEffect.DriverSheet>().last()
        assertEquals(UiKey.SHEET_CANCEL_OTHERS, offer.key)
        assertEquals(Provider.RAPIDO, offer.drivers.single().provider)
        assertEquals(listOf(Provider.UBER), offer.others.map { it.provider })
        h.post(UBER, "u1", Text.uberAssigned(eta = 1))
        val late = h.effectsOf<GuardianEffect.BetterRideSheet>().single()
        assertEquals(UiKey.SHEET_SECOND_DRIVER, late.key)
        assertFalse("no switching any more", UiAction.SWITCH in late.actions)
        assertClean(h)
    }

    @Test
    fun `better ride sheet is withdrawn when the trip starts, and a stale Yes switches nothing`() = runTest {
        val h = keepSearchingWith(this, keptEta = 15)
        h.post(UBER, "u1", Text.uberAssigned(eta = 3))
        val sheet = h.effectsOf<GuardianEffect.BetterRideSheet>().single()
        h.post(RAPIDO, "r1", Text.rapidoTripStarted)
        assertEquals(GuardianEffect.SheetWithdrawn(sheet.sheetId, UiKey.SHEET_WITHDRAWN_TRIP_UNDERWAY), h.effectsOf<GuardianEffect.SheetWithdrawn>().single())
        val stillLive = h.effectsOf<GuardianEffect.LoudAlert>().single()
        assertEquals("the other booking is still live and no chain may start now (I10)", UiKey.ALERT_STILL_LIVE, stillLive.key)
        assertEquals(Provider.UBER, stillLive.provider)
        val stale = h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.SWITCH, h.tap()))
        assertTrue(stale.any { it is GuardianEffect.AnswerRejected })
        assertTrue(h.guardian.state.switches.isEmpty())
        assertEquals(LegRole.KEPT, h.record(Provider.RAPIDO).role)
        assertClean(h)
    }

    @Test
    fun `a better-ride sheet bound to a driver who cancelled is withdrawn (I4)`() = runTest {
        val h = keepSearchingWith(this, keptEta = 15)
        h.post(UBER, "u1", Text.uberAssigned(eta = 3))
        val sheet = h.effectsOf<GuardianEffect.BetterRideSheet>().single()
        h.post(UBER, "u1", Text.uberDriverCancelled)
        assertEquals(UiKey.SHEET_WITHDRAWN_DRIVER_CANCELLED, h.effectsOf<GuardianEffect.SheetWithdrawn>().single { it.sheetId == sheet.sheetId }.key)
        assertTrue(h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.SWITCH, h.tap())).any { it is GuardianEffect.AnswerRejected })
        assertTrue(h.guardian.state.switches.isEmpty())
    }

    @Test
    fun `rung 3 - the ride post removed by the app plus a cancelled screen proves the cancel`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.yes()
        val mark = h.mark()
        h.remove(UBER, "u1")
        assertEquals(GuardianEffect.RequestCapture(h.legId(Provider.UBER), Provider.UBER, app.ridemate.core.guardian.CaptureReason.CANCEL_PROOF), h.effectsOf<GuardianEffect.RequestCapture>(mark).single())
        assertEquals("removal alone proves nothing", RideState.CANCEL_GUIDED, h.state(Provider.UBER))

        h.advance(20_000)
        val check = h.tap()
        h.act(UserAction.CheckScreen(h.legId(Provider.UBER), check))
        h.advance(5_000)
        h.guardian.onCaptureVerdict(h.legId(Provider.UBER), cancelledKeywordsFound = true, tapId = check, frameAtElapsedMs = h.now)
        assertEquals("I6: a frame 5 s after the tap is blocked", RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertFalse(h.guardian.state.captures.last().accepted)

        val again = h.tap()
        h.act(UserAction.CheckScreen(h.legId(Provider.UBER), again))
        h.advance(400)
        h.guardian.onCaptureVerdict(h.legId(Provider.UBER), cancelledKeywordsFound = true, tapId = again, frameAtElapsedMs = h.now)
        assertEquals(RideState.CANCEL_CONFIRMED, h.state(Provider.UBER))
        assertEquals(EvidenceRung.REMOVAL_PLUS_CAPTURE, h.guardian.state.evidence.single().rung)
        assertClean(h)
    }

    @Test
    fun `rung 3 - a searching post swapped for the assigned one is not half of a cancel proof`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u-search", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.yes()
        h.advance(10_000)
        // Uber finds a driver: it removes its searching post (APP_CANCEL) and posts the assigned one.
        h.remove(UBER, "u-search")
        h.post(UBER, "u-ride", Text.uberAssigned())
        h.advance(20_000)
        // The user's capture shows Uber's "your ride will be cancelled" confirmation before they tap it.
        val check = h.tap()
        h.act(UserAction.CheckScreen(h.legId(Provider.UBER), check))
        h.advance(300)
        h.guardian.onCaptureVerdict(h.legId(Provider.UBER), cancelledKeywordsFound = true, tapId = check, frameAtElapsedMs = h.now)
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertTrue(h.guardian.state.evidence.isEmpty())
        assertTrue(h.guardian.needsForegroundService)
        // Only the removal of the live ride post, after that capture, proves it.
        h.remove(UBER, "u-ride")
        assertEquals(RideState.CANCEL_CONFIRMED, h.state(Provider.UBER))
        assertEquals(EvidenceRung.REMOVAL_PLUS_CAPTURE, h.guardian.state.evidence.single().rung)
        assertClean(h)
    }

    @Test
    fun `a cancelled post left in the shade from an earlier ride neither ends a leg nor proves its cancel`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        val leftover = PostSnapshot(
            UBER, "u-yesterday", 3, null, "trip", "status", false, Text.uberCancelled.first, Text.uberCancelled.second,
            h.clock.wallMs() - 3_600_000, 100,
        )
        // The session start re-reads the shade.
        h.pipeline.onListenerConnected(listOf(PostSource.of(leftover)))
        assertEquals(RideState.SELECTED, h.state(Provider.UBER))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.yes()
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        // The listener reconnects while Uber (which has posted nothing yet) is being cancelled.
        h.pipeline.onListenerDisconnected()
        h.pipeline.onListenerConnected(listOf(PostSource.of(leftover)))
        testScheduler.runCurrent()
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertTrue(h.guardian.state.evidence.isEmpty())
        h.advance(31_000)
        assertEquals(1, h.historyOf<GuardianEffect.Reminder>().size)
        assertClean(h)
    }

    @Test
    fun `at the end of the ride the app is opened to pay there`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.RAPIDO))
        h.book(Provider.RAPIDO)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        assertEquals("no other booking: simply your ride", LegRole.KEPT, h.record(Provider.RAPIDO).role)
        h.post(RAPIDO, "r1", Text.rapidoArriving)
        h.post(RAPIDO, "r1", Text.rapidoTripStarted)
        h.post(RAPIDO, "r1", Text.rapidoEnded)
        assertEquals(GuardianEffect.PayHandoffOffer(h.legId(Provider.RAPIDO), Provider.RAPIDO), h.effectsOf<GuardianEffect.PayHandoffOffer>().single())
        val pay = h.tap()
        val open = h.act(UserAction.Pay(h.legId(Provider.RAPIDO), pay)).filterIsInstance<GuardianEffect.OpenApp>().single()
        assertEquals(OpenPurpose.PAY, open.purpose)
        assertEquals(pay, open.tapId)
        assertEquals(RideState.PAY_HANDOFF, h.state(Provider.RAPIDO))
        assertTrue(h.guardian.closeSession())
        assertClean(h)
    }

    @Test
    fun `ONDC - book after a tap, soft-cancel shows the fee, the confirm tap cancels`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.ONDC))
        val leg = h.legId(Provider.ONDC)
        h.guardian.onProtocolEvent(leg, Protocol(ProtocolKind.SEARCH_STARTED))
        assertEquals(RideState.SEARCHING_NET, h.state(Provider.ONDC))
        val book = h.tap()
        val bookFx = h.act(UserAction.OndcBook(leg, "item-auto-1", book))
        assertEquals(GuardianEffect.OndcCommand(leg, OndcCommandKind.BOOK, book, "item-auto-1"), bookFx.single { it is GuardianEffect.OndcCommand })
        h.guardian.onProtocolEvent(leg, Protocol(ProtocolKind.SELECTED, messageId = "m1"))
        h.guardian.onProtocolEvent(leg, Protocol(ProtocolKind.INITIALIZED, messageId = "m2"))
        h.guardian.onProtocolEvent(leg, Protocol(ProtocolKind.CONFIRMED, messageId = "m3"))
        assertEquals("the book tap is linked to the confirm (I1)", RideState.CONFIRMED, h.state(Provider.ONDC))
        h.guardian.onProtocolEvent(leg, Protocol(ProtocolKind.DRIVER_ASSIGNED, etaMin = 4, distanceMeters = 1100, messageId = "m4"))
        val ride = h.effectsOf<GuardianEffect.RideBanner>().single()
        assertEquals(EtaSource.NETWORK, ride.driver.etaSource)

        val cancel = h.tap()
        assertEquals(OndcCommandKind.SOFT_CANCEL, h.act(UserAction.OndcCancel(leg, cancel)).filterIsInstance<GuardianEffect.OndcCommand>().single().command)
        h.guardian.onProtocolEvent(leg, Protocol(ProtocolKind.SOFT_CANCEL_QUOTED, feeInr = 25, messageId = "m5"))
        assertEquals(GuardianEffect.OndcCancelFee(leg, 25), h.effectsOf<GuardianEffect.OndcCancelFee>().single())
        assertEquals("nothing is cancelled before the confirm tap", RideState.SOFT_CANCEL, h.state(Provider.ONDC))
        val confirm = h.tap()
        val confirmFx = h.act(UserAction.OndcConfirmCancel(leg, confirm))
        assertEquals(GuardianEffect.OndcCommand(leg, OndcCommandKind.CONFIRM_CANCEL, confirm), confirmFx.single { it is GuardianEffect.OndcCommand })
        h.guardian.onProtocolEvent(leg, Protocol(ProtocolKind.CANCEL_CONFIRMED, messageId = "m6"))
        assertEquals(RideState.CANCEL_CONFIRMED, h.state(Provider.ONDC))
        assertEquals(EvidenceRung.ONDC_ON_CANCEL, h.guardian.state.evidence.single().rung)
        assertClean(h)
    }

    @Test
    fun `ONDC as an extra booking is soft-cancelled by the chain after the user's Yes`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.RAPIDO, Provider.ONDC))
        val ondc = h.legId(Provider.ONDC)
        h.book(Provider.RAPIDO)
        h.guardian.onProtocolEvent(ondc, Protocol(ProtocolKind.SEARCH_STARTED))
        h.act(UserAction.OndcBook(ondc, "item-1", h.tap()))
        h.guardian.onProtocolEvent(ondc, Protocol(ProtocolKind.CONFIRMED))
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val sheet = h.effectsOf<GuardianEffect.DriverSheet>().single()
        assertEquals(FeeNoteKind.SHOWN_BEFORE_CONFIRM, sheet.others.single().fee.kind)
        val yes = h.tap()
        h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, yes))
        assertEquals(GuardianEffect.OndcCommand(ondc, OndcCommandKind.SOFT_CANCEL, yes), h.effectsOf<GuardianEffect.OndcCommand>().last())
        h.guardian.onProtocolEvent(ondc, Protocol(ProtocolKind.SOFT_CANCEL_QUOTED, feeInr = 0))
        h.act(UserAction.OndcConfirmCancel(ondc, h.tap()))
        h.advance(65_000)
        assertEquals(2, h.effectsOf<GuardianEffect.Reminder>().size)
        h.guardian.onProtocolEvent(ondc, Protocol(ProtocolKind.CANCEL_CONFIRMED))
        assertEquals(RideState.CANCEL_CONFIRMED, h.state(Provider.ONDC))
        assertFalse(h.guardian.needsForegroundService)
        assertClean(h)
    }
}
