package app.ridemate.precheck

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.ridemate.core.model.Provider
import app.ridemate.launch.InstalledApps
import app.ridemate.watch.RideWatchService

/** [PrecheckProbes] over the real Android services. Every read is cheap and synchronous. */
class AndroidPrecheckProbes(
    private val context: Context,
    private val apps: InstalledApps,
) : PrecheckProbes {

    /** Fallback when no [RideWatchStatus] is bound: whether notification access is granted to RideMate. */
    override fun listenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override fun overlayAllowed(): Boolean = Settings.canDrawOverlays(context)

    override fun batteryExempt(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    override fun notificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun locationAllowed(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun installedRideApps(): Set<Provider> = apps.installedRideApps().toSet()

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** The settings screen each fix-it button opens; null for in-app actions (demo, choose apps, retry, …). */
object FixIntents {
    // The guardian's reminders must survive Doze (I8); the manifest declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
    @SuppressLint("BatteryLife")
    fun forAction(context: Context, action: FixAction): Intent? {
        val pkg = context.packageName
        val intent = when (action) {
            FixAction.OPEN_NOTIFICATION_ACCESS -> notificationAccess(context)
            FixAction.OPEN_OVERLAY_SETTINGS -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri())
            FixAction.REQUEST_BATTERY_EXEMPTION ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$pkg".toUri())
            FixAction.OPEN_NOTIFICATION_SETTINGS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            FixAction.OPEN_INTERNET_SETTINGS -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            FixAction.REQUEST_LOCATION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())
            FixAction.TRY_DEMO, FixAction.CHOOSE_APPS, FixAction.RETRY_READ, FixAction.TYPE_PRICE,
            FixAction.CONTINUE_WITHOUT_ONDC,
            -> null
        }
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Straight to RideMate's own switch on Android 11+, else the list of notification-access apps. */
    private fun notificationAccess(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, RideWatchService::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    /** The list of notification-access apps (when the detail screen is missing on a phone). */
    fun notificationAccessList(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
