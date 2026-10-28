package app.ridemate.integration

import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.launch.LaunchRequest
import app.ridemate.launch.LaunchResult
import app.ridemate.ondc.OndcResult
import app.ridemate.session.GuardianRuntime
import app.ridemate.session.GuardianSessionApi
import app.ridemate.session.SessionSpec
import app.ridemate.trip.TripPlace
import app.ridemate.trip.TripPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/** Opens a ride app with the route ready (the launcher area's AppLauncher in production). */
fun interface RouteLauncher {
    suspend fun open(request: LaunchRequest): LaunchResult?
}

/** Starts the open-network search for a session's ONDC leg (OndcClient in production). */
fun interface NetworkSearch {
    suspend fun search(sessionId: String, legId: String, pickup: LatLng, drop: LatLng): OndcResult
}

/**
 * The Compare tap after the pre-session check passed (goal 3): one guardian session for the trip (its foreground
 * service, RideWatch legs, the bubble), the open-network search when ONDC is included, and the first ride app
 * opened with the route ready. The user then reads fares and books in each app themselves.
 *
 * A typed place whose coordinates are unknown is stored at [UNKNOWN_POINT]; the comparison then has no straight-line
 * distance (no ₹/km band) and opens apps without a prefilled route, exactly as the trip screen promised.
 */
class ComparisonStarter(
    private val guardian: GuardianSessionApi,
    private val launcher: RouteLauncher,
    private val network: NetworkSearch,
    private val keepSearchingDefault: () -> Boolean,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Starts the comparison of [plan]; returns its session id (the comparison screen shows it), or null when the
     * previous session still has bookings to cancel (I8). The guardian then keeps that session and says why (its
     * "finish cancelling first" notice); no app is opened and no network search starts for a session that won't exist.
     */
    fun start(plan: TripPlan, keepSearching: Boolean = keepSearchingDefault()): String? {
        val previous = guardian.status.value
        val refused = previous.sessionOpen && previous.needsForegroundService
        val sessionId = newSessionId()
        val spec = SessionSpec(
            pickup = placeOf(plan.pickup),
            drop = placeOf(plan.drop),
            providers = plan.providers,
            vehicleClasses = plan.vehicleClasses,
            keepSearching = keepSearching,
            sessionId = sessionId,
        )
        guardian.startSession(spec)
        if (refused) return null
        val pickupPoint = plan.pickup.point
        val dropPoint = plan.drop.point
        if (plan.includeOndc && pickupPoint != null && dropPoint != null) {
            launchGuarded { network.search(sessionId, GuardianRuntime.legIdOf(sessionId, Provider.ONDC), pickupPoint, dropPoint) }
        }
        plan.apps.firstOrNull()?.let { first ->
            launchGuarded { launcher.open(LaunchRequest(first, plan.pickup, plan.drop, GuardianRuntime.legIdOf(sessionId, first))) }
        }
        return sessionId
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    companion object {
        /** Stored for a place whose coordinates are unknown (typed, not looked up). */
        val UNKNOWN_POINT: LatLng = app.ridemate.compare.RoomActiveComparison.UNKNOWN_POINT

        fun placeOf(place: TripPlace): Place = Place(place.label, place.point ?: UNKNOWN_POINT)

        fun tripPlaceOf(place: Place): TripPlace = TripPlace(place.label, place.point.takeIf { it != UNKNOWN_POINT })

        fun isKnown(point: LatLng): Boolean = point != UNKNOWN_POINT
    }
}
