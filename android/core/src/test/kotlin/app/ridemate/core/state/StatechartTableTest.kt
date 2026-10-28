package app.ridemate.core.state

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.model.RideState.ARRIVING
import app.ridemate.core.model.RideState.ASSIGNED
import app.ridemate.core.model.RideState.BOOK_REQUESTED
import app.ridemate.core.model.RideState.CANCEL_CONFIRMED
import app.ridemate.core.model.RideState.CANCEL_GUIDED
import app.ridemate.core.model.RideState.CANCEL_REQUESTED
import app.ridemate.core.model.RideState.CANCEL_UNCONFIRMED
import app.ridemate.core.model.RideState.DRIVER_CANCELLED
import app.ridemate.core.model.RideState.ENDED
import app.ridemate.core.model.RideState.MAYBE_ASSIGNED
import app.ridemate.core.model.RideState.MAYBE_CHANGED
import app.ridemate.core.model.RideState.MAYBE_NEW_DRIVER
import app.ridemate.core.model.RideState.ON_TRIP
import app.ridemate.core.model.RideState.OPENED
import app.ridemate.core.model.RideState.PAY_HANDOFF
import app.ridemate.core.model.RideState.PROVIDER_CANCELLED
import app.ridemate.core.model.RideState.QUOTED
import app.ridemate.core.model.RideState.SEARCHING
import app.ridemate.core.model.RideState.SEARCH_EXPIRED
import app.ridemate.core.model.RideState.SELECTED
import app.ridemate.core.model.RideState.USER_SAYS_CANCELLED
import app.ridemate.core.state.PromptKind.MAYBE_ASSIGNED as MA
import app.ridemate.core.state.PromptKind.MAYBE_CHANGED as MC
import app.ridemate.core.state.PromptKind.MAYBE_NEW_DRIVER as MND
import app.ridemate.core.time.Moment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The §4.4 transition table, one test per cell (state × event), asserting the next state, the generation and the
 * exact outputs. Rows beyond the table (SELECTED, trip-started, terminal states) document the remaining cells.
 */
@RunWith(Parameterized::class)
class StatechartTableTest(@Suppress("unused") private val name: String, private val cell: Cell) {

    data class Cell(val leg: LegState, val event: RideEvent, val next: RideState, val generationDelta: Int, val outputs: List<StateOutput>)

    @Test
    fun cell() {
        val t = Statechart.transition(cell.leg, cell.event, NOW)
        assertEquals("next state", cell.next, t.after.state)
        assertEquals("generation", cell.leg.generation + cell.generationDelta, t.after.generation)
        assertEquals("outputs", cell.outputs, t.outputs)
    }

