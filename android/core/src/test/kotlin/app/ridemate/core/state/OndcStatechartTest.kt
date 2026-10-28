package app.ridemate.core.state

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.model.RideState.ARRIVING
import app.ridemate.core.model.RideState.ASSIGNED
import app.ridemate.core.model.RideState.CANCEL_CONFIRMED
import app.ridemate.core.model.RideState.CONFIRMED
import app.ridemate.core.model.RideState.CONFIRM_CANCEL
import app.ridemate.core.model.RideState.ENDED
import app.ridemate.core.model.RideState.INIT
import app.ridemate.core.model.RideState.ON_SELECT
import app.ridemate.core.model.RideState.ON_TRIP
import app.ridemate.core.model.RideState.PAY_HANDOFF
import app.ridemate.core.model.RideState.PROVIDER_CANCELLED
import app.ridemate.core.model.RideState.SEARCHING_NET
import app.ridemate.core.model.RideState.SEARCH_EXPIRED
import app.ridemate.core.model.RideState.SELECTED
import app.ridemate.core.model.RideState.SOFT_CANCEL
import app.ridemate.core.time.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §4.4 ONDC chain: SEARCHING_NET → ON_SELECT → INIT → CONFIRMED → … → SOFT_CANCEL → [tap] CONFIRM_CANCEL → CANCEL_CONFIRMED. */
class OndcStatechartTest {
    private val now = Moment(5_000L, 1_793_163_600_000L)
    private val tap = TapId("tap-1")

    private fun leg(state: RideState, returnState: RideState? = null) = LegState("N", Provider.ONDC, state, returnState = returnState)

    private fun LegState.on(event: RideEvent) = Statechart.transition(this, event, now)

    private fun p(kind: ProtocolKind, fee: Int? = null, eta: Int? = null, tapId: TapId? = null, id: String? = null) =
        Protocol(kind, feeInr = fee, etaMin = eta, distanceMeters = eta?.let { it * 300 }, tapId = tapId, messageId = id)

    @Test
    fun `book through the network after a tap, then ride to the end and hand off payment`() {
        var l = leg(SELECTED)
        l = l.on(p(ProtocolKind.SEARCH_STARTED)).after.also { assertEquals(SEARCHING_NET, it.state) }
        assertEquals(LogReason.QUOTES_UPDATED, l.on(p(ProtocolKind.QUOTES)).logReason)
        l = l.on(p(ProtocolKind.SELECTED)).after.also { assertEquals(ON_SELECT, it.state) }
        l = l.on(p(ProtocolKind.INITIALIZED)).after.also { assertEquals(INIT, it.state) }
        assertEquals("I1: no confirm without the user's tap", LogReason.MISSING_TAP, l.on(p(ProtocolKind.CONFIRMED)).logReason)
        l = l.on(p(ProtocolKind.CONFIRMED, tapId = tap)).after.also { assertEquals(CONFIRMED, it.state) }
        l = l.on(p(ProtocolKind.DRIVER_ASSIGNED, eta = 4)).after
        assertEquals(ASSIGNED, l.state)
        assertEquals(EtaSource.NETWORK, l.driver.etaSource)
        assertEquals(4, l.driver.etaMin)
        assertEquals(1200, l.driver.distanceMeters)
        val refreshed = l.on(p(ProtocolKind.DRIVER_ASSIGNED, eta = 2))
        assertEquals(StateOutput.DriverUpdated, refreshed.outputs.single())
        l = refreshed.after.on(p(ProtocolKind.ARRIVING)).after.also { assertEquals(ARRIVING, it.state) }
        l = l.on(p(ProtocolKind.TRIP_STARTED)).after.also { assertEquals(ON_TRIP, it.state) }
        l = l.on(p(ProtocolKind.ENDED)).after.also { assertEquals(ENDED, it.state) }
        assertEquals(PAY_HANDOFF, l.on(UserAnswer(Answer.PAY, tap)).after.state)
    }

