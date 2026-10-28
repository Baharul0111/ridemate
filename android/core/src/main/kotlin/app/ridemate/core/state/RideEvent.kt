package app.ridemate.core.state

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.EtaSource
import kotlinx.serialization.Serializable

/** The id of one user tap. I1: every book, cancel, switch and ONDC confirm/cancel links to one. */
@Serializable
@JvmInline
value class TapId(val value: String)

/** Where a provider status came from. Only [CLASSIFIER] is non-deterministic. */
enum class SignalSource {
    /** A text template matched the ride app's own post. */
    TEMPLATE,

    /** A `redacted` structural template matched a post without text. */
    STRUCTURAL_TEMPLATE,

    /** A user-tapped capture read through the VerificationGate (VERIFIED values). */
    SCREEN_CHECK,

    /** The rule-miss classifier (model output): a proposal, never evidence. */
    CLASSIFIER,
}

/** Event types used in idempotency marks (leg, event type, sbn.key, generation). */
enum class EventType {
    SEARCHING, ASSIGNED, ARRIVING, DRIVER_CANCELLED, NO_DRIVERS, CANCELLED, ENDED, TRIP_STARTED,
    UNKNOWN_UPDATE, REMOVAL, CAPTURE_VERDICT, DRIVER_DATA, USER_ANSWER, TIMEOUT, PROTOCOL, CHAIN, LIFECYCLE,
}

sealed interface RideEvent {
    val type: EventType
}

/** A ride-app status: a template hit, a verified screen read, or a classifier proposal. */
data class ProviderUpdate(
    val signal: NotificationEvent,
    val source: SignalSource,
    val sbnKey: String? = null,
    val templateId: String? = null,
    val etaMin: Int? = null,
    val distanceMeters: Int? = null,
    val plateHash: String? = null,
    /** The post's own time (wall clock), used for "first by event time" and stale-replay checks. */
    val postTimeWallMs: Long? = null,
    /** Re-read on listener reconnect (§4.5.8). */
    val replayed: Boolean = false,
) : RideEvent {
    init {
        if (source == SignalSource.CLASSIFIER) {
            require(signal in CLASSIFIER_SIGNALS) { "a classifier may only propose SEARCHING or ASSIGNED" }
            require(etaMin == null && distanceMeters == null && plateHash == null) { "classifier values never reach the UI" }
        }
        if (source == SignalSource.SCREEN_CHECK) {
            require(signal in SCREEN_SIGNALS) { "a screen read proves a cancel only through CaptureVerdict" }
        }
    }

    override val type: EventType get() = EventType.valueOf(signal.name)
    val isDeterministic: Boolean get() = source != SignalSource.CLASSIFIER

    /** I2 + D8: the label a value read from this update carries. */
    val valueSource: EtaSource
        get() = when (source) {
            SignalSource.TEMPLATE, SignalSource.STRUCTURAL_TEMPLATE -> EtaSource.TEMPLATE
            SignalSource.SCREEN_CHECK -> EtaSource.VERIFIED
            SignalSource.CLASSIFIER -> EtaSource.NONE
        }

    companion object {
        val CLASSIFIER_SIGNALS = setOf(NotificationEvent.SEARCHING, NotificationEvent.ASSIGNED)
        val SCREEN_SIGNALS = setOf(NotificationEvent.SEARCHING, NotificationEvent.ASSIGNED, NotificationEvent.ARRIVING)
    }
}

enum class UnknownReason {
    /** Android 15 redaction or an empty post. */
    REDACTED,

    /** No classifier wired, signed out, anonymous, quota, cap, breaker or offline. */
    CLASSIFIER_UNAVAILABLE,
    CLASSIFIER_FAILED,
    CLASSIFIER_TIMEOUT,

    /** The classifier answered with a label that may only raise a prompt (or `other`/`unknown`). */
    CLASSIFIER_LABEL,

    /** A rule miss on a leg that is not booked yet: never sent to the classifier. */
    NOT_CLASSIFIED,
}

/** A post RideWatch could not read deterministically. */
data class UnknownUpdate(
    val reason: UnknownReason,
    val sbnKey: String? = null,
    val postTimeWallMs: Long? = null,
    val replayed: Boolean = false,
) : RideEvent {
    override val type: EventType get() = EventType.UNKNOWN_UPDATE
}

/** A ride-app post left the shade. With REASON_APP_CANCEL on an ongoing post it is half of evidence rung 3. */
data class NotificationRemoved(
    val sbnKey: String,
    val reasonCode: Int,
    val appCancel: Boolean,
    val wasOngoing: Boolean,
) : RideEvent {
    override val type: EventType get() = EventType.REMOVAL
}

/** OCR of a user-tapped capture checked against the app's cancelled-state keywords: the other half of rung 3. */
data class CaptureVerdict(val cancelledKeywordsFound: Boolean, val tapId: TapId) : RideEvent {
    override val type: EventType get() = EventType.CAPTURE_VERDICT
}

/** New ETA/distance for the current driver without a state change (template countdown, verified capture). */
data class DriverData(val etaMin: Int?, val distanceMeters: Int?, val source: EtaSource) : RideEvent {
    override val type: EventType get() = EventType.DRIVER_DATA
}

/** Answers on prompts, the bubble and the cancel card. */
enum class Answer {
    BOOKED_HERE,

    /** MAYBE_ASSIGNED "Yes, cancel the others", MAYBE_CHANGED "Yes" (still coming), MAYBE_NEW_DRIVER "Yes". */
    YES,
    NOT_YET,
    DRIVER_CANCELLED,
    NO_IT_ENDED,
    CHECK_SCREEN,
    I_CANCELLED_IT,
    PAY,
    ONDC_CONFIRM_CANCEL,
    ONDC_KEEP_RIDE,
}

data class UserAnswer(val answer: Answer, val tapId: TapId) : RideEvent {
    override val type: EventType get() = EventType.USER_ANSWER
}

enum class TimeoutKind { REPROMPT, DRIVER_CANCELLED_WAIT, FOLLOW_UP_CHECK, REMINDER }

/** A guardian timer firing. [ongoingPresent]: the app still shows an ongoing ride/search notification. */
data class Timeout(val kind: TimeoutKind, val ongoingPresent: Boolean = false) : RideEvent {
    override val type: EventType get() = EventType.TIMEOUT
}

/** ONDC protocol data (TRV10 callbacks mirrored into Firestore by the backend). */
enum class ProtocolKind {
    SEARCH_STARTED, QUOTES, SELECTED, INITIALIZED, CONFIRMED, DRIVER_ASSIGNED, ARRIVING, TRIP_STARTED, ENDED,
    SOFT_CANCEL_QUOTED, CANCEL_CONFIRMED, FAILED,
}

data class Protocol(
    val kind: ProtocolKind,
    val feeInr: Int? = null,
    val etaMin: Int? = null,
    val distanceMeters: Int? = null,
    /** The tap that caused the call (I1); required for CONFIRMED. */
    val tapId: TapId? = null,
    /** ONDC message id; used as the idempotency key. */
    val messageId: String? = null,
) : RideEvent {
    override val type: EventType get() = EventType.PROTOCOL
}

/** Guardian-driven steps of the user-cancel chain. */
enum class ChainStep { QUEUE, GUIDE, ABORT }

data class Chain(val step: ChainStep) : RideEvent {
    override val type: EventType get() = EventType.CHAIN
}

/** Pre-booking progress: the app was opened with the route, fares were read. */
enum class LifecycleStep { OPENED, QUOTED }

data class Lifecycle(val step: LifecycleStep) : RideEvent {
    override val type: EventType get() = EventType.LIFECYCLE
}
