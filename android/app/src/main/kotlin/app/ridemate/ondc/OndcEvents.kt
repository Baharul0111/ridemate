package app.ridemate.ondc

import app.ridemate.core.state.Protocol
import app.ridemate.core.state.ProtocolKind
import app.ridemate.core.state.TapId

/**
 * Maps an `ondc_txn` snapshot to the core ONDC [Protocol] events of §4.4
 * (`SEARCHING_NET → ON_SELECT → INIT → CONFIRMED → … → SOFT_CANCEL (fee shown) → [user tap] CONFIRM_CANCEL →
 * CANCEL_CONFIRMED`).
 *
 * Firestore may coalesce snapshots, so each snapshot yields the whole path up to its state, each event with a stable
 * `messageId` (`<txn>/<kind>[/values]`): the client delivers an id once and the statechart is idempotent on it, so a
 * skipped intermediate snapshot still drives the leg through every step. Rules:
 * - CONFIRMED carries the user's book tap (I1).
 * - Driver ETA/distance (protocol data) arrive on DRIVER_ASSIGNED / ARRIVING; new values get a new id, so they update.
 * - SOFT_CANCEL carries the network's fee, shown before the user confirms (the guardian then waits for the tap).
 * - CANCEL_CONFIRMED is the seller's `on_cancel`: evidence rung 1 after the user's confirm, else the seller cancelled.
 *   It is never preceded by a synthesised soft cancel (that would show a fee nobody quoted).
 * - FAILED stands alone; an unknown state yields nothing.
 */
object OndcEvents {

    /**
     * [bookTap] is the user's Book tap (I1); [cancelRound] counts the user's soft-cancel requests, so a second request
     * that is quoted the same fee is still shown before the user confirms.
     */
    fun from(doc: OndcTxnDoc, bookTap: TapId? = null, cancelRound: Int = 0): List<Protocol> {
        val state = doc.state ?: return emptyList()
        val id = doc.txnId
        val out = mutableListOf<Protocol>()
        fun add(kind: ProtocolKind, suffix: String = "", fee: Int? = null, eta: Int? = null, meters: Int? = null, tap: TapId? = null) {
            out += Protocol(kind, feeInr = fee, etaMin = eta, distanceMeters = meters, tapId = tap, messageId = "$id/${kind.name}$suffix")
        }
        fun search() {
            add(ProtocolKind.SEARCH_STARTED)
            if (doc.quotes.isNotEmpty()) add(ProtocolKind.QUOTES, "/" + doc.quotes.joinToString(",") { "${it.itemId}:${it.priceInr}:${it.etaMin}" })
        }
        fun booked() {
            search()
            add(ProtocolKind.SELECTED)
            add(ProtocolKind.INITIALIZED)
            add(ProtocolKind.CONFIRMED, tap = bookTap)
        }
        fun driverValues(kind: ProtocolKind) {
            val driver = doc.driver ?: return add(kind)
            add(kind, "/${driver.etaMin}/${driver.distanceMeters}", eta = driver.etaMin, meters = driver.distanceMeters)
        }

        when (state) {
            OndcTxnState.SEARCHING_NET -> search()
            OndcTxnState.ON_SELECT -> {
                search()
                add(ProtocolKind.SELECTED)
            }
            OndcTxnState.INIT -> {
                search()
                add(ProtocolKind.SELECTED)
                add(ProtocolKind.INITIALIZED)
            }
            OndcTxnState.CONFIRMED -> {
                booked()
                if (doc.driver != null) driverValues(ProtocolKind.DRIVER_ASSIGNED)
            }
            OndcTxnState.ARRIVING -> {
                booked()
                driverValues(ProtocolKind.ARRIVING)
            }
            OndcTxnState.ON_TRIP -> {
                booked()
                add(ProtocolKind.TRIP_STARTED)
            }
            OndcTxnState.ENDED -> {
                booked()
                add(ProtocolKind.ENDED)
            }
            OndcTxnState.SOFT_CANCEL -> {
                booked()
                if (doc.driver != null) driverValues(ProtocolKind.DRIVER_ASSIGNED)
                val fee = doc.feeInr
                if (fee != null) add(ProtocolKind.SOFT_CANCEL_QUOTED, "/$cancelRound/$fee", fee = fee)
            }
            OndcTxnState.CANCEL_CONFIRMED -> {
                booked()
                add(ProtocolKind.CANCEL_CONFIRMED)
            }
            OndcTxnState.FAILED -> add(ProtocolKind.FAILED)
        }
        return out
    }
}
