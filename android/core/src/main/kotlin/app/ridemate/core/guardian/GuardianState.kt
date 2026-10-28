package app.ridemate.core.guardian

import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.LegState
import app.ridemate.core.state.PromptKind
import app.ridemate.core.state.TapId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The guardian's whole state, serialisable so the app can persist it in Room after every step and restore it after
 * the process is killed (reminders and follow-up checks survive). Times are elapsed (monotonic) ms in the guardian's
 * frame; [elapsedOffsetMs] rebases that frame after a reboot.
 */
@Serializable
data class GuardianState(
    var session: SessionState? = null,
    val legs: MutableMap<String, LegRecord> = linkedMapOf(),
    /** Terminal legs replaced by "Compare again (same trip)"; kept for the audit and invariants. */
    val retiredLegs: MutableList<LegRecord> = mutableListOf(),
    val sheets: MutableList<OpenSheet> = mutableListOf(),
    val prompts: MutableList<OpenPrompt> = mutableListOf(),
    var chain: ChainState? = null,
    var reminders: ReminderState? = null,
    val timers: MutableList<TimerRecord> = mutableListOf(),
    val taps: MutableList<TapRecord> = mutableListOf(),
    val actions: MutableList<ActionRecord> = mutableListOf(),
    val evidence: MutableList<EvidenceRecord> = mutableListOf(),
    val switches: MutableList<SwitchRecord> = mutableListOf(),
    val captures: MutableList<CaptureRecord> = mutableListOf(),
    /** "legId:generation" of first-driver sheets the user answered "Not now". */
    val notNowDrivers: MutableSet<String> = mutableSetOf(),
    /** "legId:generation" of candidates that already got a better-ride / second-driver sheet. */
    val betterRideOffered: MutableSet<String> = mutableSetOf(),
    val sameDriverReported: MutableSet<String> = mutableSetOf(),
    var foregroundRequired: Boolean = false,
    /** "legId:generation" of the last "your ride" banner, so one driver is announced once. */
    var lastRideBanner: String? = null,
    var nextId: Long = 1,
    var elapsedOffsetMs: Long = 0,
    var savedAtElapsedMs: Long = 0,
    var savedAtWallMs: Long = 0,
)

@Serializable
data class SessionState(
    val sessionId: String,
    val demo: Boolean,
    /** Opt-in "Keep searching" (off by default, with the fee and driver-harm warning). */
    val keepSearching: Boolean,
    val startedAtElapsedMs: Long,
    var keptLegId: String? = null,
    var betterRideStopped: Boolean = false,
    var closed: Boolean = false,
    var compareRound: Int = 1,
)

enum class LegRole {
    /** Not decided yet. */
    NONE,

    /** The ride the user keeps. */
    KEPT,

    /** A booking to be cancelled (I8 applies). */
    EXTRA,

    /** Still searching under "Keep searching": a possible better ride. */
    CANDIDATE,
}

@Serializable
data class LegRecord(
    var leg: LegState,
    var role: LegRole = LegRole.NONE,
    var dismissed: Boolean = false,
    var extraSinceElapsedMs: Long? = null,
    var ondcBookTap: TapId? = null,
    /** The tap that queued this leg for cancelling (the chain's Yes, a switch, or an ONDC cancel). */
    var cancelTap: TapId? = null,
    var bookedFareInr: Int? = null,
) {
    val legId: String get() = leg.legId
    val provider: Provider get() = leg.provider
}

enum class SheetKind { CANCEL_OTHERS, TWO_DRIVERS, SAME_DRIVER, BETTER_RIDE, SECOND_DRIVER }

@Serializable
data class SheetBinding(val legId: String, val generation: Int)

@Serializable
data class OpenSheet(
    val sheetId: String,
    val kind: SheetKind,
    /** I10 / I4: the (leg, generation) pairs the sheet is about. BETTER_RIDE: [current, candidate]. */
    val bindings: List<SheetBinding>,
    val shownAtElapsedMs: Long,
    val actions: List<UiAction>,
)

@Serializable
data class OpenPrompt(
    val promptId: String,
    val legId: String,
    val generation: Int,
    val kind: PromptKind,
    val shownAtElapsedMs: Long,
    var repeats: Int = 0,
)

@Serializable
data class ChainState(
    /** The user's Yes that started the chain (I1): every OpenApp/soft cancel links to it. */
    val yesTap: TapId,
    val queue: MutableList<String>,
    var currentLegId: String?,
    val startedAtElapsedMs: Long,
)

@Serializable
data class ReminderState(val anchorElapsedMs: Long, var sent: Int = 0)

enum class TimerKind { REPROMPT, DRIVER_CANCELLED_WAIT, FOLLOW_UP_CHECK, BETTER_RIDE_STOP }

/** A timer bound to the leg's state entry ([stateSeq]); it is dropped if the leg moved on. */
@Serializable
data class TimerRecord(val kind: TimerKind, val legId: String, val stateSeq: Long, val dueElapsedMs: Long)

@Serializable
data class TapRecord(val tapId: TapId, val atElapsedMs: Long)

enum class ActionKind {
    BOOKED_HERE, CANCEL_CHAIN_START, CANCEL_GUIDE, SWITCH, ONDC_BOOK, ONDC_SOFT_CANCEL, ONDC_CONFIRM_CANCEL, DISMISS, PAY_HANDOFF,
}

/** I1 / I10 audit: what RideMate did, the tap it links to, and the leg's state and generation at that moment. */
@Serializable
data class ActionRecord(
    val kind: ActionKind,
    val legId: String,
    val tapId: TapId?,
    val atElapsedMs: Long,
    val legState: RideState,
    val generation: Int,
)

@Serializable
data class EvidenceRecord(val legId: String, val rung: EvidenceRung, val atElapsedMs: Long)

/** I4 audit: a switch, the sheet it came from, and the generations bound vs. current at the tap. */
@Serializable
data class SwitchRecord(
    val sheetId: String,
    val sheetKind: SheetKind,
    val fromLegId: String,
    val toLegId: String,
    val bound: List<SheetBinding>,
    val current: List<SheetBinding>,
    val tapId: TapId,
    val atElapsedMs: Long,
)

/** I6 audit: a frame processed for a capture verdict, and the tap it followed. */
@Serializable
data class CaptureRecord(
    val legId: String,
    val tapId: TapId,
    val tapAtElapsedMs: Long?,
    val frameAtElapsedMs: Long,
    val demo: Boolean,
    /** False when the frame was blocked for being outside the tap window. */
    val accepted: Boolean,
)

internal val GuardianJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    allowStructuredMapKeys = true
}
