package app.ridemate.onboarding

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ridemate.watch.RideWatchService

/** Everything RideMate asks Android for during onboarding (architecture §4.2 Onboarding). */
enum class AppPermission {
    /** Posting notifications (runtime permission on Android 13+, always on before). */
    POST_NOTIFICATIONS,

    /** Notification access for RideWatch (the listener), granted in system settings. */
    NOTIFICATION_ACCESS,

    /** Display over other apps (the bubble), granted in system settings. */
    OVERLAY,

    /** Location while the app is in use (coarse or fine). */
    LOCATION,

    /** Exemption from battery optimisation, so reminders survive Doze. */
    BATTERY_EXEMPTION,
}

/** Reads the current grant state; tests use a fake. */
fun interface PermissionChecker {
    fun isGranted(permission: AppPermission): Boolean
}

/** Every permission granted right now. */
fun PermissionChecker.granted(): Set<AppPermission> = AppPermission.entries.filter { isGranted(it) }.toSet()

class AndroidPermissionChecker(context: Context) : PermissionChecker {
    private val app = context.applicationContext

    override fun isGranted(permission: AppPermission): Boolean = when (permission) {
        // On Android 13+ this reflects the POST_NOTIFICATIONS grant; before, the user's app-notification switch.
        AppPermission.POST_NOTIFICATIONS -> NotificationManagerCompat.from(app).areNotificationsEnabled()
        AppPermission.NOTIFICATION_ACCESS -> NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
        AppPermission.OVERLAY -> Settings.canDrawOverlays(app)
        AppPermission.LOCATION ->
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        AppPermission.BATTERY_EXEMPTION ->
            app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) ?: false
    }
}

/**
 * The facts about this phone that change the onboarding path.
 *
 * @property sideloaded installed from outside Google Play (an APK from GitHub). Android 13+ then blocks the
 *   notification-access and overlay switches until the user allows "restricted settings".
 */
data class DeviceProfile(
    val sdkInt: Int,
    val release: String,
    val manufacturer: String,
    val sideloaded: Boolean,
) {
    /** Android 13+ restricts listener/overlay toggles for sideloaded apps. */
    val restrictedSettingsPossible: Boolean get() = sideloaded && sdkInt >= Build.VERSION_CODES.TIRAMISU

    /** POST_NOTIFICATIONS is a runtime permission only on Android 13+. */
    val notificationsAreRuntimePermission: Boolean get() = sdkInt >= Build.VERSION_CODES.TIRAMISU

    companion object {
        /** Installers that are app stores (not sideloading). */
        val STORE_INSTALLERS: Set<String> = setOf("com.android.vending", "com.google.android.feedback")

        fun of(context: Context): DeviceProfile = DeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            sideloaded = installerOf(context) !in STORE_INSTALLERS,
        )

        private fun installerOf(context: Context): String? = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
    }
}

/** System screens the onboarding steps open (always by intent; the user flips the switch). */
object PermissionIntents {
    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    /** RideWatch's own notification-access page (Android 11+), else the list of listeners. */
    fun notificationAccess(context: Context): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, RideWatchService::class.java).flattenToString(),
                ),
            )
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun overlay(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )

    /** App info, where ⋮ → "Allow restricted settings" lives. */
    fun appInfo(context: Context): List<Intent> = listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    /** The app's notification settings (when the runtime prompt can no longer be shown). */
    fun appNotificationSettings(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    ) + appInfo(context)

    /** Asks for the battery-optimisation exemption directly, else the optimisation list. */
    fun batteryExemption(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    ) + appInfo(context)
}
