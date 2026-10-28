package app.ridemate.ondc

import app.ridemate.core.format.DistanceText
import app.ridemate.core.model.VehicleClass
import kotlin.math.ceil

/** `ondc_txn/{txnId}.state` (contracts §1.4), written by the backend from TRV10 callbacks. */
enum class OndcTxnState {
    SEARCHING_NET, ON_SELECT, INIT, CONFIRMED, ARRIVING, ON_TRIP, ENDED, SOFT_CANCEL, CANCEL_CONFIRMED, FAILED;

    /** A booking exists and can be soft-cancelled (fee quoted first). */
    val cancellable: Boolean get() = this == CONFIRMED || this == ARRIVING

    val terminal: Boolean get() = this == ENDED || this == CANCEL_CONFIRMED || this == FAILED

    /** The select → init → confirm progression, for waiting on a step's callback. */
    val progress: Int
        get() = when (this) {
            SEARCHING_NET -> 0
            ON_SELECT -> 1
            INIT -> 2
            else -> 3
        }

    companion object {
        fun fromWire(value: String?): OndcTxnState? = entries.firstOrNull { it.name == value }
    }
}

/** One quote from the seller: protocol data (I2 allows it), shown as a "network quote". */
data class OndcQuoteDoc(
    val itemId: String,
    /** The seller's own provider name (protocol data, never model output). */
    val providerName: String?,
    val vehicleClass: VehicleClass,
    val priceInr: Int,
    val etaMin: Int?,
)

data class OndcDriverDoc(val etaMin: Int?, val distanceMeters: Int?)

/**
 * The phone's view of `ondc_txn/{txnId}` (contracts §1.4). Parsed leniently from the Firestore map: an unknown state is
 * null (nothing is inferred from it), malformed quotes are dropped, numbers may arrive as Long, Double or String.
 */
data class OndcTxnDoc(
    val txnId: String,
    val state: OndcTxnState?,
    /** The hackathon's mock seller: rows are labelled "ONDC demo seller". */
    val demo: Boolean,
    val bppId: String?,
    val quotes: List<OndcQuoteDoc>,
    val selectedItemId: String?,
    /** The soft-cancel fee in whole rupees (rounded up, so it is never understated); null until quoted. */
    val feeInr: Int?,
    val driver: OndcDriverDoc?,
) {
    /** Demo seller: the doc says so, or the seller is the in-process mock (`mock-bpp.*`). */
    val demoSeller: Boolean get() = demo || bppId?.startsWith(MOCK_BPP_PREFIX) == true

    companion object {
        const val MOCK_BPP_PREFIX = "mock-bpp"
        private const val MAX_PRICE_INR = 100_000
        private const val MAX_ETA_MIN = 180

        fun fromMap(txnId: String, map: Map<String, Any?>): OndcTxnDoc = OndcTxnDoc(
            txnId = txnId,
            state = OndcTxnState.fromWire(map["state"] as? String),
            demo = map["demo"] as? Boolean ?: false,
            bppId = map["bpp_id"] as? String,
            quotes = (map["quotes"] as? List<*>).orEmpty().mapNotNull { quoteOf(it) },
            selectedItemId = map["selected_item_id"] as? String,
            feeInr = feeOf(map["fee"]),
            driver = driverOf(map["driver"]),
        )

        private fun quoteOf(raw: Any?): OndcQuoteDoc? {
            val q = raw as? Map<*, *> ?: return null
            val itemId = (q["item_id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val price = wholeNumber(q["price"])?.takeIf { it in 0..MAX_PRICE_INR } ?: return null
            return OndcQuoteDoc(
                itemId = itemId,
                providerName = (q["provider_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                vehicleClass = VehicleClass.fromWireId(q["vehicle_class"] as? String) ?: VehicleClass.OTHER,
                priceInr = price,
                etaMin = wholeNumber(q["eta_min"])?.takeIf { it in 0..MAX_ETA_MIN },
            )
        }

        private fun feeOf(raw: Any?): Int? {
            val fee = raw as? Map<*, *> ?: return null
            val currency = fee["currency"] as? String
            if (currency != null && currency != "INR") return null
            val amount = number(fee["amount"]) ?: return null
            if (amount < 0 || amount > MAX_PRICE_INR) return null
            return ceil(amount).toInt()
        }

        private fun driverOf(raw: Any?): OndcDriverDoc? {
            val d = raw as? Map<*, *> ?: return null
            val eta = wholeNumber(d["eta_min"])?.takeIf { it in 0..MAX_ETA_MIN }
            val meters = DistanceText.parseMeters(d["distance_text"] as? String)
            return if (eta == null && meters == null) null else OndcDriverDoc(eta, meters)
        }

        private fun number(raw: Any?): Double? = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }

        /** An integer value; a fractional or non-numeric value is rejected (prices and minutes are whole numbers). */
        private fun wholeNumber(raw: Any?): Int? {
            val value = number(raw) ?: return null
            if (value % 1.0 != 0.0) return null
            return value.toLong().takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        }
    }
}
