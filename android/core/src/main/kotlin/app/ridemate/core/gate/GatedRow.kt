package app.ridemate.core.gate

import app.ridemate.core.format.DistanceText
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass

/** Why a row is not VERIFIED (logged as enums only; never shown as text). */
enum class GateFailure {
    MODEL_UNAVAILABLE,
    /** (a) Channel A says a different app than the one launched. */
    APP_MISMATCH,
    NO_MODEL_OPTION,
    NO_PARSER_ROW,
    PRICE_MISSING,
    /** (b) Channel A's price differs from the parser's payable token. */
    PRICE_MISMATCH,
    /** (b) the payable token's centre is outside Channel A's box ± 4%. */
    POSITION_MISMATCH,
    /**
     * (b) Channel A says the amount the parser took as payable is the struck-out price (`price_struck_text`): the two
     * channels disagree about which number is payable. Another look at the crop cannot settle it (a re-read carries no
     * struck text), so the row ends UNREADABLE.
     */
    STRUCK_CONFLICT,
    /** (c) labels differ or map to different vehicle classes. */
    LABEL_MISMATCH,
    /** (d) the two frames show different digits. */
    FRAMES_DIFFER,
    /** (e) outside the sanity bands. */
    OUT_OF_BANDS,
}

/**
 * One row of the comparison list. I2 is enforced on construction: an UNREADABLE row never carries a price or ETA,
 * and a displayed ETA always has a source. [displayLabel] is OCR text or the class name, never model text (I5).
 */
data class GatedRow(
    val provider: Provider,
    val vehicleClass: VehicleClass,
    val displayLabel: String,
    /** False when [displayLabel] is the English class name; the UI then shows its localised class name. */
    val labelFromOcr: Boolean,
    val price: IntRange?,
    val status: QuoteStatus,
    val eta: Int?,
    val etaSource: EtaSource,
    val seenAtMs: Long,
    val rowId: String? = null,
    val failures: Set<GateFailure> = emptySet(),
) {
    init {
        require((status == QuoteStatus.UNREADABLE) == (price == null)) { "I2: a price shows exactly when the row is a quote" }
        require(price == null || price.first in 0..price.last) { "invalid price range" }
        require((eta == null) == (etaSource == EtaSource.NONE)) { "I2: an ETA needs a source" }
        require(status != QuoteStatus.UNREADABLE || eta == null) { "I2: an unreadable row shows no ETA" }
        require(status != QuoteStatus.VERIFIED || failures.isEmpty()) { "a VERIFIED row has no failures" }
    }

    companion object {
        /** A price the user typed (manual mode); labelled "entered by you". */
        fun userEntered(provider: Provider, vehicleClass: VehicleClass, price: IntRange, seenAtMs: Long, eta: Int? = null) =
            GatedRow(
                provider, vehicleClass, VehicleClassNames.english(vehicleClass), labelFromOcr = false, price = price,
                status = QuoteStatus.USER_ENTERED, eta = eta, etaSource = if (eta == null) EtaSource.NONE else EtaSource.USER,
                seenAtMs = seenAtMs,
            )

        /** An ONDC quote (protocol data, labelled "network quote"); [providerName] comes from the protocol. */
        fun network(vehicleClass: VehicleClass, providerName: String, price: Int, eta: Int?, seenAtMs: Long) = GatedRow(
            Provider.ONDC, vehicleClass, providerName, labelFromOcr = false, price = price..price,
            status = QuoteStatus.NETWORK, eta = eta, etaSource = if (eta == null) EtaSource.NONE else EtaSource.NETWORK,
            seenAtMs = seenAtMs,
        )
    }
}

/** Driver ETA and distance on an assigned screen; null values mean "see in <app>". */
data class DriverReading(val etaMin: Int?, val etaSource: EtaSource, val distanceMeters: Int?) {
    init {
        require((etaMin == null) == (etaSource == EtaSource.NONE)) { "I2: an ETA needs a source" }
    }

    /** Distance as `1.2 km` / `800 m`, built from the verified number (I5). */
    val distanceText: String? get() = distanceMeters?.let { DistanceText.format(it) }

    companion object {
        val NONE = DriverReading(null, EtaSource.NONE, null)
    }
}

/** English class names, used when no OCR label exists; the UI localises by [VehicleClass] instead. */
object VehicleClassNames {
    fun english(vehicleClass: VehicleClass): String = when (vehicleClass) {
        VehicleClass.BIKE -> "Bike"
        VehicleClass.AUTO -> "Auto"
        VehicleClass.CAB_ECONOMY -> "Cab"
        VehicleClass.CAB_PREMIUM -> "Premium cab"
        VehicleClass.CAB_XL -> "XL cab"
        VehicleClass.OTHER -> "Other"
    }
}
