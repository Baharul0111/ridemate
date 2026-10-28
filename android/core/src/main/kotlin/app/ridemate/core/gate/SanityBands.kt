package app.ridemate.core.gate

import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.format.Geo
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.VehicleClass

/** §4.3 (e) and contracts §2.3: a VERIFIED value must also be plausible. */
class SanityBands(val config: SanityBandsConfig = SanityBandsConfig.DEFAULT) {
    /**
     * Both ends of [price] must be within ₹price_min–price_max and, when [tripKm] (straight-line haversine km) is
     * known, within `per_km_min·d − base_allowance … per_km_max·d + base_allowance` where d = tripKm × road_factor.
     */
    fun priceOk(price: IntRange, vehicleClass: VehicleClass, tripKm: Double?): Boolean {
        if (price.first < config.priceMinInr || price.last > config.priceMaxInr || price.first > price.last) return false
        if (tripKm == null || !tripKm.isFinite() || tripKm <= 0) return true
        val d = tripKm * config.roadFactor
        val band = config.perKm(vehicleClass)
        return price.first >= band.start * d - config.baseAllowanceInr &&
            price.last <= band.endInclusive * d + config.baseAllowanceInr
    }

    fun etaOk(minutes: Int): Boolean = minutes in 0..config.etaMaxMin

    companion object {
        /** Straight-line trip distance for [priceOk]. */
        fun tripKm(pickup: LatLng, drop: LatLng): Double = Geo.haversineKm(pickup, drop)
    }
}
