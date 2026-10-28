package app.ridemate.onboarding

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.ridemate.R
import app.ridemate.notify.GuardianChannels
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * What the onboarding health check needs from RideWatch: has it seen the self-test notification tagged `token`?
 * Bound to the watch area's `app.ridemate.watch.RideWatchStatus`, which the listener feeds with RideMate's own
 * `rm_selftest` posts (it reads only their channel id and tag).
 */
fun interface RideWatchSelfTest {
    fun hasSeen(token: String): Boolean
}

/** Posts and removes the self-test notification. */
interface SelfTestNotifier {
    /** Posts the test notification tagged [token]; false when Android refused (notifications blocked). */
    fun post(token: String): Boolean

    fun cancel(token: String)
}

class AndroidSelfTestNotifier(context: Context) : SelfTestNotifier {
    private val app = context.applicationContext

    // areNotificationsEnabled() is checked first and a SecurityException is caught.
    @SuppressLint("MissingPermission")
    override fun post(token: String): Boolean {
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return false
        GuardianChannels.ensure(app) // one definition of every RideMate channel, rm_selftest included
        val notification = NotificationCompat.Builder(app, GuardianChannels.SELFTEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.onb_selftest_title))
            .setContentText(app.getString(R.string.onb_selftest_text))
            .setTimeoutAfter(TIMEOUT_MS)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return try {
            manager.notify(token, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override fun cancel(token: String) {
        runCatching { NotificationManagerCompat.from(app).cancel(token, NOTIFICATION_ID) }
    }

    companion object {
        const val NOTIFICATION_ID = 7_001
        private const val TIMEOUT_MS = 15_000L
    }
}

/** Result of the health check. */
enum class HealthResult {
    /** RideWatch saw the test notification: RideMate can see ride-app notifications. */
    SEEN,

    /** Posted, but RideWatch did not report it within the time limit. */
    NOT_SEEN,

    /** RideMate may not post notifications. */
    NOTIFICATIONS_BLOCKED,

    /** Notification access is off. */
    ACCESS_MISSING,
}

/**
 * The onboarding self-test (architecture §4.2): post a notification on channel `rm_selftest` and poll RideWatch
 * for at most [timeoutMs] (5 s) until it reports having seen it. The test notification is always removed.
 */
class HealthCheck(
    private val permissions: PermissionChecker,
    private val notifier: SelfTestNotifier,
    private val rideWatch: () -> RideWatchSelfTest,
    private val newToken: () -> String = { "rm-selftest-" + UUID.randomUUID() },
) {
    suspend fun run(timeoutMs: Long = TIMEOUT_MS, pollMs: Long = POLL_MS): HealthResult {
        if (!permissions.isGranted(AppPermission.POST_NOTIFICATIONS)) return HealthResult.NOTIFICATIONS_BLOCKED
        if (!permissions.isGranted(AppPermission.NOTIFICATION_ACCESS)) return HealthResult.ACCESS_MISSING
        val token = newToken()
        if (!notifier.post(token)) return HealthResult.NOTIFICATIONS_BLOCKED
        try {
            val watch = rideWatch()
            val seen = withTimeoutOrNull(timeoutMs) {
                while (!watch.hasSeen(token)) delay(pollMs)
                true
            }
            return if (seen == true) HealthResult.SEEN else HealthResult.NOT_SEEN
        } finally {
            notifier.cancel(token)
        }
    }

    companion object {
        /** "poll ≤ 5 s". */
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 200L
    }
}
