package app.ridemate.notify

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.guardian.FeeRules
import app.ridemate.core.guardian.Guardian
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.RuleBasedFeeEstimator
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.ProviderUpdate
import app.ridemate.core.state.SignalSource
import app.ridemate.core.state.TapId
import app.ridemate.core.state.UnknownReason
import app.ridemate.core.state.UnknownUpdate
import app.ridemate.core.time.ManualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianUiReducerTest {
    private val clock = ManualClock()
    private val guardian = Guardian(clock, RuleBasedFeeEstimator(FeeRules()))
    private var ui = GuardianUiState.EMPTY

    init {
        guardian.effectListener = { ui = GuardianUiReducer.apply(ui, it, clock.wallMs()) }
    }

    private fun reconcile() {
        ui = GuardianUiReducer.reconcile(ui, guardian.state, clock.elapsedMs(), clock.wallMs(), null, stopBlocked = false)
    }

    private fun leg(provider: Provider) = "s:${provider.wireId}"

    private fun book(vararg providers: Provider) {
        guardian.startSession("s", providers.toList())
        providers.forEach { guardian.onUserAction(UserAction.BookedHere(leg(it), TapId("b-${it.name}"))) }
        reconcile()
    }

    private fun update(provider: Provider, signal: NotificationEvent, key: String, eta: Int? = null) {
        guardian.onRideEvent(leg(provider), ProviderUpdate(signal, SignalSource.TEMPLATE, key, etaMin = eta, postTimeWallMs = clock.wallMs()))
        reconcile()
    }

    @Test
    fun `legs show state, role, driver values with their source, and the proof`() {
        book(Provider.UBER, Provider.RAPIDO)
        update(Provider.RAPIDO, NotificationEvent.ASSIGNED, "r1", eta = 6)
        val sheet = ui.items.filterIsInstance<DriverSheetItem>().single()
        guardian.onUserAction(UserAction.AnswerSheet(sheet.id, app.ridemate.core.guardian.SheetChoice.YES_CANCEL_OTHERS, TapId("yes")))
        reconcile()

        val rapido = ui.leg(leg(Provider.RAPIDO))!!
        assertEquals(LegRole.KEPT, rapido.role)
        assertEquals(6, rapido.driver!!.etaMin)
        assertEquals(EtaSource.TEMPLATE, rapido.driver!!.etaSource)
        // The countdown runs on the driver's reading time.
        clock.advanceBy(2 * 60_000L)
        reconcile()
        assertEquals(4, ui.leg(leg(Provider.RAPIDO))!!.driver!!.etaMin)

        val uber = ui.leg(leg(Provider.UBER))!!
        assertEquals(LegRole.EXTRA, uber.role)
        assertEquals(RideState.CANCEL_GUIDED, uber.state)
        assertTrue(ui.items.any { it is CancelCardItem && it.legId == uber.legId })
        assertTrue(ui.items.none { it is DriverSheetItem })

        update(Provider.UBER, NotificationEvent.CANCELLED, "u9")
        val proven = ui.leg(leg(Provider.UBER))!!
        assertEquals(RideState.CANCEL_CONFIRMED, proven.state)
        assertEquals(app.ridemate.core.model.EvidenceRung.TEMPLATE, proven.evidence)
        assertTrue(ui.items.none { it is CancelCardItem })
        assertTrue(ui.notices.any { it.key == UiKey.BANNER_CANCEL_CONFIRMED && it.providers == listOf(Provider.UBER) })
    }

    @Test
    fun `a your-ride notice stays only while that driver is assigned or arriving (I10)`() {
        book(Provider.UBER, Provider.RAPIDO)
        update(Provider.RAPIDO, NotificationEvent.ASSIGNED, "r1", eta = 6)
        val sheet = ui.items.filterIsInstance<DriverSheetItem>().single()
        guardian.onUserAction(UserAction.AnswerSheet(sheet.id, app.ridemate.core.guardian.SheetChoice.YES_CANCEL_OTHERS, TapId("yes")))
        reconcile()
        val yourRide = ui.notices.single { it.key == UiKey.BANNER_YOUR_RIDE }
        assertEquals(leg(Provider.RAPIDO), yourRide.legId)

        update(Provider.RAPIDO, NotificationEvent.ARRIVING, "r1", eta = 2)
        assertTrue(ui.notices.any { it.id == yourRide.id })

        // The captain cancels: that generation is no longer anyone's ride, so its announcement goes at once.
        update(Provider.RAPIDO, NotificationEvent.DRIVER_CANCELLED, "r1")
        assertTrue(ui.notices.none { it.key == UiKey.BANNER_YOUR_RIDE })
        assertTrue(ui.notices.any { it.key == UiKey.BANNER_FINDING_ANOTHER_CAPTAIN })

        // A new captain is a new generation with its own announcement.
        update(Provider.RAPIDO, NotificationEvent.ASSIGNED, "r1", eta = 3)
        val again = ui.notices.single { it.key == UiKey.BANNER_YOUR_RIDE }
        assertEquals(1, again.driver!!.generation)
    }

    @Test
    fun `a MAYBE prompt is replaced by a withdrawal notice when evidence answers it`() {
        book(Provider.UBER, Provider.RAPIDO)
        update(Provider.RAPIDO, NotificationEvent.SEARCHING, "r1")
        guardian.onRideEvent(leg(Provider.RAPIDO), UnknownUpdate(UnknownReason.REDACTED, "r1", clock.wallMs()))
        reconcile()
        val prompt = ui.items.filterIsInstance<PromptItem>().single()
        assertEquals(listOf(UiAction.YES_CANCEL_OTHERS, UiAction.NOT_YET, UiAction.CHECK_SCREEN), prompt.actions)

        update(Provider.RAPIDO, NotificationEvent.NO_DRIVERS, "r2")
        assertTrue(ui.items.none { it is PromptItem })
        val notice = ui.notices.first { it.key == UiKey.PROMPT_WITHDRAWN_SEARCH_EXPIRED }
        assertEquals(listOf(Provider.RAPIDO), notice.providers)
        assertTrue(ui.notices.any { it.key == UiKey.BANNER_ENDED_BY_APP })
    }

    @Test
    fun `reconcile drops items the guardian no longer backs and the UI state round-trips as JSON`() {
        book(Provider.OLA)
        val stale = PromptItem("prompt-404", leg(Provider.OLA), Provider.OLA, 0, UiKey.PROMPT_MAYBE_ASSIGNED, listOf(UiAction.YES), 0)
        ui = ui.copy(items = ui.items + stale)
        reconcile()
        assertTrue(ui.items.none { it.id == "prompt-404" })

        ui = GuardianUiReducer.apply(ui, GuardianEffect.Reminder(1, emptyList(), headsUp = true, bubblePulse = true), clock.wallMs())
        val decoded = GuardianUiState.decode(GuardianUiState.encode(ui))
        assertEquals(ui, decoded)
        assertNull(GuardianUiState.decode("{not json"))
    }

    @Test
    fun `notices are capped and expire`() {
        repeat(10) { ui = GuardianUiReducer.apply(ui, GuardianEffect.AnswerRejected(TapId("t$it")), clock.wallMs()) }
        assertEquals(GuardianUiReducer.MAX_NOTICES, ui.notices.size)
        assertEquals("notice:10", ui.notices.first().id)

        clock.advanceBy(GuardianUiReducer.NOTICE_TTL_MS)
        reconcile()
        assertTrue(ui.notices.isEmpty())
        assertEquals(ui, GuardianUiReducer.dismissNotice(ui, "missing"))
    }

    @Test
    fun `alerts outrank sheets and prompts for the active sheet`() {
        val prompt = PromptItem("p", "l", Provider.OLA, 0, UiKey.PROMPT_MAYBE_CHANGED, emptyList(), 0)
        val alert = AlertItem("l", Provider.OLA, UiKey.ALERT_STILL_LIVE, emptyList())
        val state = GuardianUiState(items = listOf(alert, prompt))
        assertEquals(alert, state.activeItem)
        assertEquals(prompt, state.copy(items = listOf(prompt)).activeItem)
    }

    @Test
    fun `button targets map to the guardian's user actions`() {
        val tap = TapId("x")
        assertEquals(
            UserAction.AnswerSheet("sheet", app.ridemate.core.guardian.SheetChoice.PICK, tap, "l2"),
            GuardianTap(TapScope.SHEET, "sheet", UiAction.PICK, pickedLegId = "l2").toUserAction(tap),
        )
        assertNull(GuardianTap(TapScope.SHEET, "sheet", UiAction.PICK).toUserAction(tap))
        // The tapped button travels with the answer: only "Yes, cancel the others" may start the cancel chain.
        assertEquals(
            UserAction.AnswerPrompt("p", app.ridemate.core.state.Answer.YES, tap, button = UiAction.YES_CANCEL_OTHERS),
            GuardianTap(TapScope.PROMPT, "p", UiAction.YES_CANCEL_OTHERS).toUserAction(tap),
        )
        assertEquals(
            UserAction.AnswerPrompt("p", app.ridemate.core.state.Answer.YES, tap, button = UiAction.YES),
            GuardianTap(TapScope.PROMPT, "p", UiAction.YES).toUserAction(tap),
        )
        assertEquals(
            UserAction.AnswerPrompt("p", app.ridemate.core.state.Answer.BOOKED_HERE, tap, button = UiAction.YES_BOOKED),
            GuardianTap(TapScope.PROMPT, "p", UiAction.YES_BOOKED).toUserAction(tap),
        )
        assertEquals(
            UserAction.AnswerPrompt("p", app.ridemate.core.state.Answer.NOT_BOOKED, tap, button = UiAction.NOT_BOOKED),
            GuardianTap(TapScope.PROMPT, "p", UiAction.NOT_BOOKED).toUserAction(tap),
        )
        assertEquals(UserAction.DismissLeg("l", tap), GuardianTap(TapScope.LEG, "l", UiAction.DISMISS).toUserAction(tap))
        assertEquals(UserAction.CompareAgain(tap), GuardianTap(TapScope.SESSION, null, UiAction.COMPARE_AGAIN).toUserAction(tap))
        val target = GuardianTap(TapScope.LEG, "l", UiAction.I_CANCELLED_IT)
        assertEquals(target, ActionTarget.decode(target.encode()))
        assertEquals(SessionOp(SessionOpKind.STOP), ActionTarget.decode(SessionOp(SessionOpKind.STOP).encode()))
        assertNull(ActionTarget.decode("garbage"))
    }
}
