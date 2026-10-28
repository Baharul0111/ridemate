package app.ridemate.core.state

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.time.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StatechartBehaviourTest {
    private var now = Moment(1_000_000L, 1_793_163_600_000L)

    private fun fresh(state: RideState = RideState.SEARCHING) = LegState("L", Provider.RAPIDO, state)

    private fun LegState.on(event: RideEvent): Transition = Statechart.transition(this, event, now)

    private fun tpl(
        signal: NotificationEvent,
        key: String = "k1",
        plate: String? = null,
        eta: Int? = null,
        source: SignalSource = SignalSource.TEMPLATE,
        postTime: Long = now.wallMs,
        replayed: Boolean = false,
    ) = ProviderUpdate(signal, source, key, "tpl", eta, null, plate, postTime, replayed)

    @Test
    fun `transitions are idempotent on event type, sbn key and generation`() {
        val assigned = fresh().on(tpl(NotificationEvent.ASSIGNED, plate = "p1", eta = 6)).after
        val again = assigned.on(tpl(NotificationEvent.ASSIGNED, plate = "p1", eta = 6))
        assertEquals(LogReason.IDEMPOTENT, again.logReason)
        // A repeat with a fresher countdown is still idempotent but keeps the numbers current.
        now = now.copy(elapsedMs = now.elapsedMs + 60_000)
        val fresher = assigned.on(tpl(NotificationEvent.ASSIGNED, plate = "p1", eta = 5))
        assertEquals(LogReason.IDEMPOTENT, fresher.logReason)
        assertTrue(StateOutput.DriverUpdated in fresher.outputs)
        assertEquals(5, fresher.after.driver.etaMin)
    }

    @Test
    fun `the same key alternating searching and assigned is never mistaken for a repeat`() {
        var leg = fresh().on(tpl(NotificationEvent.SEARCHING)).after
        leg = leg.on(tpl(NotificationEvent.ASSIGNED, plate = "p1")).after
        val research = leg.on(tpl(NotificationEvent.SEARCHING))
        assertEquals(RideState.SEARCHING, research.after.state)
        assertEquals(1, research.after.generation)
        val reassigned = research.after.on(tpl(NotificationEvent.ASSIGNED, plate = "p1"))
        assertEquals(RideState.ASSIGNED, reassigned.after.state)
        assertEquals(1, reassigned.after.generation)
    }

    @Test
    fun `an unknown update on the key in between lets the same template resolve MAYBE_CHANGED`() {
        val assigned = fresh().on(tpl(NotificationEvent.ASSIGNED, plate = "p1")).after
        val maybe = assigned.on(UnknownUpdate(UnknownReason.CLASSIFIER_LABEL, "k1")).after
        assertEquals(RideState.MAYBE_CHANGED, maybe.state)
        val back = maybe.on(tpl(NotificationEvent.ASSIGNED, plate = "p1"))
        assertEquals(RideState.ASSIGNED, back.after.state)
        assertEquals(StateOutput.WithdrawPrompt(PromptKind.MAYBE_CHANGED, WithdrawReason.SAME_DRIVER_STILL_COMING), back.outputs.first())
    }

    @Test
    fun `a replayed post older than the leg's evidence is stale`() {
        val searching = fresh(RideState.BOOK_REQUESTED).on(tpl(NotificationEvent.SEARCHING, key = "old", postTime = now.wallMs - 60_000)).after
        val assigned = searching.on(tpl(NotificationEvent.ASSIGNED, key = "new", plate = "p1")).after
        val driverCancelled = assigned.on(tpl(NotificationEvent.DRIVER_CANCELLED, key = "new")).after
        val reassigned = driverCancelled.on(tpl(NotificationEvent.ASSIGNED, key = "new2", plate = "p2")).after
        val replay = reassigned.on(tpl(NotificationEvent.SEARCHING, key = "old", postTime = now.wallMs - 60_000, replayed = true))
        assertEquals(LogReason.STALE_REPLAY, replay.logReason)
        assertEquals(RideState.ASSIGNED, replay.after.state)
        val replayedUnknown = reassigned.on(UnknownUpdate(UnknownReason.REDACTED, "pin", now.wallMs - 60_000, replayed = true))
        assertEquals(LogReason.STALE_REPLAY, replayedUnknown.logReason)
    }

    @Test
    fun `classifier labels only propose SEARCHING or ASSIGNED and never resolve or end anything`() {
        assertThrows(IllegalArgumentException::class.java) { ProviderUpdate(NotificationEvent.CANCELLED, SignalSource.CLASSIFIER) }
        assertThrows(IllegalArgumentException::class.java) { ProviderUpdate(NotificationEvent.ENDED, SignalSource.CLASSIFIER) }
        assertThrows(IllegalArgumentException::class.java) { ProviderUpdate(NotificationEvent.ASSIGNED, SignalSource.CLASSIFIER, etaMin = 3) }
        fun classified(signal: NotificationEvent) = ProviderUpdate(signal, SignalSource.CLASSIFIER, "k9")

        val fromSearching = fresh().on(classified(NotificationEvent.ASSIGNED))
        assertEquals(RideState.ASSIGNED, fromSearching.after.state)
        assertNull("eta stays 'see in app'", fromSearching.after.driver.etaMin)
        assertEquals(EtaSource.NONE, fromSearching.after.driver.etaSource)

        assertEquals(RideState.MAYBE_ASSIGNED, fresh(RideState.BOOK_REQUESTED).on(classified(NotificationEvent.ASSIGNED)).after.state)
        assertEquals(RideState.SEARCHING, fresh(RideState.BOOK_REQUESTED).on(classified(NotificationEvent.SEARCHING)).after.state)
        assertEquals(RideState.SEARCHING, fresh(RideState.DRIVER_CANCELLED).on(classified(NotificationEvent.SEARCHING)).after.state)
        assertEquals(RideState.MAYBE_CHANGED, fresh(RideState.ASSIGNED).on(classified(NotificationEvent.SEARCHING)).after.state)
        assertTrue(fresh(RideState.MAYBE_ASSIGNED).on(classified(NotificationEvent.ASSIGNED)).isDupDrop)
        assertEquals(RideState.OPENED, fresh(RideState.OPENED).on(classified(NotificationEvent.ASSIGNED)).after.state)
        val inChain = fresh(RideState.CANCEL_GUIDED).copy(providerState = RideState.SEARCHING).on(classified(NotificationEvent.ASSIGNED))
        assertEquals(listOf(StateOutput.CheckScreenPrompt), inChain.outputs)
    }

    @Test
    fun `I3 - random event sequences reach CANCEL_CONFIRMED only through rungs 1 to 3`() {
        val rnd = Random(42)
        val signals = NotificationEvent.entries
        repeat(2_000) {
            var leg = fresh(RideState.BOOK_REQUESTED)
            repeat(25) { step ->
                now = now.copy(elapsedMs = now.elapsedMs + rnd.nextLong(0, 90_000), wallMs = now.wallMs + 1)
                val event: RideEvent = when (rnd.nextInt(9)) {
                    0, 1 -> tpl(signals.random(rnd), key = "k${rnd.nextInt(3)}", plate = "p${rnd.nextInt(2)}",
                        source = if (rnd.nextBoolean()) SignalSource.TEMPLATE else SignalSource.STRUCTURAL_TEMPLATE)
                    2 -> UnknownUpdate(UnknownReason.REDACTED, "k${rnd.nextInt(3)}")
                    3 -> UserAnswer(Answer.entries.random(rnd), TapId("t$step"))
                    4 -> Timeout(TimeoutKind.entries.random(rnd), rnd.nextBoolean())
                    5 -> Chain(ChainStep.entries.random(rnd))
                    6 -> NotificationRemoved("k${rnd.nextInt(3)}", if (rnd.nextBoolean()) 8 else 2, rnd.nextBoolean(), rnd.nextBoolean())
                    7 -> CaptureVerdict(rnd.nextBoolean(), TapId("t$step"))
                    else -> ProviderUpdate(listOf(NotificationEvent.SEARCHING, NotificationEvent.ASSIGNED).random(rnd), SignalSource.CLASSIFIER, "k0")
                }
                val t = leg.on(event)
                if (t.after.state == RideState.CANCEL_CONFIRMED && t.before.state != RideState.CANCEL_CONFIRMED) {
                    val rung = t.after.confirmedBy
                    assertTrue("confirmed by $rung via $event", rung != null && rung.confirms)
                    assertTrue(t.output<StateOutput.CancelConfirmed>() != null)
                    assertTrue(t.before.state in LegState.CHAIN_STATES)
                    if (event is ProviderUpdate) assertEquals(SignalSource.TEMPLATE, event.source)
                }
                leg = t.after
            }
        }
    }

    @Test
    fun `a structural cancelled match is not rung 2`() {
        val guided = fresh(RideState.CANCEL_GUIDED).copy(providerState = RideState.SEARCHING)
        val t = guided.on(tpl(NotificationEvent.CANCELLED, source = SignalSource.STRUCTURAL_TEMPLATE))
        assertEquals(RideState.CANCEL_GUIDED, t.after.state)
        assertEquals(listOf(StateOutput.CheckScreenPrompt), t.outputs)
    }

    @Test
    fun `rung 3 needs the APP_CANCEL removal of an ongoing post and a cancelled capture within 5 minutes`() {
        val guided = fresh(RideState.CANCEL_GUIDED).copy(providerState = RideState.SEARCHING)
        assertEquals(LogReason.REMOVAL_RECORDED, guided.on(NotificationRemoved("k", 2, appCancel = false, wasOngoing = true)).logReason)
        assertEquals(LogReason.REMOVAL_RECORDED, guided.on(NotificationRemoved("k", 8, appCancel = true, wasOngoing = false)).logReason)
        val removed = guided.on(NotificationRemoved("k", 8, appCancel = true, wasOngoing = true))
        assertTrue(StateOutput.RequestCapture(CapturePurpose.CANCEL_PROOF) in removed.outputs)
        assertEquals(RideState.CANCEL_GUIDED, removed.after.state)

        now = now.copy(elapsedMs = now.elapsedMs + 4 * 60_000)
        assertEquals(LogReason.CAPTURE_RECORDED, removed.after.on(CaptureVerdict(false, TapId("t"))).logReason)
        val confirmed = removed.after.on(CaptureVerdict(true, TapId("t")))
        assertEquals(RideState.CANCEL_CONFIRMED, confirmed.after.state)
        assertEquals(EvidenceRung.REMOVAL_PLUS_CAPTURE, confirmed.after.confirmedBy)

        now = now.copy(elapsedMs = now.elapsedMs + 2 * 60_000)
        val late = removed.after.on(CaptureVerdict(true, TapId("t")))
        assertEquals("6 minutes apart", RideState.CANCEL_GUIDED, late.after.state)

        val captureFirst = guided.on(CaptureVerdict(true, TapId("t"))).after
        val thenRemoval = captureFirst.on(NotificationRemoved("k", 8, appCancel = true, wasOngoing = true))
        assertEquals(RideState.CANCEL_CONFIRMED, thenRemoval.after.state)
    }

    @Test
    fun `the user-cancel chain queues, guides and aborts back to the provider state`() {
        val maybe = fresh(RideState.MAYBE_CHANGED).copy(returnState = RideState.ARRIVING)
        val queued = maybe.on(Chain(ChainStep.QUEUE))
        assertEquals(RideState.CANCEL_REQUESTED, queued.after.state)
        assertEquals(RideState.ARRIVING, queued.after.providerState)
        assertEquals(StateOutput.WithdrawPrompt(PromptKind.MAYBE_CHANGED, WithdrawReason.CANCELLING), queued.outputs.single())
        val guided = queued.after.on(Chain(ChainStep.GUIDE))
        assertEquals(RideState.CANCEL_GUIDED, guided.after.state)
        val aborted = guided.after.on(Chain(ChainStep.ABORT))
        assertEquals(RideState.ARRIVING, aborted.after.state)
        assertNull(aborted.after.providerState)
        assertEquals(LogReason.NO_TRANSITION, fresh(RideState.ON_TRIP).on(Chain(ChainStep.QUEUE)).logReason)
        assertEquals(LogReason.NO_TRANSITION, fresh(RideState.QUOTED).on(Chain(ChainStep.QUEUE)).logReason)
        val unconfirmed = fresh(RideState.CANCEL_UNCONFIRMED).copy(providerState = RideState.SEARCHING)
        assertEquals(RideState.CANCEL_GUIDED, unconfirmed.on(Chain(ChainStep.GUIDE)).after.state)
    }

    @Test
    fun `lifecycle moves the pre-booking states forward only`() {
        val opened = fresh(RideState.SELECTED).on(Lifecycle(LifecycleStep.OPENED)).after
        assertEquals(RideState.OPENED, opened.state)
        assertEquals(RideState.QUOTED, opened.on(Lifecycle(LifecycleStep.QUOTED)).after.state)
        assertEquals(LogReason.NO_TRANSITION, fresh(RideState.SEARCHING).on(Lifecycle(LifecycleStep.OPENED)).logReason)
        assertEquals(LogReason.WRONG_LEG_KIND, fresh().on(Protocol(ProtocolKind.CONFIRMED)).logReason)
    }

    @Test
    fun `driver data rules - plates, generations and admissible values`() {
        val assigned = fresh().on(tpl(NotificationEvent.ASSIGNED, plate = "p1", eta = 8)).after
        assertEquals(EtaSource.TEMPLATE, assigned.driver.etaSource)
        assertEquals(now.elapsedMs, assigned.driver.assignedAtElapsedMs)
        val cancelled = assigned.on(tpl(NotificationEvent.DRIVER_CANCELLED)).after
        assertNull("the old driver is gone", cancelled.driver.plateHash)
        val rematched = cancelled.on(tpl(NotificationEvent.ASSIGNED, plate = "p2")).after
        assertEquals("no extra bump after DRIVER_CANCELLED", 1, rematched.generation)

        val yes = fresh(RideState.MAYBE_ASSIGNED).on(UserAnswer(Answer.YES, TapId("t"))).after
        assertNull(yes.driver.etaMin)
        val enriched = yes.on(tpl(NotificationEvent.ASSIGNED, plate = "p1", eta = 4))
        assertEquals("a first plate is adopted, not a new driver", 0, enriched.after.generation)
        assertEquals("p1", enriched.after.driver.plateHash)
        assertEquals(4, enriched.after.driver.etaMin)

        val verified = assigned.on(DriverData(3, 700, EtaSource.VERIFIED))
        assertEquals(StateOutput.DriverUpdated, verified.outputs.single())
        assertEquals(EtaSource.VERIFIED, verified.after.driver.etaSource)
        assertEquals(LogReason.NOT_A_DRIVER_STATE, assigned.on(DriverData(3, null, EtaSource.NONE)).logReason)
        assertEquals(LogReason.NOT_A_DRIVER_STATE, fresh().on(DriverData(3, null, EtaSource.TEMPLATE)).logReason)

        val screen = fresh(RideState.MAYBE_ASSIGNED).on(ProviderUpdate(NotificationEvent.ASSIGNED, SignalSource.SCREEN_CHECK, etaMin = 6))
        assertEquals(EtaSource.VERIFIED, screen.after.driver.etaSource)
    }

    @Test
    fun `stateSeq moves only when the state or generation changes`() {
        val start = fresh()
        val dup = start.on(tpl(NotificationEvent.SEARCHING))
        assertEquals(start.stateSeq, dup.after.stateSeq)
        val moved = start.on(tpl(NotificationEvent.ASSIGNED, plate = "p1"))
        assertEquals(start.stateSeq + 1, moved.after.stateSeq)
        assertEquals(now.elapsedMs, moved.after.enteredAtElapsedMs)
    }

    @Test
    fun `remaining ETA counts down from the reading and is hidden without an admissible source`() {
        val driver = DriverInfo(etaMin = 7, etaSource = EtaSource.TEMPLATE, etaAtElapsedMs = 0)
        assertEquals(7, driver.remainingEtaMin(59_999))
        assertEquals(5, driver.remainingEtaMin(120_000))
        assertEquals(0, driver.remainingEtaMin(60 * 60_000))
        assertNull(driver.copy(etaSource = EtaSource.NONE).remainingEtaMin(0))
    }
}
