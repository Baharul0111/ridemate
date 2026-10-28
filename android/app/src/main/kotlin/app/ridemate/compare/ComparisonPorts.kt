package app.ridemate.compare

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.ridemate.core.gate.SanityBands
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.data.LegEntity
import app.ridemate.data.RideMateDb
import app.ridemate.data.SessionEntity

/**
 * The guardian side of the comparison list (A3 implements it over its statechart and guardian).
 */
interface SessionCommands {
    /**
     * "Booked here": the user says they tapped Book in [provider]'s app. Records BOOK_REQUESTED for that leg through
     * the guardian, linked to the user's [tapId] (I1). Returns false when there is no such leg in the active session.
     */
    suspend fun bookedHere(provider: Provider, tapId: String): Boolean
}

/** Opens a ride app (the trip/launch area's AppLauncher implements this with route prefill). */
fun interface FareAppLauncher {
    /** Opens [provider]'s app on the user's tap; false when it isn't installed or can't be opened. */
    fun open(provider: Provider): Boolean
}

/**
 * Plain launch through `getLaunchIntentForPackage` (the fallback until the launcher area binds its AppLauncher).
 * Opening the app is the only thing RideMate does with it: it never acts inside it.
 */
class PackageLaunchLauncher(private val context: Context) : FareAppLauncher {
    override fun open(provider: Provider): Boolean {
        if (provider.isDemo || provider.isNetwork) return false
        val intent = context.packageManager.getLaunchIntentForPackage(provider.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

/** The leg whose ride app is on screen now, with what the fare reader needs about its trip. */
data class ActiveLeg(
    val sessionId: String,
    val legId: String,
    val provider: Provider,
    /** Straight-line trip distance in km (for the ₹/km sanity band); null when unknown. */
    val tripKm: Double?,
)

/** Which comparison and app a "Read fares" tap is about (the session area may bind a richer one). */
fun interface ActiveComparison {
    suspend fun current(): ActiveLeg?
}

/**
 * Default [ActiveComparison] over Room: the newest ACTIVE session, and its leg most recently opened (OPENED or
 * QUOTED, latest `last_event_at` first). ONDC legs are never read from a screen.
 */
class RoomActiveComparison(private val db: RideMateDb) : ActiveComparison {
    override suspend fun current(): ActiveLeg? {
        val session = db.sessionDao().active().firstOrNull() ?: return null
        val leg = db.legDao().forSession(session.id)
            .filter { !it.provider.isNetwork && it.state in READABLE }
            .maxByOrNull { it.lastEventAt ?: Long.MIN_VALUE } ?: return null
        return activeLegOf(session, leg)
    }

    companion object {
        /** States in which a ride app's price screen can be read. */
        val READABLE: Set<RideState> = setOf(RideState.SELECTED, RideState.OPENED, RideState.QUOTED)

        fun activeLegOf(session: SessionEntity, leg: LegEntity): ActiveLeg = ActiveLeg(
            sessionId = session.id,
            legId = leg.id,
            provider = leg.provider,
            tripKm = SanityBands.tripKm(session.pickup.toPlace().point, session.drop.toPlace().point).takeIf { it > 0 },
        )
    }
}

/** The installed versionCode of a ride app (fare parsers are versioned by it); null for demo/ONDC or not installed. */
fun interface InstalledVersions {
    fun versionCode(provider: Provider): Long?

    companion object {
        fun of(context: Context): InstalledVersions = InstalledVersions { provider ->
            if (provider.isDemo || provider.isNetwork) {
                null
            } else {
                runCatching {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(provider.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(provider.packageName, 0)
                    }
                    info.longVersionCode
                }.getOrNull()
            }
        }
    }
}
