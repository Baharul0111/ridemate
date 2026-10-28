package app.ridemate.core.config

import app.ridemate.core.model.VehicleClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Remote Config `sanity_bands` (contracts §2.3): plausibility bands a VERIFIED value must fall inside (§4.3 e). */
@Serializable
data class SanityBandsConfig(
    val schema: String,
    @SerialName("price_min_inr") val priceMinInr: Int,
    @SerialName("price_max_inr") val priceMaxInr: Int,
    @SerialName("eta_max_min") val etaMaxMin: Int,
    @SerialName("road_factor") val roadFactor: Double,
    @SerialName("base_allowance_inr") val baseAllowanceInr: Int,
    /** ₹/km `[min, max]` keyed by vehicle class wire id; `other` is the fallback and must be present. */
    @SerialName("per_km_inr") val perKmInr: Map<String, List<Double>>,
) {
    /** The ₹/km band for [vehicleClass], falling back to `other`. */
    fun perKm(vehicleClass: VehicleClass): ClosedFloatingPointRange<Double> {
        val band = perKmInr[vehicleClass.wireId] ?: perKmInr.getValue(OTHER)
        return band[0]..band[1]
    }

    internal fun validate() {
        require(priceMinInr in 0..priceMaxInr) { "price_min_inr must be in 0..price_max_inr" }
        require(etaMaxMin > 0) { "eta_max_min must be positive" }
        require(roadFactor.isFinite() && roadFactor >= 1.0) { "road_factor must be ≥ 1" }
        require(baseAllowanceInr >= 0) { "base_allowance_inr must be ≥ 0" }
        require(OTHER in perKmInr) { "per_km_inr must include \"other\"" }
        perKmInr.forEach { (key, band) ->
            require(band.size == 2 && band.all { it.isFinite() && it >= 0 } && band[0] <= band[1]) {
                "per_km_inr.$key must be [min, max] with 0 ≤ min ≤ max"
            }
        }
    }

    companion object {
        private const val OTHER = "other"

        /** The contracts §2.3 defaults. */
        val DEFAULT: SanityBandsConfig = SanityBandsConfig(
            schema = "1.0", priceMinInr = 15, priceMaxInr = 5000, etaMaxMin = 60, roadFactor = 1.35,
            baseAllowanceInr = 60,
            perKmInr = mapOf(
                "bike" to listOf(3.0, 30.0), "auto" to listOf(8.0, 45.0), "cab_economy" to listOf(8.0, 60.0),
                "cab_premium" to listOf(10.0, 90.0), "cab_xl" to listOf(12.0, 110.0), "other" to listOf(3.0, 150.0),
            ),
        )
    }
}

/** Parses and validates `sanity_bands`; throws on a wrong schema major or an implausible band. */
fun parseSanityBands(raw: String): SanityBandsConfig =
    decodeFareSidePayload(raw, SanityBandsConfig.serializer()).also { it.validate() }