    @Test
    fun `soft cancel shows the fee, and only the user's confirm tap leads to the protocol cancel`() {
        for (from in listOf(CONFIRMED, ASSIGNED, ARRIVING)) {
            val soft = leg(from).on(p(ProtocolKind.SOFT_CANCEL_QUOTED, fee = 25))
            assertEquals(SOFT_CANCEL, soft.after.state)
            assertEquals(25, soft.after.ondcFeeInr)
            assertEquals(StateOutput.OndcFeeShown(25), soft.outputs.single())
            assertEquals("on_cancel before the confirm tap is the seller's own cancel", PROVIDER_CANCELLED, soft.after.on(p(ProtocolKind.CANCEL_CONFIRMED)).after.state)
            val confirm = soft.after.on(UserAnswer(Answer.ONDC_CONFIRM_CANCEL, tap))
            assertEquals(CONFIRM_CANCEL, confirm.after.state)
            assertEquals(from, confirm.after.underlyingState)
            val done = confirm.after.on(p(ProtocolKind.CANCEL_CONFIRMED))
            assertEquals(CANCEL_CONFIRMED, done.after.state)
            assertEquals(EvidenceRung.ONDC_ON_CANCEL, done.after.confirmedBy)
            assertEquals(StateOutput.CancelConfirmed(EvidenceRung.ONDC_ON_CANCEL), done.outputs.single())
            assertEquals("keep the ride", from, soft.after.on(UserAnswer(Answer.ONDC_KEEP_RIDE, tap)).after.state)
        }
        val requote = leg(SOFT_CANCEL, returnState = ASSIGNED).on(p(ProtocolKind.SOFT_CANCEL_QUOTED, fee = 40))
        assertEquals(40, requote.after.ondcFeeInr)
        assertEquals(LogReason.ANSWER_NOT_APPLICABLE, leg(CONFIRMED).on(UserAnswer(Answer.ONDC_CONFIRM_CANCEL, tap)).logReason)
    }

    @Test
    fun `failures end the leg as expired before confirmation and as provider-cancelled after`() {
        assertEquals(SEARCH_EXPIRED, leg(SEARCHING_NET).on(p(ProtocolKind.FAILED)).after.state)
        assertEquals(SEARCH_EXPIRED, leg(INIT).on(p(ProtocolKind.FAILED)).after.state)
        assertEquals(PROVIDER_CANCELLED, leg(CONFIRMED).on(p(ProtocolKind.FAILED)).after.state)
        assertEquals(PROVIDER_CANCELLED, leg(ASSIGNED).on(p(ProtocolKind.CANCEL_CONFIRMED)).after.state)
        assertEquals(LogReason.NO_TRANSITION, leg(ON_TRIP).on(p(ProtocolKind.FAILED)).logReason)
        assertEquals(LogReason.TERMINAL_STATE, leg(CANCEL_CONFIRMED).on(p(ProtocolKind.FAILED)).logReason)
    }

    @Test
    fun `protocol messages are idempotent on message id, and ride-app events never touch an ONDC leg`() {
        val first = leg(INIT).on(p(ProtocolKind.CONFIRMED, tapId = tap, id = "m1")).after
        val assigned = first.on(p(ProtocolKind.DRIVER_ASSIGNED, eta = 3, id = "m2")).after
        assertEquals(LogReason.IDEMPOTENT, assigned.on(p(ProtocolKind.DRIVER_ASSIGNED, eta = 9, id = "m2")).logReason)
        assertEquals(LogReason.WRONG_LEG_KIND, assigned.on(ProviderUpdate(NotificationEvent.CANCELLED, SignalSource.TEMPLATE, "k")).logReason)
        assertEquals(LogReason.WRONG_LEG_KIND, assigned.on(UnknownUpdate(UnknownReason.REDACTED, "k")).logReason)
        assertEquals(LogReason.WRONG_LEG_KIND, assigned.on(Chain(ChainStep.QUEUE)).logReason)
        assertNull(assigned.on(Chain(ChainStep.QUEUE)).after.providerState)
    }
}
