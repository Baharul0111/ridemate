package app.ridemate.core.fare

import app.ridemate.core.format.RupeeAmount
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.Box

/** A ₹ amount on screen: one token or several merged ones (`₹` + `110`, `₹110` + `–` + `130`). */
data class PriceToken(val amount: RupeeAmount, val tokenIds: List<Int>, val box: Box, val text: String)

/** Why a ₹ token in a row was not taken as the payable price. */
enum class IgnoredReason {
    /** Shorter than `strike_height_ratio` × the row's tallest ₹ token. */
    SHORTER_THAN_PAYABLE,

    /** Crossed by a strike marker the OCR reported (a dash token drawn over it, or combining strokes). */
    STRIKE_MARKER,

    /** Not struck, but a taller or (equally tall) further-right ₹ token is the payable. */
    NOT_SELECTED,
}

data class IgnoredPrice(val price: PriceToken, val reason: IgnoredReason)

/** The vehicle label of a row: the OCR text the matching `vehicle_labels` rule touched. */
data class RowLabel(val text: String, val vehicleClass: VehicleClass, val tokenIds: List<Int>, val box: Box)

/** A number selected from tokens (minutes for ETAs, metres for distances) and where it came from. */
data class TokenValue(val value: Int, val tokenIds: List<Int>, val box: Box)

/** One fare option row as Channel B sees it; the gate compares it with Channel A's option at the same place. */
data class FareRow(
    val index: Int,
    val box: Box,
    val tokenIds: List<Int>,
    val label: RowLabel?,
    /** The payable price: tallest ₹ token in the row, then rightmost; struck prices are never chosen. */
    val price: PriceToken?,
    val ignoredPrices: List<IgnoredPrice>,
    /** Pickup ETA; null when there is none or the row shows several different ones. */
    val eta: TokenValue?,
) {
    val vehicleClass: VehicleClass? get() = label?.vehicleClass
}

/** Everything Channel B read from one frame. */
data class FareRead(
    val frameWidth: Int,
    val frameHeight: Int,
    val rows: List<FareRow>,
    val screenType: ScreenType,
    /** Driver ETA in minutes, selected next to an anchor word (`away`, `arriving`, `दूर`, …). */
    val driverEta: TokenValue?,
    /** Driver distance in metres, selected next to an anchor word. */
    val driverDistance: TokenValue?,
    /** K3: at least one ₹/Rs token or a known status keyword for the app. */
    val looksLikeFareScreen: Boolean,
)
