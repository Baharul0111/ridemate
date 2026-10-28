package app.ridemate.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ridemate.R

/**
 * The threshold alerts of docs/slo-and-alerts.md §4, as the server's FCM data payload names them
 * (`{"type": "owner_alert", "alert": "<wire>", "value": "<number>"}`). Each maps to a title and a body that names the
 * runbook to open, in English and Hindi. [TEST] is the dispatcher's test-fire.
 */
enum class OwnerAlertKind(val wire: String, @param:StringRes val title: Int, @param:StringRes val body: Int) {
    SERVER_ERRORS("server_errors", R.string.owner_alert_server_errors_title, R.string.owner_alert_server_errors_body),
    SLOW_READS("slow_reads", R.string.owner_alert_slow_reads_title, R.string.owner_alert_slow_reads_body),
    READING_ACCURACY("reading_accuracy", R.string.owner_alert_reading_accuracy_title, R.string.owner_alert_reading_accuracy_body),
    RULE_MISS("rule_miss", R.string.owner_alert_rule_miss_title, R.string.owner_alert_rule_miss_body),
    MISSES_UNCLASSIFIED("misses_unclassified", R.string.owner_alert_misses_unclassified_title, R.string.owner_alert_misses_unclassified_body),
    SPEND("spend", R.string.owner_alert_spend_title, R.string.owner_alert_spend_body),
    BILLING_DRIFT("billing_drift", R.string.owner_alert_billing_drift_title, R.string.owner_alert_billing_drift_body),
    FIRESTORE_QUOTA("firestore_quota", R.string.owner_alert_firestore_quota_title, R.string.owner_alert_firestore_quota_body),
    PROBE_FAILURE("probe_failure", R.string.owner_alert_probe_failure_title, R.string.owner_alert_probe_failure_body),
    MODEL_MISSING("model_missing", R.string.owner_alert_model_missing_title, R.string.owner_alert_model_missing_body),
    KEY_AGE("key_age", R.string.owner_alert_key_age_title, R.string.owner_alert_key_age_body),
    TEST("test", R.string.owner_alert_test_title, R.string.owner_alert_test_body);

    companion object {
        fun fromWire(value: String?): OwnerAlertKind? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/**
 * One owner alert push. [kind] is null for an alert this build doesn't know (shown generically); [value] is the measured
 * number when the server sent one; [pushTitle]/[pushBody] are the push's own notification text (the owner's console
 * test messages), used only when the kind is unknown.
 */
data class OwnerAlert(
    val kind: OwnerAlertKind?,
    val value: String?,
    val pushTitle: String?,
    val pushBody: String?,
) {
    companion object {
        const val TYPE = "owner_alert"
        private const val MAX_TEXT = 200
        private val NUMBER = Regex("^-?\\d{1,12}(?:\\.\\d{1,4})?%?$")

        /**
         * Reads a push. Returns null for pushes that are not owner alerts (a `type` other than `owner_alert` with no
         * notification text). Free text is trimmed and capped; the value must look like a number.
         */
        fun parse(data: Map<String, String>, notificationTitle: String?, notificationBody: String?): OwnerAlert? {
            val type = data["type"]
            val title = notificationTitle?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
            val body = notificationBody?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
            if (type != null && type != TYPE) return null
            if (type == null && title == null && body == null) return null
            val value = data["value"]?.trim()?.takeIf { NUMBER.matches(it) }
            return OwnerAlert(OwnerAlertKind.fromWire(data["alert"]), value, title, body)
        }
    }
}

/** Text of an owner alert notification, from string resources (English + Hindi). */
data class OwnerAlertText(val title: String, val body: String)

object OwnerAlertTexts {
    fun of(context: Context, alert: OwnerAlert): OwnerAlertText {
        val kind = alert.kind
        val title = if (kind != null) context.getString(kind.title) else alert.pushTitle ?: context.getString(R.string.owner_alert_generic_title)
        val baseBody = if (kind != null) context.getString(kind.body) else alert.pushBody ?: context.getString(R.string.owner_alert_generic_body)
        val body = alert.value?.let { context.getString(R.string.owner_alert_with_value, baseBody, it) } ?: baseBody
        return OwnerAlertText(context.getString(R.string.owner_alert_title_format, title), body)
    }
}

/** Posts owner alerts on their own high-importance channel ("Owner alerts"); tapping one opens RideMate. */
class OwnerAlertNotifier(private val context: Context) {

    /** Shows [alert]; false when notifications are off or not permitted (nothing else to do: email alerts still go out). */
    fun show(alert: OwnerAlert): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel()
        val text = OwnerAlertTexts.of(context, alert)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val content = launch?.let {
            PendingIntent.getActivity(context, REQUEST_CODE, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_owner_alert)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .apply { content?.let(::setContentIntent) }
            .build()
        return try {
            manager.notify(notificationIdFor(alert), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.owner_alert_channel_name), NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.owner_alert_channel_description)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "owner_alerts"
        private const val REQUEST_CODE = 0x0A1E
        private const val BASE_ID = 0x0A1E00

        /** One notification per alert kind (a repeat replaces the previous one); unknown kinds share one. */
        fun notificationIdFor(alert: OwnerAlert): Int = BASE_ID + (alert.kind?.ordinal?.plus(1) ?: 0)
    }
}
