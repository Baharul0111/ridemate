@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.core.guardian

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.replay.ReplayHarness
import app.ridemate.core.replay.SchedulerClock
import app.ridemate.core.state.Answer
import app.ridemate.core.state.ProviderUpdate
import app.ridemate.core.state.SignalSource
import app.ridemate.core.state.TapId
import app.ridemate.core.state.UnknownReason
import app.ridemate.core.state.UnknownUpdate
import app.ridemate.core.time.ManualClock
import app.ridemate.core.watch.Disposition
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guardian behaviour driven directly (no RideWatch), on a hand-moved clock. */
class GuardianTest {
    private val clock = ManualClock()
    private val fees = RuleBasedFeeEstimator(FeeRules.parse(ReplayHarness.FEE_RULES_JSON))
    private var taps = 0
    private fun tap() = TapId("t${++taps}")

    private fun guardian(vararg providers: Provider, keepSearching: Boolean = false): Guardian =
        Guardian(clock, fees).also { it.startSession("s", providers.toList(), keepSearching) }

    private fun id(p: Provider) = "s:${p.wireId}"

    private fun Guardian.tpl(p: Provider, signal: NotificationEvent, eta: Int? = null, plate: String? = null, key: String = "k-${p.wireId}") =
        onRideEvent(id(p), ProviderUpdate(signal, SignalSource.TEMPLATE, key, "tpl", eta, null, plate, clock.wallMs()))

    private fun Guardian.book(vararg providers: Provider) = providers.forEach { onUserAction(UserAction.BookedHere(id(it), tap())) }

    private fun Guardian.state(p: Provider) = state.legs.getValue(id(p)).leg.state

    @Test
    fun `sessions never mix demo and live legs, and keep one leg per provider`() {
        val g = Guardian(clock, fees)
        assertThrows(IllegalArgumentException::class.java) { g.startSession("s", listOf(Provider.UBER, Provider.DEMO_KESTREL)) }
        assertThrows(IllegalArgumentException::class.java) { g.startSession("s", listOf(Provider.UBER, Provider.UBER)) }
        assertThrows(IllegalArgumentException::class.java) { g.startSession("s", listOf(Provider.UBER), demo = true) }
        g.startSession("d", listOf(Provider.DEMO_KESTREL, Provider.DEMO_PEPPER), demo = true)
        assertEquals("d:demo_kestrel", g.legFor("demo.kestrel")!!.legId)
        assertNull(g.legFor("com.ubercab"))
    }

