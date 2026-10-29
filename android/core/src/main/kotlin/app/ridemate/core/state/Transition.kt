package app.ridemate.core.state

import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.RideState

enum class PromptKind { MAYBE_BOOKED, MAYBE_ASSIGNED, MAYBE_CHANGED, MAYBE_NEW_DRIVER }

fun RideState.promptKind(): PromptKind? = when (this) {
    RideState.MAYBE_BOOKED -> PromptKind.MAYBE_BOOKED
    RideState.MAYBE_ASSIGNED -> PromptKind.MAYBE_ASSIGNED
    RideState.MAYBE_CHANGED -> PromptKind.MAYBE_CHANGED
    RideState.MAYBE_NEW_DRIVER -> PromptKind.MAYBE_NEW_DRIVER
    else -> null
}

/** Why an open prompt was withdrawn; the user is told this reason ("(withdraw)" in the §4.4 table). */
enum class WithdrawReason {
    SEARCHING_AGAIN, DRIVER_ASSIGNED, DRIVER_ARRIVING, DRIVER_CANCELLED, SEARCH_EXPIRED, PROVIDER_CANCELLED,
    RIDE_ENDED, TRIP_STARTED, SAME_DRIVER_STILL_COMING, CANCELLING,

    /** "Did you book on <App>?" closed because the app's own post shows a live booking (searching or a driver). */
    BOOKING_SEEN,

    /** The user answered it; nothing to explain. */
    ANSWERED,
}

/** Why an event caused no transition ("—" cells and guards). Always logged. */
enum class LogReason {
    NO_TRANSITION,
    IGNORED_ON_TRIP,
    TERMINAL_STATE,
    ANSWER_NOT_APPLICABLE,
    IDEMPOTENT,
    STALE_REPLAY,
    WRONG_LEG_KIND,
    MISSING_TAP,
    REMOVAL_RECORDED,
    CAPTURE_RECORDED,
    FOLLOW_UP_CLEAR,
    NO_ONGOING_POST,
    NOT_A_DRIVER_STATE,
    QUOTES_UPDATED,
}

enum class CapturePurpose { CHECK_SCREEN, CANCEL_PROOF }

enum class AlertKind {
    /** The user said "I cancelled it" (or proof is pending) but the app says the ride is live. */
    STILL_LIVE,

    /** An unreadable update on a leg the user said was cancelled. */
    CHECK_SCREEN,
}

sealed interface StateOutput {
    data class RaisePrompt(val kind: PromptKind) : StateOutput
    data class RePrompt(val kind: PromptKind) : StateOutput
    data class WithdrawPrompt(val kind: PromptKind, val reason: WithdrawReason) : StateOutput
    data object DupDrop : StateOutput
    data class Logged(val reason: LogReason) : StateOutput
    data class GenerationBumped(val from: Int, val to: Int) : StateOutput

    /** A provider event during CANCEL_REQUESTED/GUIDED: refresh the instruction card and the fee note. */
    data class CancelCardUpdate(val providerState: RideState) : StateOutput

    /** A terminal provider event closed a leg that was being cancelled: "Ended by <App>. No cancel needed". */
    data class EndedByApp(val state: RideState) : StateOutput
    data class CancelConfirmed(val rung: EvidenceRung) : StateOutput

    /** Rung 4: schedule the +60 s follow-up check. */
    data object UserSaysCancelled : StateOutput
    data class Alert(val kind: AlertKind) : StateOutput

    /** An unreadable update during the cancel chain: keep the chain, ask the user to check the screen. */
    data object CheckScreenPrompt : StateOutput
    data class RequestCapture(val purpose: CapturePurpose) : StateOutput

    /** A reminder timer fired for a leg still being cancelled (§4.7 schedule). */
    data object ReminderDue : StateOutput
    data object DriverUpdated : StateOutput
    data class OndcFeeShown(val feeInr: Int) : StateOutput
}

data class Transition(val before: LegState, val after: LegState, val outputs: List<StateOutput>) {
    val stateChanged: Boolean get() = before.state != after.state
    val generationChanged: Boolean get() = before.generation != after.generation

    inline fun <reified T : StateOutput> output(): T? = outputs.filterIsInstance<T>().firstOrNull()
    val isDupDrop: Boolean get() = outputs.any { it == StateOutput.DupDrop }
    val logReason: LogReason? get() = output<StateOutput.Logged>()?.reason
}