    companion object {
        val NOW = Moment(1_000_000L, 1_793_163_600_000L)
        private const val PLATE = "plate-a"
        private const val NEW_PLATE = "plate-b"
        private val DRIVER = DriverInfo(
            etaMin = 5, etaSource = EtaSource.TEMPLATE, etaAtElapsedMs = NOW.elapsedMs, distanceMeters = 1200,
            distanceSource = EtaSource.TEMPLATE, plateHash = PLATE, assignedAtElapsedMs = 0, assignedAtWallMs = 0,
        )

        fun leg(state: RideState, returnState: RideState? = null, providerState: RideState? = null): LegState {
            val hasDriver = state == ASSIGNED || state == ARRIVING || state == MAYBE_CHANGED ||
                providerState == ASSIGNED || providerState == ARRIVING
            return LegState(
                legId = "L", provider = Provider.RAPIDO, state = state, generation = 3, stateSeq = 7,
                returnState = returnState, providerState = providerState, driver = if (hasDriver) DRIVER else DriverInfo(),
                // The capture half of rung 3 is already in, so the removal column completes the rung.
                cancelledCaptureAtElapsedMs = NOW.elapsedMs,
            )
        }

        fun tpl(signal: NotificationEvent, plate: String = PLATE): ProviderUpdate {
            val carriesDriver = signal == NotificationEvent.ASSIGNED || signal == NotificationEvent.ARRIVING
            return ProviderUpdate(
                signal, SignalSource.TEMPLATE, sbnKey = "k", templateId = "t",
                etaMin = if (carriesDriver) 5 else null, distanceMeters = if (carriesDriver) 1200 else null,
                plateHash = if (signal == NotificationEvent.ASSIGNED) plate else null, postTimeWallMs = NOW.wallMs,
            )
        }

        private val REMOVAL = NotificationRemoved("k", 8, appCancel = true, wasOngoing = true)
        private val UNKNOWN = UnknownUpdate(UnknownReason.REDACTED, "k", NOW.wallMs)
        private fun answer(a: Answer) = UserAnswer(a, TapId("tap"))
        private fun wd(kind: PromptKind, reason: WithdrawReason) = StateOutput.WithdrawPrompt(kind, reason)
        private fun raise(kind: PromptKind) = StateOutput.RaisePrompt(kind)
        private val GB = StateOutput.GenerationBumped(3, 4)
        private val LOG = StateOutput.Logged(LogReason.NO_TRANSITION)
        private val NA = StateOutput.Logged(LogReason.ANSWER_NOT_APPLICABLE)
        private val DUP = StateOutput.DupDrop
        private val TERMINAL = StateOutput.Logged(LogReason.TERMINAL_STATE)

        private val rows = mutableListOf<Array<Any>>()

        private fun row(leg: LegState, column: String, event: RideEvent, next: RideState, gen: Int = 0, vararg out: StateOutput) {
            val ret = leg.returnState?.let { "(→$it)" } ?: leg.providerState?.let { "(provider $it)" } ?: ""
            rows += arrayOf("${leg.state}$ret × $column", Cell(leg, event, next, gen, out.toList()))
        }

        init {
            // OPENED / QUOTED / BOOK_REQUESTED (+ SELECTED, treated the same way)
            for (s in listOf(SELECTED, OPENED, QUOTED, BOOK_REQUESTED)) {
                val l = leg(s)
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), SEARCHING)
                row(l, "assigned tpl", tpl(NotificationEvent.ASSIGNED), ASSIGNED)
                row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), ARRIVING)
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), s, 0, LOG)
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), SEARCH_EXPIRED)
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), PROVIDER_CANCELLED)
                row(l, "removal rung", REMOVAL, PROVIDER_CANCELLED)
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), s, 0, LOG)
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), s, 0, LOG)
                if (s == BOOK_REQUESTED) {
                    row(l, "UNKNOWN_UPDATE", UNKNOWN, MAYBE_ASSIGNED, 0, raise(MA))
                    row(l, "answer Booked here", answer(Answer.BOOKED_HERE), s, 0, DUP)
                } else {
                    row(l, "UNKNOWN_UPDATE", UNKNOWN, s, 0, LOG)
                    row(l, "answer Booked here", answer(Answer.BOOKED_HERE), BOOK_REQUESTED)
                }
                row(l, "answer Yes", answer(Answer.YES), s, 0, NA)
                row(l, "timeout", Timeout(TimeoutKind.REPROMPT), s, 0, LOG)
            }

            leg(SEARCHING).let { l ->
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), SEARCHING, 0, DUP)
                row(l, "assigned tpl", tpl(NotificationEvent.ASSIGNED), ASSIGNED)
                row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), ARRIVING)
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), SEARCHING, 0, LOG)
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), SEARCH_EXPIRED)
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), PROVIDER_CANCELLED)
                row(l, "removal rung", REMOVAL, PROVIDER_CANCELLED)
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), SEARCHING, 0, LOG)
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), SEARCHING, 0, LOG)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, MAYBE_ASSIGNED, 0, raise(MA))
                row(l, "answer Yes", answer(Answer.YES), SEARCHING, 0, NA)
                row(l, "timeout", Timeout(TimeoutKind.REPROMPT), SEARCHING, 0, LOG)
            }

            leg(MAYBE_ASSIGNED).let { l ->
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), SEARCHING, 0, wd(MA, WithdrawReason.SEARCHING_AGAIN))
                row(l, "assigned tpl", tpl(NotificationEvent.ASSIGNED), ASSIGNED, 0, wd(MA, WithdrawReason.DRIVER_ASSIGNED))
                row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), ARRIVING, 0, wd(MA, WithdrawReason.DRIVER_ARRIVING))
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), MAYBE_ASSIGNED, 0, LOG)
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), SEARCH_EXPIRED, 0, wd(MA, WithdrawReason.SEARCH_EXPIRED))
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), PROVIDER_CANCELLED, 0, wd(MA, WithdrawReason.PROVIDER_CANCELLED))
                row(l, "removal rung", REMOVAL, PROVIDER_CANCELLED, 0, wd(MA, WithdrawReason.PROVIDER_CANCELLED))
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), MAYBE_ASSIGNED, 0, LOG)
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), MAYBE_ASSIGNED, 0, LOG)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, MAYBE_ASSIGNED, 0, DUP)
                row(l, "answer Yes", answer(Answer.YES), ASSIGNED, 0, wd(MA, WithdrawReason.ANSWERED))
                row(l, "answer Not yet", answer(Answer.NOT_YET), SEARCHING, 0, wd(MA, WithdrawReason.ANSWERED))
                row(l, "answer Check", answer(Answer.CHECK_SCREEN), MAYBE_ASSIGNED, 0, StateOutput.RequestCapture(CapturePurpose.CHECK_SCREEN))
                row(l, "answer I cancelled it", answer(Answer.I_CANCELLED_IT), MAYBE_ASSIGNED, 0, NA)
                row(l, "timeout 2 min", Timeout(TimeoutKind.REPROMPT), MAYBE_ASSIGNED, 0, StateOutput.RePrompt(MA))
            }

            for (s in listOf(ASSIGNED, ARRIVING)) {
                val l = leg(s)
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), SEARCHING, 1, GB)
                row(l, "assigned tpl, same plate", tpl(NotificationEvent.ASSIGNED), s, 0, DUP)
                row(l, "assigned tpl, new plate", tpl(NotificationEvent.ASSIGNED, NEW_PLATE), ASSIGNED, 1, GB)
                if (s == ASSIGNED) {
                    row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), ARRIVING)
                } else {
                    row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), ARRIVING, 0, DUP)
                }
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), DRIVER_CANCELLED, 1, GB)
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), PROVIDER_CANCELLED)
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), PROVIDER_CANCELLED)
                row(l, "removal rung", REMOVAL, PROVIDER_CANCELLED)
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), ENDED)
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), ON_TRIP)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, MAYBE_CHANGED, 0, raise(MC))
                row(l, "answer Yes", answer(Answer.YES), s, 0, NA)
                row(l, "timeout", Timeout(TimeoutKind.REPROMPT), s, 0, LOG)
            }

            for (back in listOf(ASSIGNED, ARRIVING)) {
                val l = leg(MAYBE_CHANGED, returnState = back)
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), SEARCHING, 1, wd(MC, WithdrawReason.SEARCHING_AGAIN), GB)
                row(l, "assigned tpl, new plate", tpl(NotificationEvent.ASSIGNED, NEW_PLATE), ASSIGNED, 1, wd(MC, WithdrawReason.DRIVER_ASSIGNED), GB)
                row(l, "assigned tpl, same plate", tpl(NotificationEvent.ASSIGNED), back, 0, wd(MC, WithdrawReason.SAME_DRIVER_STILL_COMING))
                row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), ARRIVING, 0, wd(MC, WithdrawReason.DRIVER_ARRIVING))
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), DRIVER_CANCELLED, 1, wd(MC, WithdrawReason.DRIVER_CANCELLED), GB)
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), PROVIDER_CANCELLED, 0, wd(MC, WithdrawReason.PROVIDER_CANCELLED))
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), PROVIDER_CANCELLED, 0, wd(MC, WithdrawReason.PROVIDER_CANCELLED))
                row(l, "removal rung", REMOVAL, PROVIDER_CANCELLED, 0, wd(MC, WithdrawReason.PROVIDER_CANCELLED))
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), ENDED, 0, wd(MC, WithdrawReason.RIDE_ENDED))
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), ON_TRIP, 0, wd(MC, WithdrawReason.TRIP_STARTED))
                row(l, "UNKNOWN_UPDATE", UNKNOWN, MAYBE_CHANGED, 0, DUP)
                row(l, "answer Still coming", answer(Answer.YES), back, 0, wd(MC, WithdrawReason.ANSWERED))
                row(l, "answer Driver cancelled", answer(Answer.DRIVER_CANCELLED), DRIVER_CANCELLED, 1, wd(MC, WithdrawReason.ANSWERED), GB)
                row(l, "answer Check", answer(Answer.CHECK_SCREEN), MAYBE_CHANGED, 0, StateOutput.RequestCapture(CapturePurpose.CHECK_SCREEN))
                row(l, "answer Not yet", answer(Answer.NOT_YET), MAYBE_CHANGED, 0, NA)
                row(l, "timeout 2 min", Timeout(TimeoutKind.REPROMPT), MAYBE_CHANGED, 0, StateOutput.RePrompt(MC))
            }

            leg(DRIVER_CANCELLED).let { l ->
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), SEARCHING)
                row(l, "assigned tpl", tpl(NotificationEvent.ASSIGNED), ASSIGNED)
                row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), ARRIVING)
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), DRIVER_CANCELLED, 0, DUP)
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), PROVIDER_CANCELLED)
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), PROVIDER_CANCELLED)
                row(l, "removal rung", REMOVAL, PROVIDER_CANCELLED)
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), DRIVER_CANCELLED, 0, LOG)
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), DRIVER_CANCELLED, 0, LOG)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, MAYBE_NEW_DRIVER, 0, raise(MND))
                row(l, "answer Yes", answer(Answer.YES), DRIVER_CANCELLED, 0, NA)
                row(l, "timeout 3 min, ongoing post", Timeout(TimeoutKind.DRIVER_CANCELLED_WAIT, ongoingPresent = true), MAYBE_NEW_DRIVER, 0, raise(MND))
                row(l, "timeout 3 min, no ongoing post", Timeout(TimeoutKind.DRIVER_CANCELLED_WAIT), DRIVER_CANCELLED, 0, StateOutput.Logged(LogReason.NO_ONGOING_POST))
            }

            leg(MAYBE_NEW_DRIVER).let { l ->
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), SEARCHING, 0, wd(MND, WithdrawReason.SEARCHING_AGAIN))
                row(l, "assigned tpl", tpl(NotificationEvent.ASSIGNED), ASSIGNED, 0, wd(MND, WithdrawReason.DRIVER_ASSIGNED))
                row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), ARRIVING, 0, wd(MND, WithdrawReason.DRIVER_ARRIVING))
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), MAYBE_NEW_DRIVER, 0, DUP)
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), PROVIDER_CANCELLED, 0, wd(MND, WithdrawReason.PROVIDER_CANCELLED))
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), PROVIDER_CANCELLED, 0, wd(MND, WithdrawReason.PROVIDER_CANCELLED))
                row(l, "removal rung", REMOVAL, PROVIDER_CANCELLED, 0, wd(MND, WithdrawReason.PROVIDER_CANCELLED))
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), MAYBE_NEW_DRIVER, 0, LOG)
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), MAYBE_NEW_DRIVER, 0, LOG)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, MAYBE_NEW_DRIVER, 0, DUP)
                row(l, "answer Yes", answer(Answer.YES), SEARCHING, 0, wd(MND, WithdrawReason.ANSWERED))
                row(l, "answer No, it ended", answer(Answer.NO_IT_ENDED), PROVIDER_CANCELLED, 0, wd(MND, WithdrawReason.ANSWERED))
                row(l, "answer Check", answer(Answer.CHECK_SCREEN), MAYBE_NEW_DRIVER, 0, StateOutput.RequestCapture(CapturePurpose.CHECK_SCREEN))
                row(l, "timeout 2 min", Timeout(TimeoutKind.REPROMPT), MAYBE_NEW_DRIVER, 0, StateOutput.RePrompt(MND))
            }

            leg(ON_TRIP).let { l ->
                for (signal in NotificationEvent.entries.filter { it != NotificationEvent.ENDED }) {
                    row(l, "${signal.name.lowercase()} tpl", tpl(signal), ON_TRIP, 0, LOG)
                }
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), ENDED)
                row(l, "removal rung", REMOVAL, ON_TRIP, 0, LOG)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, ON_TRIP, 0, StateOutput.Logged(LogReason.IGNORED_ON_TRIP))
                row(l, "answer Yes", answer(Answer.YES), ON_TRIP, 0, NA)
                row(l, "timeout", Timeout(TimeoutKind.REPROMPT), ON_TRIP, 0, LOG)
            }

            leg(ENDED).let { l ->
                for (signal in NotificationEvent.entries) row(l, "${signal.name.lowercase()} tpl", tpl(signal), ENDED, 0, LOG)
                row(l, "removal rung", REMOVAL, ENDED, 0, LOG)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, ENDED, 0, LOG)
                row(l, "answer Pay", answer(Answer.PAY), PAY_HANDOFF)
                row(l, "answer Yes", answer(Answer.YES), ENDED, 0, LOG)
                row(l, "timeout", Timeout(TimeoutKind.REMINDER), ENDED, 0, LOG)
            }

            for (s in listOf(CANCEL_REQUESTED, CANCEL_GUIDED)) {
                val l = leg(s, providerState = ASSIGNED)
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), s, 0, StateOutput.CancelCardUpdate(SEARCHING))
                row(l, "assigned tpl", tpl(NotificationEvent.ASSIGNED), s, 0, StateOutput.CancelCardUpdate(ASSIGNED))
                row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), s, 0, StateOutput.CancelCardUpdate(ARRIVING))
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), s, 0, StateOutput.CancelCardUpdate(DRIVER_CANCELLED))
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), SEARCH_EXPIRED, 0, StateOutput.EndedByApp(SEARCH_EXPIRED))
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), CANCEL_CONFIRMED, 0, StateOutput.CancelConfirmed(EvidenceRung.TEMPLATE))
                row(l, "removal rung", REMOVAL, CANCEL_CONFIRMED, 0, StateOutput.CancelConfirmed(EvidenceRung.REMOVAL_PLUS_CAPTURE))
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), s, 0, LOG)
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), s, 0, LOG)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, s, 0, StateOutput.CheckScreenPrompt)
                row(l, "answer I cancelled it", answer(Answer.I_CANCELLED_IT), USER_SAYS_CANCELLED, 0, StateOutput.UserSaysCancelled)
                row(l, "answer Yes", answer(Answer.YES), s, 0, NA)
                row(l, "timeout (reminder)", Timeout(TimeoutKind.REMINDER), s, 0, StateOutput.ReminderDue)
            }

            for (s in listOf(USER_SAYS_CANCELLED, CANCEL_UNCONFIRMED)) {
                val l = leg(s, providerState = SEARCHING)
                val stillLive = StateOutput.Alert(AlertKind.STILL_LIVE)
                row(l, "searching tpl", tpl(NotificationEvent.SEARCHING), CANCEL_UNCONFIRMED, 0, stillLive)
                row(l, "assigned tpl", tpl(NotificationEvent.ASSIGNED), CANCEL_UNCONFIRMED, 0, stillLive)
                row(l, "arriving tpl", tpl(NotificationEvent.ARRIVING), CANCEL_UNCONFIRMED, 0, stillLive)
                row(l, "driver-cancelled tpl", tpl(NotificationEvent.DRIVER_CANCELLED), CANCEL_UNCONFIRMED, 0, stillLive)
                row(l, "no-drivers tpl", tpl(NotificationEvent.NO_DRIVERS), SEARCH_EXPIRED, 0, StateOutput.EndedByApp(SEARCH_EXPIRED))
                row(l, "cancelled tpl", tpl(NotificationEvent.CANCELLED), CANCEL_CONFIRMED, 0, StateOutput.CancelConfirmed(EvidenceRung.TEMPLATE))
                row(l, "removal rung", REMOVAL, CANCEL_CONFIRMED, 0, StateOutput.CancelConfirmed(EvidenceRung.REMOVAL_PLUS_CAPTURE))
                row(l, "ended tpl", tpl(NotificationEvent.ENDED), s, 0, LOG)
                row(l, "trip-started tpl", tpl(NotificationEvent.TRIP_STARTED), s, 0, LOG)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, s, 0, StateOutput.Alert(AlertKind.CHECK_SCREEN))
                row(l, "answer I cancelled it", answer(Answer.I_CANCELLED_IT), s, 0, NA)
                row(l, "timeout +60 s, still live", Timeout(TimeoutKind.FOLLOW_UP_CHECK, ongoingPresent = true), CANCEL_UNCONFIRMED, 0, stillLive)
                row(l, "timeout +60 s, clear", Timeout(TimeoutKind.FOLLOW_UP_CHECK), s, 0, StateOutput.Logged(LogReason.FOLLOW_UP_CLEAR))
                row(l, "timeout (reminder)", Timeout(TimeoutKind.REMINDER), s, 0, StateOutput.ReminderDue)
            }

            for (s in listOf(SEARCH_EXPIRED, PROVIDER_CANCELLED, CANCEL_CONFIRMED, PAY_HANDOFF)) {
                val l = leg(s)
                for (signal in NotificationEvent.entries) row(l, "${signal.name.lowercase()} tpl", tpl(signal), s, 0, TERMINAL)
                row(l, "removal rung", REMOVAL, s, 0, TERMINAL)
                row(l, "UNKNOWN_UPDATE", UNKNOWN, s, 0, TERMINAL)
                row(l, "answer I cancelled it", answer(Answer.I_CANCELLED_IT), s, 0, TERMINAL)
                row(l, "timeout", Timeout(TimeoutKind.REMINDER), s, 0, TERMINAL)
            }
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cells(): List<Array<Any>> = rows
    }
}
