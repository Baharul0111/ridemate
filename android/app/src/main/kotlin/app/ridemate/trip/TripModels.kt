package app.ridemate.trip

import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass

/**
 * A pickup or drop as the user confirmed it. [point] is null when the address was typed and could not be looked up
 * (Geocoder unavailable or no match): such a trip still works, with a plain launch instead of a prefilled route.
 */
data class TripPlace(val label: String, val point: LatLng?) {
    init {
        require(label.isNotBlank()) { "a place needs a label" }
    }

    val hasPoint: Boolean get() = point != null

    /** The core [Place] when the coordinates are known. */
    fun toPlaceOrNull(): Place? = point?.let { Place(label, it) }

    companion object {
        fun of(place: Place): TripPlace = TripPlace(place.label, place.point)
    }
}

/** The three vehicle choices the trip screen offers (goal 2); "cab" covers every car class. */
enum class VehicleChoice(val classes: List<VehicleClass>) {
    BIKE(listOf(VehicleClass.BIKE)),
    AUTO(listOf(VehicleClass.AUTO)),
    CAB(listOf(VehicleClass.CAB_ECONOMY, VehicleClass.CAB_PREMIUM, VehicleClass.CAB_XL)),
}

/**
 * What the user chose on the trip screen, handed to the comparison once the pre-session check passed. [apps] are
 * installed ride apps only, in the order they will be opened.
 */
data class TripPlan(
    val pickup: TripPlace,
    val drop: TripPlace,
    val apps: List<Provider>,
    val vehicles: Set<VehicleChoice>,
    val includeOndc: Boolean,
) {
    init {
        require(apps.all { it in Provider.LIVE_APPS }) { "only ride apps can be opened" }
        require(apps.isNotEmpty() || includeOndc) { "pick at least one app or the open network" }
        require(vehicles.isNotEmpty()) { "pick at least one vehicle type" }
        require(!includeOndc || (pickup.hasPoint && drop.hasPoint)) { "ONDC needs both points" }
    }

    /** The §5 `Session.vehicle_classes` value. */
    val vehicleClasses: List<VehicleClass>
        get() = VehicleChoice.entries.filter { it in vehicles }.flatMap { it.classes }

    /** Every provider of the session, ONDC last. */
    val providers: List<Provider>
        get() = if (includeOndc) apps + Provider.ONDC else apps
}

/** One Geocoder match offered to the user. [id] is `c1`…`c5` (the place-pick enum, contracts §1.3). */
data class PlaceSuggestion(val id: String, val label: String, val point: LatLng) {
    fun toTripPlace(): TripPlace = TripPlace(label, point)
}
