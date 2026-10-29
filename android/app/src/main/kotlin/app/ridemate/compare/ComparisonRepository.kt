package app.ridemate.compare

import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.data.LegEntity
import app.ridemate.data.RideMateDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One comparison as the list shows it: its legs (apps) and every stored row of a vehicle type the user chose on the
 * trip screen ([vehicleClasses], goal 2) as a gate row. ONDC quotes join through [withNetworkRows].
 */
data class ComparisonData(
    val sessionId: String,
    val legs: List<LegEntity>,
    val rows: List<GatedRow>,
    /** The vehicle types chosen for this trip; empty when unknown (every type but "Other" is then shown). */
    val vehicleClasses: List<VehicleClass> = emptyList(),
    /** The network quotes come from the hackathon's mock seller: their rows also say "ONDC demo seller". */
    val networkDemoSeller: Boolean = false,
) {
    /** The session's ride apps in leg order (ONDC excluded: it is booked inside RideMate). */
    val rideApps: List<Provider> get() = legs.map { it.provider }.filter { !it.isNetwork }.distinct()

    /**
     * The ride apps the list offers to open with the route ready ("Open <app>", goal 3): every app of the trip that is
     * still before booking, whether or not it has a price row yet.
     */
    val openableApps: List<Provider>
        get() = legs.filter { !it.provider.isNetwork && !it.dismissed && it.state in OPENABLE }.map { it.provider }.distinct()

    /** The types the manual price form offers: the chosen ones (every type when unknown). */
    val formClasses: List<VehicleClass> get() = vehicleClasses.ifEmpty { VehicleClass.entries.toList() }

    fun legFor(provider: Provider): LegEntity? = legs.firstOrNull { it.provider == provider }

    /** This comparison with [network] (ONDC quotes) in place of any earlier network rows, filtered like the others. */
    fun withNetworkRows(network: List<GatedRow>, demoSeller: Boolean): ComparisonData {
        val quotes = chosen(network.filter { it.status == QuoteStatus.NETWORK }, vehicleClasses)
        return copy(
            rows = rows.filter { it.status != QuoteStatus.NETWORK } + quotes,
            networkDemoSeller = demoSeller && quotes.isNotEmpty(),
        )
    }

    companion object {
        /** States in which a ride app can still be opened to compare (before any booking). */
        val OPENABLE: Set<RideState> = setOf(RideState.SELECTED, RideState.OPENED, RideState.QUOTED)

        fun empty(sessionId: String) = ComparisonData(sessionId, emptyList(), emptyList())

        /**
         * The rows of the vehicle types the user chose (goal 2): a trip for "Auto" lists no bike, cab or "Other" rows,
         * so "cheapest" is decided only among the chosen types. "Other" (parcel, rentals, outstation…) is never offered
         * on the trip screen, so it shows only when chosen; with no choice stored every other type shows.
         */
        fun chosen(rows: List<GatedRow>, classes: Collection<VehicleClass>): List<GatedRow> =
            if (classes.isEmpty()) rows.filter { it.vehicleClass != VehicleClass.OTHER } else rows.filter { it.vehicleClass in classes }
    }
}

/**
 * Room → [ComparisonData], live. Rows that would break I2 are dropped by [QuoteMapping.toGatedRow]; rows of vehicle
 * types the user did not choose for the trip are not listed ([ComparisonData.chosen]).
 */
class ComparisonRepository(private val db: RideMateDb) {
    fun observe(sessionId: String): Flow<ComparisonData> =
        combine(
            db.sessionDao().observe(sessionId),
            db.legDao().observeForSession(sessionId),
            db.quoteDao().observeForSession(sessionId),
        ) { session, legs, quotes ->
            val byId = legs.associateBy { it.id }
            val classes = session?.vehicleClasses.orEmpty()
            val rows = quotes.mapNotNull { q -> byId[q.legId]?.let { leg -> QuoteMapping.toGatedRow(leg.provider, q) } }
            ComparisonData(
                sessionId = sessionId,
                legs = legs,
                rows = ComparisonData.chosen(rows, classes),
                vehicleClasses = classes,
            )
        }

    /** The newest active session's id (null when none). */
    fun observeActiveSessionId(): Flow<String?> =
        db.sessionDao().observeActive().map { it.firstOrNull()?.id }.distinctUntilChanged()
}
