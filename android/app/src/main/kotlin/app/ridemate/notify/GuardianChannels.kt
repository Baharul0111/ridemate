package app.ridemate.notify

import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import app.ridemate.R

/**
 * RideMate's notification channels. Importance decides how loud each kind is:
 * - [GUARDIAN] (low): the ongoing "RideMate is guarding your bookings" notification and quiet updates.
 * - [PROMPTS] (high, heads-up): sheets and questions ("Cancel the others?", MAYBE prompts, cancel cards).
 * - [ALERTS] (high, sound + strong vibration): loud alerts ("Your Rapido captain cancelled", "still looks active").
 * - [REMINDERS] (high, heads-up): "Still booked" reminders until every extra booking is proven cancelled.
 * - [OWNER] (default): owner alerts pushed by the server (developer mode).
 * - [SELFTEST] (low): the onboarding self-test post that RideWatch must see.
 */
object GuardianChannels {
    const val GUARDIAN = "rm_guardian"
    const val PROMPTS = "rm_prompts"
    const val ALERTS = "rm_alerts"
    const val REMINDERS = "rm_reminders"
    const val OWNER = "rm_owner"
    const val SELFTEST = "rm_selftest"

    /** Long, distinctive pattern for loud alerts (never the only signal: the text says what happened). */
    val LOUD_VIBRATION: LongArray = longArrayOf(0, 700, 250, 700, 250, 1_000)
    val PROMPT_VIBRATION: LongArray = longArrayOf(0, 250, 150, 250)

    val ALL: List<String> = listOf(GUARDIAN, PROMPTS, ALERTS, REMINDERS, OWNER, SELFTEST)

    /** Creates (or updates the names of) every channel. Safe to call often. */
    fun ensure(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val sound = Settings.System.DEFAULT_NOTIFICATION_URI
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channels = listOf(
            NotificationChannelCompat.Builder(GUARDIAN, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.guardian_channel_guardian))
                .setDescription(context.getString(R.string.guardian_channel_guardian_desc))
                .setShowBadge(false)
                .build(),
            NotificationChannelCompat.Builder(PROMPTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.guardian_channel_prompts))
                .setDescription(context.getString(R.string.guardian_channel_prompts_desc))
                .setVibrationEnabled(true)
                .setVibrationPattern(PROMPT_VIBRATION)
                .build(),
            NotificationChannelCompat.Builder(ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.guardian_channel_alerts))
                .setDescription(context.getString(R.string.guardian_channel_alerts_desc))
                .setSound(sound, attributes)
                .setVibrationEnabled(true)
                .setVibrationPattern(LOUD_VIBRATION)
                .setLightsEnabled(true)
                .build(),
            NotificationChannelCompat.Builder(REMINDERS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.guardian_channel_reminders))
                .setDescription(context.getString(R.string.guardian_channel_reminders_desc))
                .setVibrationEnabled(true)
                .setVibrationPattern(PROMPT_VIBRATION)
                .build(),
            NotificationChannelCompat.Builder(OWNER, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.guardian_channel_owner))
                .setDescription(context.getString(R.string.guardian_channel_owner_desc))
                .build(),
            NotificationChannelCompat.Builder(SELFTEST, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.guardian_channel_selftest))
                .setDescription(context.getString(R.string.guardian_channel_selftest_desc))
                .setShowBadge(false)
                .build(),
        )
        manager.createNotificationChannelsCompat(channels)
    }
}
