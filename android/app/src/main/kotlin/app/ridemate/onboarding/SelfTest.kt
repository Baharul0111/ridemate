package app.ridemate.onboarding

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.ridemate.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * What the onboarding health check needs from RideWatch: has it seen the self-test notification tagged `token`?
 * Bound to A3's RideWatchStatus by the integration; by default it is [SelfTestRegistry], which RideWatch feeds.
 */
fun interface RideWatchSelfTest {
    fun hasSeen(token: String): Boolean
}

/**
 * Where RideWatch reports RideMate's own self-test posts. RideWatch's listener calls [onPosted] for a post whose
 * package is RideMate's own and whose channel is [CHANNEL_ID] ([isSelfTestPost]); it reads nothing else from it.
 * Keeps the last [CAPACITY] tags.
 */
class SelfTestRegistry : RideWatchSelfTest {
    private val seen = ConcurrentLinkedDeque<String>()

    fun onPosted(tag: String?) {
        if (tag.isNullOrBlank()) return
        seen.addLast(tag)
        while (seen.size > CAPACITY) seen.pollFirst()
    }

    override fun hasSeen(token: String): Boolean = seen.contains(token)

    companion object {
        /** The notification channel of the onboarding self-test. */
        const val CHANNEL_ID = "rm_selftest"
        const val NOTIFICATION_ID = 7_001
        private const val CAPACITY = 16

        /** True for RideMate's own self-test post (RideWatch checks this before [onPosted]). */
        fun isSelfTestPost(postPackage: String, channelId: String?, ownPackage: String): Boolean =
            postPackage == ownPackage && channelId == CHANNEL_ID
    }
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
        val channel = NotificationChannel(
            SelfTestRegistry.CHANNEL_ID,
            app.getString(R.string.onb_selftest_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = app.getString(R.string.onb_selftest_channel_description) }
        app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(app, SelfTestRegistry.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.onb_selftest_title))
            .setContentText(app.getString(R.string.onb_selftest_text))
            .setTimeoutAfter(TIMEOUT_MS)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return try {
            manager.notify(token, SelfTestRegistry.NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override fun cancel(token: String) {
        runCatching { NotificationManagerCompat.from(app).cancel(token, SelfTestRegistry.NOTIFICATION_ID) }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
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
