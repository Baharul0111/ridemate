package app.ridemate.core.state

import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import kotlinx.serialization.Serializable

/** What is known about the current driver. Values are shown only with an admissible source (I2 + D8). */
@Serializable
data class DriverInfo(
    val etaMin: Int? = null,
    val etaSource: EtaSource = EtaSource.NONE,
    val etaAtElapsedMs: Long? = null,
    val distanceMeters: Int? = null,
    val distanceSource: EtaSource = EtaSource.NONE,
    val plateHash: String? = null,
    val assignedAtElapsedMs: Long? = null,
    /** Event time of the assignment (the post's time), for "first ASSIGNED by event time". */
    val assignedAtWallMs: Long? = null,
) {
    /** Remaining ETA, counting down from the last reading; null when the ETA may not be shown. */
    fun remainingEtaMin(nowElapsedMs: Long): Int? {
        val eta = etaMin ?: return null
        if (!etaSource.isAdmissible) return null
        val readAt = etaAtElapsedMs ?: return eta
        val elapsedMin = ((nowElapsedMs - readAt).coerceAtLeast(0) / 60_000).toInt()
        return (eta - elapsedMin).coerceAtLeast(0)
    }
}

/** I2 + D8: VERIFIED, a deterministic template, protocol data or a labelled user entry. */
val EtaSource.isAdmissible: Boolean get() = this != EtaSource.NONE

/** Idempotency mark: the last event type a sbn.key delivered, and the leg generation it left behind. */
@Serializable
data class AppliedMark(val type: EventType, val generation: Int)

/** One leg (one ride app or ONDC) of a session: the statechart's whole state. */
@Serializable
data class LegState(
    val legId: String,
    val provider: Provider,
    val state: RideState = RideState.SELECTED,
    val generation: Int = 0,
    /** Incremented on every state or generation change; timers are bound to it. */
    val stateSeq: Long = 0,
    val enteredAtElapsedMs: Long = 0,
    /** MAYBE_CHANGED → "Still coming" target, MAYBE_BOOKED → "No" target, SOFT_CANCEL → "Keep ride" target. */
    val returnState: RideState? = null,
    /** The ride status underneath a user-cancel chain (drives the fee note and the abort target). */
    val providerState: RideState? = null,
    val driver: DriverInfo = DriverInfo(),
    val lastEvidenceWallMs: Long? = null,
    val marks: Map<String, AppliedMark> = emptyMap(),
    val removalAtElapsedMs: Long? = null,
    val cancelledCaptureAtElapsedMs: Long? = null,
    val confirmedBy: EvidenceRung? = null,
    val ondcFeeInr: Int? = null,
    /** Closed by the ride app itself (expired / provider-cancelled) while a cancel chain was running. */
    val endedByApp: Boolean = false,
    /**
     * Wall time the leg was created. A post re-read from the shade that is older than this belongs to an earlier ride
     * (a cancelled or expired notification left over from before the session) and must not move this leg.
     */
    val notBeforeWallMs: Long? = null,
) {
    val isNetwork: Boolean get() = provider.isNetwork

    /** A booking exists (or may exist): the user tapped Book, or the app's own posts say so. */
    val isBooked: Boolean get() = state !in PRE_BOOKING && state !in ONDC_PRE_CONFIRM

    /** The ride status as the provider sees it, looking through MAYBE and cancel-chain states. */
    val underlyingState: RideState
        get() = when (state) {
            RideState.MAYBE_BOOKED -> returnState ?: RideState.OPENED
            RideState.MAYBE_ASSIGNED -> RideState.SEARCHING
            RideState.MAYBE_CHANGED -> returnState ?: RideState.ASSIGNED
            RideState.MAYBE_NEW_DRIVER -> RideState.DRIVER_CANCELLED
            in CHAIN_STATES -> providerState ?: RideState.SEARCHING
            RideState.SOFT_CANCEL, RideState.CONFIRM_CANCEL -> returnState ?: RideState.CONFIRMED
            else -> state
        }

    companion object {
        /** No booking is known yet. MAYBE_BOOKED counts as not booked until the user says "Yes, booked". */
        val PRE_BOOKING = setOf(RideState.SELECTED, RideState.OPENED, RideState.QUOTED, RideState.MAYBE_BOOKED)
        val ONDC_PRE_CONFIRM = setOf(RideState.SEARCHING_NET, RideState.ON_SELECT, RideState.INIT)
        val CHAIN_STATES = setOf(
            RideState.CANCEL_REQUESTED, RideState.CANCEL_GUIDED,
            RideState.USER_SAYS_CANCELLED, RideState.CANCEL_UNCONFIRMED,
        )
        val DRIVER_STATES = setOf(RideState.ASSIGNED, RideState.ARRIVING)
    }
}
