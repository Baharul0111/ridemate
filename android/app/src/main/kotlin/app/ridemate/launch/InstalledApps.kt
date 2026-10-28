package app.ridemate.launch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.ridemate.core.model.Provider

/**
 * The ride apps on this phone. Only Uber, Ola and Rapido are visible to RideMate (the manifest's `<queries>`), so
 * nothing else about the user's installed apps can be read.
 */
interface InstalledApps {
    /** True when [provider] is a ride app that is installed and can be launched. Demo and ONDC are never "installed". */
    fun isInstalled(provider: Provider): Boolean

    /** The app's own launcher intent, or null when it is missing or has no launcher activity. */
    fun launchIntent(provider: Provider): Intent?

    /** True when some activity of the intent's package can handle [intent]. */
    fun canHandle(intent: Intent): Boolean

    /** The installed ride apps, in [Provider.LIVE_APPS] order. */
    fun installedRideApps(): List<Provider> = Provider.LIVE_APPS.filter(::isInstalled)
}

/** [InstalledApps] over the [PackageManager]. */
class PackageManagerApps(private val context: Context) : InstalledApps {
    private val pm: PackageManager get() = context.packageManager

    override fun isInstalled(provider: Provider): Boolean = launchIntent(provider) != null

    override fun launchIntent(provider: Provider): Intent? {
        if (provider !in Provider.LIVE_APPS) return null
        return runCatching { pm.getLaunchIntentForPackage(provider.packageName) }.getOrNull()
    }

    override fun canHandle(intent: Intent): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L)).isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).isNotEmpty()
        }
    }.getOrDefault(false)
}