    @Test
    fun `a new session is refused while extra bookings still need cancelling (I8)`() {
        val g = guardian(Provider.UBER, Provider.RAPIDO)
        g.book(Provider.UBER, Provider.RAPIDO)
        g.tpl(Provider.RAPIDO, NotificationEvent.ASSIGNED, eta = 4)
        val sheet = g.effectLog.map { it.effect }.filterIsInstance<GuardianEffect.DriverSheet>().single()
        g.onUserAction(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, tap()))
        assertFalse(g.closeSession())
        assertThrows(IllegalStateException::class.java) { g.startSession("s2", listOf(Provider.OLA)) }
        g.tpl(Provider.UBER, NotificationEvent.CANCELLED)
        assertTrue(g.closeSession())
        assertNull(g.activeSessionId)
        assertNull("closed sessions read nothing", g.legFor("com.ubercab"))
    }

    @Test
    fun `lifecycle, Booked here and legFor report what RideWatch needs`() {
        val g = guardian(Provider.UBER, Provider.OLA)
        g.markOpened(id(Provider.UBER))
        g.markQuoted(id(Provider.UBER))
        assertEquals(RideState.QUOTED, g.state(Provider.UBER))
        assertFalse(g.legFor("com.ubercab")!!.isBooked)
        val booked = tap()
        g.onUserAction(UserAction.BookedHere(id(Provider.UBER), booked))
        assertTrue(g.legFor("com.ubercab")!!.isBooked)
        assertEquals(ActionKind.BOOKED_HERE, g.state.actions.single { it.tapId == booked }.kind)
        assertEquals("s", g.activeSessionId)
        assertEquals(Disposition.PROMPT, g.onRideEvent(id(Provider.UBER), UnknownUpdate(UnknownReason.REDACTED, "k")))
        assertEquals(Disposition.DUP_DROP, g.onRideEvent(id(Provider.UBER), UnknownUpdate(UnknownReason.REDACTED, "k")))
        assertEquals(Disposition.TRANSITION, g.tpl(Provider.UBER, NotificationEvent.SEARCHING))
        assertEquals(Disposition.LOGGED, g.tpl(Provider.OLA, NotificationEvent.ENDED))
        assertEquals(Disposition.LOGGED, g.onRideEvent("no-such-leg", UnknownUpdate(UnknownReason.REDACTED)))
    }

    @Test
    fun `the cancel queue puts searching legs first, then driver legs by when their free window closes`() {
        val g = guardian(Provider.UBER, Provider.OLA, Provider.RAPIDO, Provider.ONDC)
        g.book(Provider.UBER, Provider.OLA, Provider.RAPIDO)
        g.tpl(Provider.OLA, NotificationEvent.ASSIGNED, eta = 6, plate = "o")
        clock.advanceBy(10_000)
        g.tpl(Provider.UBER, NotificationEvent.ASSIGNED, eta = 3, plate = "u")
        val pick = g.effectLog.map { it.effect }.filterIsInstance<GuardianEffect.DriverSheet>().last()
        assertEquals(UiKey.SHEET_TWO_DRIVERS_PICK_ONE, pick.key)
        clock.advanceBy(15_000)
        g.tpl(Provider.RAPIDO, NotificationEvent.SEARCHING)
        // The user keeps Ola; Rapido (searching, usually free) goes first, then Uber (driver, free window).
        g.onUserAction(UserAction.AnswerSheet(pick.sheetId, SheetChoice.PICK, tap(), pickedLegId = id(Provider.OLA)))
        val chain = g.state.chain!!
        assertEquals(id(Provider.RAPIDO), chain.currentLegId)
        assertEquals(listOf(id(Provider.UBER)), chain.queue)
        val card = g.effectLog.map { it.effect }.filterIsInstance<GuardianEffect.CancelCard>().single()
        assertEquals(FeeNoteKind.FREE_WHILE_SEARCHING, card.fee.kind)
        assertEquals(1, card.position)
        assertEquals(2, card.total)
        assertEquals("the ONDC leg was never confirmed: nothing to cancel", LegRole.NONE, g.state.legs.getValue(id(Provider.ONDC)).role)
    }

    @Test
    fun `the cancel card refreshes when the leg being cancelled changes, and later legs are re-ordered`() {
        val g = guardian(Provider.UBER, Provider.OLA, Provider.RAPIDO)
        g.book(Provider.UBER, Provider.OLA, Provider.RAPIDO)
        g.tpl(Provider.RAPIDO, NotificationEvent.ASSIGNED, eta = 4, plate = "r")
        val sheet = g.effectLog.map { it.effect }.filterIsInstance<GuardianEffect.DriverSheet>().single()
        g.onUserAction(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, tap()))
        assertEquals(id(Provider.UBER), g.state.chain!!.currentLegId)
        val before = g.effectLog.size
        g.tpl(Provider.UBER, NotificationEvent.ASSIGNED, eta = 2, plate = "u")
        val refreshed = g.effectLog.drop(before).map { it.effect }.filterIsInstance<GuardianEffect.CancelCard>().single()
        assertEquals(RideState.ASSIGNED, refreshed.providerState)
        assertEquals(FeeNoteKind.FREE_WINDOW, refreshed.fee.kind)
        g.tpl(Provider.OLA, NotificationEvent.ASSIGNED, eta = 2, plate = "o")
        assertEquals(RideState.CANCEL_REQUESTED, g.state(Provider.OLA))
        assertEquals(RideState.ASSIGNED, g.state.legs.getValue(id(Provider.OLA)).leg.providerState)
    }

    @Test
    fun `stale or foreign answers are refused`() {
        val g = guardian(Provider.UBER, Provider.OLA)
        g.book(Provider.UBER, Provider.OLA)
        g.onRideEvent(id(Provider.OLA), UnknownUpdate(UnknownReason.REDACTED, "k"))
        val prompt = g.state.prompts.single()
        g.tpl(Provider.OLA, NotificationEvent.SEARCHING)
        val late = g.onUserAction(UserAction.AnswerPrompt(prompt.promptId, Answer.YES, tap()))
        assertTrue(late.single() is GuardianEffect.AnswerRejected)
        assertEquals(RideState.SEARCHING, g.state(Provider.OLA))
        assertTrue(g.onUserAction(UserAction.AnswerSheet("sheet-404", SheetChoice.YES_CANCEL_OTHERS, tap())).single() is GuardianEffect.AnswerRejected)
        assertTrue(g.onUserAction(UserAction.ICancelledIt(id(Provider.OLA), tap())).single() is GuardianEffect.AnswerRejected)
        assertTrue(g.onUserAction(UserAction.ShowMe(id(Provider.OLA), tap())).single() is GuardianEffect.AnswerRejected)
        assertTrue(g.onUserAction(UserAction.Pay(id(Provider.OLA), tap())).single() is GuardianEffect.AnswerRejected)
        assertTrue(g.onUserAction(UserAction.OndcCancel(id(Provider.OLA), tap())).single() is GuardianEffect.AnswerRejected)
        assertTrue(g.onUserAction(UserAction.BookedHere("nope", tap())).single() is GuardianEffect.AnswerRejected)
    }

    @Test
    fun `I said I cancelled it - follow-up after 60 s stays quiet when the ride post is gone`() {
        val g = guardian(Provider.UBER, Provider.RAPIDO)
        g.ongoingNotifications = app.ridemate.core.watch.OngoingNotifications { false }
        g.book(Provider.UBER, Provider.RAPIDO)
        g.tpl(Provider.RAPIDO, NotificationEvent.ASSIGNED)
        g.onUserAction(UserAction.AnswerSheet(g.state.sheets.single().sheetId, SheetChoice.YES_CANCEL_OTHERS, tap()))
        g.onUserAction(UserAction.ICancelledIt(id(Provider.UBER), tap()))
        assertEquals(RideState.USER_SAYS_CANCELLED, g.state(Provider.UBER))
        assertTrue("I8: the follow-up keeps the service up", g.needsForegroundService)
        clock.advanceBy(60_000)
        g.tick()
        assertEquals(RideState.USER_SAYS_CANCELLED, g.state(Provider.UBER))
        assertTrue(g.effectLog.none { it.effect is GuardianEffect.LoudAlert })
        assertTrue("not proof: reminders continue (I3, I8)", g.needsForegroundService)
    }

    @Test
    fun `Show me and Check screen ask for a capture, and a reminder's Open app re-guides a leg`() {
        val g = guardian(Provider.UBER, Provider.RAPIDO)
        g.book(Provider.UBER, Provider.RAPIDO)
        g.tpl(Provider.RAPIDO, NotificationEvent.ASSIGNED)
        g.onUserAction(UserAction.AnswerSheet(g.state.sheets.single().sheetId, SheetChoice.YES_CANCEL_OTHERS, tap()))
        assertEquals(CaptureReason.SHOW_ME, (g.onUserAction(UserAction.ShowMe(id(Provider.UBER), tap())).single() as GuardianEffect.RequestCapture).reason)
        assertEquals(CaptureReason.CHECK_SCREEN, (g.onUserAction(UserAction.CheckScreen(id(Provider.RAPIDO), tap())).single() as GuardianEffect.RequestCapture).reason)
        g.onUserAction(UserAction.ICancelledIt(id(Provider.UBER), tap()))
        val reopen = tap()
        val fx = g.onUserAction(UserAction.OpenApp(id(Provider.UBER), reopen))
        assertEquals(GuardianEffect.OpenApp(id(Provider.UBER), Provider.UBER, OpenPurpose.CANCEL, reopen), fx.filterIsInstance<GuardianEffect.OpenApp>().single())
        assertEquals(RideState.CANCEL_GUIDED, g.state(Provider.UBER))
        val view = g.onUserAction(UserAction.OpenApp(id(Provider.RAPIDO), tap())).filterIsInstance<GuardianEffect.OpenApp>().single()
        assertEquals(OpenPurpose.VIEW, view.purpose)
    }

    @Test
    fun `a verified capture of the driver screen gives VERIFIED numbers, and frames are gated on the tap (I6)`() {
        val g = guardian(Provider.UBER, Provider.RAPIDO)
        g.book(Provider.UBER, Provider.RAPIDO)
        g.onRideEvent(id(Provider.RAPIDO), UnknownUpdate(UnknownReason.REDACTED, "k"))
        val check = tap()
        g.onUserAction(UserAction.CheckScreen(id(Provider.RAPIDO), check))
        clock.advanceBy(800)
        g.onVerifiedDriverReading(id(Provider.RAPIDO), 6, 1500, check, clock.elapsedMs())
        val leg = g.state.legs.getValue(id(Provider.RAPIDO)).leg
        assertEquals(RideState.ASSIGNED, leg.state)
        assertEquals(EtaSource.VERIFIED, leg.driver.etaSource)
        val card = g.effectLog.map { it.effect }.filterIsInstance<GuardianEffect.DriverSheet>().single().drivers.single()
        assertEquals(6, card.etaMin)
        assertEquals(EtaSource.VERIFIED, card.etaSource)
        clock.advanceBy(5_000)
        g.onVerifiedDriverReading(id(Provider.RAPIDO), 2, 300, check, clock.elapsedMs())
        assertEquals("blocked: 5.8 s after its tap", 6, g.state.legs.getValue(id(Provider.RAPIDO)).leg.driver.etaMin)
        g.onVerifiedDriverReading(id(Provider.RAPIDO), 2, 300, TapId("never-tapped"), clock.elapsedMs())
        assertEquals(listOf(true, false, false), g.state.captures.map { it.accepted })
    }

    @Test
    fun `demo sessions accept demo frames without a tap window`() {
        val g = Guardian(clock, fees).also { it.startSession("d", listOf(Provider.DEMO_KESTREL, Provider.DEMO_PEPPER), demo = true) }
        g.onUserAction(UserAction.BookedHere("d:demo_kestrel", tap()))
        g.onRideEvent("d:demo_kestrel", ProviderUpdate(NotificationEvent.ASSIGNED, SignalSource.TEMPLATE, "k", "t", 3, null, null, clock.wallMs()))
        g.onVerifiedDriverReading("d:demo_kestrel", 2, null, TapId("script"), clock.elapsedMs() + 60_000)
        assertTrue(g.state.captures.single().accepted)
        assertEquals(EtaSource.VERIFIED, g.state.legs.getValue("d:demo_kestrel").leg.driver.etaSource)
    }

    @Test
    fun `snapshot and restore round-trip the whole state, and a reboot rebases the countdowns`() {
        val g = guardian(Provider.UBER, Provider.OLA, Provider.RAPIDO, keepSearching = true)
        g.book(Provider.UBER, Provider.OLA, Provider.RAPIDO)
        g.tpl(Provider.OLA, NotificationEvent.SEARCHING)
        g.tpl(Provider.RAPIDO, NotificationEvent.ASSIGNED, eta = 9, plate = "r")
        g.onUserAction(UserAction.AnswerSheet(g.state.sheets.single().sheetId, SheetChoice.YES_CANCEL_OTHERS, tap()))
        g.onRideEvent(id(Provider.UBER), UnknownUpdate(UnknownReason.REDACTED, "u"))
        clock.advanceBy(45_000)
        g.tick()
        val json = g.snapshot()
        val restored = Guardian.restore(json, clock, fees)
        assertEquals(json, restored.snapshot())
        assertEquals(g.state.copy(savedAtElapsedMs = 0, savedAtWallMs = 0), restored.state.copy(savedAtElapsedMs = 0, savedAtWallMs = 0))
        assertEquals(g.nextDueElapsedMs(), restored.nextDueElapsedMs())

        val dueBefore = restored.nextDueElapsedMs()!!
        val untilDue = dueBefore - clock.elapsedMs()
        clock.reboot(downtimeMs = 5_000, elapsedAfter = 2_000)
        val rebooted = Guardian.restore(json, clock, fees)
        assertEquals("same remaining time minus the downtime", untilDue - 5_000, rebooted.nextDueElapsedMs()!! - clock.elapsedMs())
        clock.advanceBy(untilDue - 5_000)
        val fx = rebooted.tick()
        assertEquals(2, fx.filterIsInstance<GuardianEffect.Reminder>().single().attempt)
    }

    @Test
    fun `a reboot is recognised even when the phone has since been up longer than at the save`() {
        val g = guardian(Provider.UBER, Provider.RAPIDO)
        g.book(Provider.UBER, Provider.RAPIDO)
        g.tpl(Provider.RAPIDO, NotificationEvent.ASSIGNED)
        g.onUserAction(UserAction.AnswerSheet(g.state.sheets.single().sheetId, SheetChoice.YES_CANCEL_OTHERS, tap()))
        clock.advanceBy(130_000)
        g.tick()
        val json = g.snapshot()
        val untilDue = g.nextDueElapsedMs()!! - clock.elapsedMs()
        assertEquals("the 240 s reminder", 110_000L, untilDue)

        // Down for 90 s, then up for 10 s longer than the uptime at the save: the monotonic clock never went backwards.
        clock.reboot(downtimeMs = 90_000, elapsedAfter = clock.elapsedMs() + 10_000)
        val rebooted = Guardian.restore(json, clock, fees)
        assertEquals(untilDue - 90_000, rebooted.nextDueElapsedMs()!! - clock.elapsedMs())

        // A small wall-clock correction without a reboot moves nothing.
        val corrected = ManualClock(elapsed = 131_000, wall = ManualClock.DEFAULT_WALL_MS + 131_000 + 20_000)
        val restored = Guardian.restore(json, corrected, fees)
        assertEquals(untilDue - 1_000, restored.nextDueElapsedMs()!! - corrected.elapsedMs())
    }

    @Test
    fun `resume re-announces the foreground service and catches up`() {
        val g = guardian(Provider.UBER, Provider.RAPIDO)
        g.book(Provider.UBER, Provider.RAPIDO)
        g.tpl(Provider.RAPIDO, NotificationEvent.ASSIGNED)
        g.onUserAction(UserAction.AnswerSheet(g.state.sheets.single().sheetId, SheetChoice.YES_CANCEL_OTHERS, tap()))
        val json = g.snapshot()
        clock.advanceBy(31_000)
        val restored = Guardian.restore(json, clock, fees)
        val fx = restored.resume()
        assertEquals(GuardianEffect.ForegroundService(true), fx.first())
        assertEquals(1, fx.filterIsInstance<GuardianEffect.Reminder>().single().attempt)
    }

    @Test
    fun `the ticker fires reminders at their due times on the guardian's dispatcher`() = runTest {
        val vclock = SchedulerClock(testScheduler)
        val g = Guardian(vclock, fees).also { it.startSession("s", listOf(Provider.UBER, Provider.RAPIDO)) }
        val seen = mutableListOf<Long>()
        g.effectListener = { if (it is GuardianEffect.Reminder) seen += testScheduler.currentTime }
        backgroundScope.launch { GuardianTicker(g, vclock).run() }
        g.onUserAction(UserAction.BookedHere("s:uber", TapId("a")))
        g.onUserAction(UserAction.BookedHere("s:rapido", TapId("b")))
        g.onRideEvent("s:rapido", ProviderUpdate(NotificationEvent.ASSIGNED, SignalSource.TEMPLATE, "k", "t"))
        g.onUserAction(UserAction.AnswerSheet(g.state.sheets.single().sheetId, SheetChoice.YES_CANCEL_OTHERS, TapId("c")))
        testScheduler.advanceTimeBy(250_000)
        testScheduler.runCurrent()
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L), seen)
    }

    @Test
    fun `compare again gives ended legs a fresh leg and keeps live ones`() {
        val g = guardian(Provider.UBER, Provider.OLA)
        g.book(Provider.UBER, Provider.OLA)
        g.tpl(Provider.UBER, NotificationEvent.NO_DRIVERS)
        g.tpl(Provider.OLA, NotificationEvent.SEARCHING)
        g.onUserAction(UserAction.CompareAgain(tap()))
        assertEquals(listOf("s:ola", "s:uber:r2"), g.state.legs.keys.sorted())
        assertEquals(RideState.SEARCHING, g.state.legs.getValue("s:ola").leg.state)
        assertEquals(RideState.SELECTED, g.state.legs.getValue("s:uber:r2").leg.state)
        assertEquals(listOf("s:uber"), g.state.retiredLegs.map { it.legId })
        assertEquals("s:uber:r2", g.legFor("com.ubercab")!!.legId)
    }

    @Test
    fun `Not now on the first sheet keeps everything, and a second driver then asks to pick one`() {
        val g = guardian(Provider.UBER, Provider.RAPIDO)
        g.book(Provider.UBER, Provider.RAPIDO)
        g.tpl(Provider.RAPIDO, NotificationEvent.ASSIGNED, plate = "r")
        g.onUserAction(UserAction.AnswerSheet(g.state.sheets.single().sheetId, SheetChoice.NOT_NOW, tap()))
        assertTrue(g.state.sheets.isEmpty())
        assertNull(g.state.session!!.keptLegId)
        clock.advanceBy(120_000)
        g.tpl(Provider.UBER, NotificationEvent.ASSIGNED, plate = "u")
        assertEquals(SheetKind.TWO_DRIVERS, g.state.sheets.single().kind)
        assertEquals(RideState.ASSIGNED, g.state(Provider.UBER))
    }
}
