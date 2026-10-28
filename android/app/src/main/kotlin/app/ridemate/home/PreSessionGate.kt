package app.ridemate.home

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.ridemate.core.model.Provider
import app.ridemate.onboarding.AppPermission
import app.ridemate.onboarding.PermissionChecker

/** A reason Compare cannot start, or should warn first (architecture §4.2 Pre-session check). */
enum class PreSessionIssue {
    /** RideWatch is blind: notification access is off or the listener is disconnected. Blocks Compare. */
    LISTENER_OFF,

    /** No floating button: display over other apps is off. Blocks Compare. */
    OVERLAY_OFF,

    /** Reminders could not be shown. Blocks Compare. */
    NOTIFICATIONS_OFF,

    /** None of Uber, Ola, Rapido is installed: offer Try the demo. Blocks Compare. */
    NO_SUPPORTED_APP,

    /** Battery optimisation may delay reminders. Warning. */
    BATTERY_OPTIMISED,

    /** Pickup must be typed. Warning. */
    LOCATION_OFF,

    /** No connection: ride apps and AI reading need one. Warning. */
    OFFLINE,
}

/** The outcome of the pre-session check: Compare starts only when [blocking] is empty. */
data class PreSessionReport(val blocking: List<PreSessionIssue>, val warnings: List<PreSessionIssue>) {
    val canStart: Boolean get() = blocking.isEmpty()
    val clean: Boolean get() = blocking.isEmpty() && warnings.isEmpty()

    companion object {
        val READY = PreSessionReport(emptyList(), emptyList())
    }
}

/**
 * Runs before every Compare tap. The integration binds A2's PreSessionCheck (which also pings the listener);
 * [DefaultPreSessionGate] is the permission-based check used until then.
 */
fun interface PreSessionGate {
    suspend fun check(): PreSessionReport
}

/** Which ride apps are installed (manifest `<queries>` makes exactly these three visible). */
fun interface InstalledRideApps {
    fun installed(): Set<Provider>
}

class PackageManagerRideApps(private val context: Context) : InstalledRideApps {
    override fun installed(): Set<Provider> = Provider.LIVE_APPS.filter { provider ->
        runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(provider.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(provider.packageName, 0)
            }
        }.isSuccess
    }.toSet()
}

/** Whether the phone has a validated internet connection. */
fun interface Connectivity {
    fun isOnline(): Boolean
}

class AndroidConnectivity(private val context: Context) : Connectivity {
    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class DefaultPreSessionGate(
    private val permissions: PermissionChecker,
    private val rideApps: InstalledRideApps,
    private val connectivity: Connectivity,
) : PreSessionGate {
    override suspend fun check(): PreSessionReport {
        val blocking = buildList {
            if (!permissions.isGranted(AppPermission.NOTIFICATION_ACCESS)) add(PreSessionIssue.LISTENER_OFF)
            if (!permissions.isGranted(AppPermission.OVERLAY)) add(PreSessionIssue.OVERLAY_OFF)
            if (!permissions.isGranted(AppPermission.POST_NOTIFICATIONS)) add(PreSessionIssue.NOTIFICATIONS_OFF)
            if (rideApps.installed().isEmpty()) add(PreSessionIssue.NO_SUPPORTED_APP)
        }
        val warnings = buildList {
            if (!permissions.isGranted(AppPermission.BATTERY_EXEMPTION)) add(PreSessionIssue.BATTERY_OPTIMISED)
            if (!permissions.isGranted(AppPermission.LOCATION)) add(PreSessionIssue.LOCATION_OFF)
            if (!connectivity.isOnline()) add(PreSessionIssue.OFFLINE)
        }
        return PreSessionReport(blocking, warnings)
    }
}
