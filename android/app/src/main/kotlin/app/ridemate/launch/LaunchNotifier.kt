package app.ridemate.launch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ridemate.R
import app.ridemate.core.model.Provider

/** Posts the heads-up "Open Ola" notification used when Android does not let RideMate open an app by itself. */
interface LaunchNotifier {
    /** Returns false when the notification could not be shown (notifications off for RideMate). */
    fun postOpenApp(request: LaunchRequest): Boolean

    fun cancel(provider: Provider)
}

/**
 * Android 15+ only lets an app start another app's activity from the background in a few cases (a visible overlay
 * window is one). When none applies, [AppLauncher] posts this heads-up notification instead. Tapping it opens
 * [OpenRideAppActivity], which is then in the foreground and opens the ride app (the user's tap is the trigger).
 */
class AndroidLaunchNotifier(private val context: Context) : LaunchNotifier {

    override fun postOpenApp(request: LaunchRequest): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel()
        val app = request.provider.displayName
        val tap = PendingIntent.getActivity(
            context,
            requestCode(request.provider),
            OpenRideAppActivity.intent(context, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.launch_heads_up_title, app))
            .setContentText(context.getString(R.string.launch_heads_up_text, app, request.drop.label))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.launch_heads_up_text, app, request.drop.label)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(tap)
            .addAction(0, context.getString(R.string.launch_heads_up_action, app), tap)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        return try {
            manager.notify(notificationId(request.provider), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    override fun cancel(provider: Provider) {
        NotificationManagerCompat.from(context).cancel(notificationId(provider))
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.launch_channel_name), NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.launch_channel_description)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID: String = "ridemate_open_app"
        private const val NOTIFICATION_ID_BASE = 7_300
        private const val TIMEOUT_MS = 10L * 60 * 1000

        fun notificationId(provider: Provider): Int = NOTIFICATION_ID_BASE + provider.ordinal

        private fun requestCode(provider: Provider): Int = NOTIFICATION_ID_BASE + provider.ordinal
    }
}
