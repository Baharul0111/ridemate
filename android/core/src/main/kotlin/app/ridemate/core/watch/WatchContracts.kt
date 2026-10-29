package app.ridemate.core.watch

import app.ridemate.core.model.RideState
import app.ridemate.core.state.EventType
import app.ridemate.core.state.RideEvent
import app.ridemate.core.state.UnknownReason

/** What RideWatch needs to know about the active session (implemented by the guardian). */
interface LegDirectory {
    /** The active session id, or null outside a session (rule misses are then never classified). */
    val activeSessionId: String?

    /** The active session's leg for a ride-app package, or null. */
    fun legFor(packageName: String): LegView?
}

data class LegView(val legId: String, val state: RideState, val generation: Int, val isBooked: Boolean)

/** Where RideWatch delivers events (implemented by the guardian). */
fun interface RideEventSink {
    fun onRideEvent(legId: String, event: RideEvent): Disposition
}

/** What a delivered event did, recorded in the audit log. */
enum class Disposition { TRANSITION, PROMPT, DUP_DROP, LOGGED }

/** Whether a ride app still shows an ongoing ride/search notification (§4.6 rung 4 follow-up, DRIVER_CANCELLED wait). */
fun interface OngoingNotifications {
    fun hasOngoingRide(packageName: String): Boolean

    companion object {
        val NONE = OngoingNotifications { false }
    }
}

enum class PostKind { TEMPLATE_HIT, STRUCTURAL_HIT, REDACTED, RULE_MISS }

enum class PostOutcome {
    /** A RideEvent reached the leg (template, redacted, cached or classified label, or the UNKNOWN_UPDATE fallback). */
    EVENT,

    /** Exact repeat of the last processed post for its key (logged, I9). */
    DUPLICATE_DROPPED,
    PENDING_CLASSIFICATION,

    /** A newer rule miss on the same key replaced this one before it was classified; resolved with that post. */
    COALESCED,

    /** A newer post on the same key was resolved first; this post's classification was discarded as stale. */
    SUPERSEDED,

    /** No session leg for this package: logged only. */
    NO_ACTIVE_LEG,
}

/**
 * One allowlisted post. Holds ids, enums and times only (never text), so it is safe to log and to keep for the I9
 * checker.
 */
class PostAuditEntry internal constructor(
    val seq: Long,
    val packageName: String,
    val sbnKey: String,
    val arrivedAtElapsedMs: Long,
    val kind: PostKind,
    val templateId: String?,
    val templateFallback: Boolean,
    val replayed: Boolean,
    val legId: String?,
    val legStateAtArrival: RideState?,
    val legBookedAtArrival: Boolean,
) {
    var outcome: PostOutcome = PostOutcome.PENDING_CLASSIFICATION
        private set
    var resolvedAtElapsedMs: Long? = null
        private set
    var eventType: EventType? = null
        private set
    var unknownReason: UnknownReason? = null
        private set
    var disposition: Disposition? = null
        private set

    /** For COALESCED / SUPERSEDED: the post whose resolution covers this one. */
    var coveredBySeq: Long? = null
        private set
    var classifierCalled: Boolean = false
        internal set

    internal fun resolve(
        outcome: PostOutcome,
        atElapsedMs: Long,
        eventType: EventType? = null,
        unknownReason: UnknownReason? = null,
        disposition: Disposition? = null,
        coveredBySeq: Long? = null,
    ) {
        this.outcome = outcome
        this.resolvedAtElapsedMs = atElapsedMs
        this.eventType = eventType
        this.unknownReason = unknownReason
        this.disposition = disposition
        this.coveredBySeq = coveredBySeq
    }

    internal fun markCoalesced(intoSeq: Long) {
        outcome = PostOutcome.COALESCED
        coveredBySeq = intoSeq
    }

    /**
     * I9 applies to posts on booked, non-terminal legs, and on legs whose app RideMate opened this session (the user
     * may have booked there without tapping "Booked here"; an unreadable post asks "Did you book on <App>?").
     */
    val coveredByI9: Boolean
        get() = legId != null && legStateAtArrival?.isTerminal == false &&
            (legBookedAtArrival || legStateAtArrival in OPENED_APP_STATES)

    private companion object {
        val OPENED_APP_STATES = setOf(RideState.OPENED, RideState.QUOTED, RideState.MAYBE_BOOKED)
    }

    override fun toString(): String =
        "Post#$seq($packageName key=$sbnKey kind=$kind tpl=$templateId outcome=$outcome event=$eventType disp=$disposition " +
            "arrived=$arrivedAtElapsedMs resolved=$resolvedAtElapsedMs covered=$coveredBySeq)"
}

data class RemovalAuditEntry(
    val packageName: String,
    val sbnKey: String,
    val reasonCode: Int,
    val wasOngoing: Boolean,
    val atElapsedMs: Long,
    val legId: String?,
)
