package app.ridemate.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared vocabulary for the phone side of RideMate. Everything here is plain data so the
 * gate, the statechart and the guardian can be unit-tested on the JVM.
 */

/** A ride source. Demo providers are fictional and never mix with live legs. */
enum class Provider(
    val packageName: String,
    val displayName: String,
    val isDemo: Boolean = false,
    val isNetwork: Boolean = false,
) {
    UBER("com.ubercab", "Uber"),
    OLA("com.olacabs.customer", "Ola"),
    RAPIDO("com.rapido.passenger", "Rapido"),
    ONDC("ondc.network", "Open network (ONDC)", isNetwork = true),
    DEMO_KESTREL("demo.kestrel", "Kestrel Go", isDemo = true),
    DEMO_PEPPER("demo.pepper", "Pepper Rides", isDemo = true),
    DEMO_TAMARIND("demo.tamarind", "Tamarind Cabs", isDemo = true);

    /** The short id used on the wire (`app` field) and in Remote Config. */
    val wireId: String get() = name.lowercase()

    companion object {
        /** I7: the only packages RideWatch ever reads. Everything else is dropped unread. */
        val RIDE_APP_PACKAGES: Set<String> = setOf(UBER.packageName, OLA.packageName, RAPIDO.packageName)
        val LIVE_APPS: List<Provider> = listOf(UBER, OLA, RAPIDO)
        val DEMO_APPS: List<Provider> = listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND)

        fun fromPackage(pkg: String): Provider? = entries.firstOrNull { it.packageName == pkg }
        fun fromWireId(id: String): Provider? = entries.firstOrNull { it.wireId == id }
    }
}

@Serializable
enum class VehicleClass {
    @SerialName("bike") BIKE,
    @SerialName("auto") AUTO,
    @SerialName("cab_economy") CAB_ECONOMY,
    @SerialName("cab_premium") CAB_PREMIUM,
    @SerialName("cab_xl") CAB_XL,
    @SerialName("other") OTHER;

    val wireId: String get() = name.lowercase()

    companion object {
        fun fromWireId(id: String?): VehicleClass? = entries.firstOrNull { it.wireId == id }
    }
}

@Serializable
enum class ScreenType {
    @SerialName("fare_options") FARE_OPTIONS,
    @SerialName("searching") SEARCHING,
    @SerialName("driver_assigned") DRIVER_ASSIGNED,
    @SerialName("cancel_flow") CANCEL_FLOW,
    @SerialName("cancelled") CANCELLED,
    @SerialName("home") HOME,
    @SerialName("other") OTHER,
    @SerialName("unreadable") UNREADABLE,
}

/** §4.4 leg states (ride apps, user cancel chain and ONDC). */
enum class RideState {
    SELECTED, OPENED, QUOTED,

    /** An app RideMate opened (not marked "Booked here") sent a post it could not read: "Did you book on <App>?". */
    MAYBE_BOOKED,
    BOOK_REQUESTED, SEARCHING,
    MAYBE_ASSIGNED, ASSIGNED, ARRIVING, MAYBE_CHANGED,
    DRIVER_CANCELLED, MAYBE_NEW_DRIVER,
    ON_TRIP, ENDED, PAY_HANDOFF,
    SEARCH_EXPIRED, PROVIDER_CANCELLED,
    CANCEL_REQUESTED, CANCEL_GUIDED, USER_SAYS_CANCELLED, CANCEL_UNCONFIRMED, CANCEL_CONFIRMED,
    SEARCHING_NET, ON_SELECT, INIT, CONFIRMED, SOFT_CANCEL, CONFIRM_CANCEL;

    /** I8 terminal states: nothing left to cancel or remind about. */
    val isTerminal: Boolean
        get() = this in TERMINAL

    /** I10: the only states in which a leg counts as "your ride". */
    val hasDriver: Boolean
        get() = this == ASSIGNED || this == ARRIVING

    val isMaybe: Boolean
        get() = this == MAYBE_BOOKED || this == MAYBE_ASSIGNED || this == MAYBE_CHANGED || this == MAYBE_NEW_DRIVER

    companion object {
        val TERMINAL: Set<RideState> = setOf(CANCEL_CONFIRMED, SEARCH_EXPIRED, PROVIDER_CANCELLED, ENDED, PAY_HANDOFF)
    }
}

enum class QuoteStatus { VERIFIED, UNREADABLE, USER_ENTERED, NETWORK }

/** Where a displayed ETA came from (I2 + D8). NONE means "see in app". */
enum class EtaSource { VERIFIED, TEMPLATE, USER, NETWORK, NONE }

/** §4.6 evidence ladder. Only rungs 1–3 may confirm a cancel (I3). */
enum class EvidenceRung(val rung: Int, val confirms: Boolean) {
    ONDC_ON_CANCEL(1, true),
    TEMPLATE(2, true),
    REMOVAL_PLUS_CAPTURE(3, true),
    USER(4, false),
}

data class LatLng(val lat: Double, val lng: Double)

data class Place(val label: String, val point: LatLng)
