package app.ridemate.watch

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ridemate.R
import app.ridemate.notify.GuardianChannels
import app.ridemate.onboarding.RideWatchSelfTest
import app.ridemate.precheck.RideWatchPing
import app.ridemate.util.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/** What the pre-session check sees (§4.2 "Pre-session check"). */
enum class WatchHealth {
    /** Notification access is granted and the listener is bound. */
    OK,

    /** Notification access is off: Compare is blocked with a fix-it card. */
    ACCESS_OFF,

    /** Access is granted but the system has not bound the listener (a rebind was requested). */
    NOT_CONNECTED,
}

/** The onboarding self-test result (goal 20: "a quick self-test confirms RideMate can see notifications"). */
enum class SelfTestResult { PASSED, ACCESS_OFF, NOTIFICATIONS_BLOCKED, TIMED_OUT }

/**
 * RideWatch health for onboarding and the pre-session check: whether the listener is connected, a ping that asks the
 * system to rebind a granted-but-unbound listener, and the self-test, which posts a notification on RideMate's own
 * `rm_selftest` channel and waits for the listener to see it. The listener reads nothing of its own app's posts except
 * this channel's id and the self-test tag.
 */
class RideWatchStatus(private val context: Context, private val clock: Clock) : RideWatchSelfTest, RideWatchPing {
    private val _connected = MutableStateFlow(false)

    /** Tags of RideMate's own self-test posts the listener has seen (onboarding health check), newest last. */
    private val seenSelfTests = ConcurrentLinkedDeque<String>()

    /** True while the system has the listener bound. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    var lastConnectedAtWallMs: Long? = null
        private set

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    private var reread: (() -> Unit)? = null

    private val component: ComponentName get() = ComponentName(context, RideWatchService::class.java)

    fun isAccessGranted(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** The listener's health. A granted but unbound listener gets a rebind request. */
    fun health(): WatchHealth = when {
        !isAccessGranted() -> WatchHealth.ACCESS_OFF
        connected.value -> WatchHealth.OK
        else -> {
            requestRebind()
            WatchHealth.NOT_CONNECTED
        }
    }

    /** Asks the system to bind the listener again (after a disconnect, or when a check finds it unbound). */
    fun requestRebind() {
        try {
            NotificationListenerService.requestRebind(component)
        } catch (_: RuntimeException) {
            // Access was revoked; the pre-session check reports ACCESS_OFF.
        }
    }

    /**
     * Posts a self-test notification and waits up to [timeoutMs] for the listener to see it. The post is removed
     * afterwards either way.
     */
    @SuppressLint("MissingPermission") // canPostNotifications() checks POST_NOTIFICATIONS first.
    suspend fun selfTest(timeoutMs: Long = DEFAULT_SELF_TEST_TIMEOUT_MS): SelfTestResult {
        if (!isAccessGranted()) return SelfTestResult.ACCESS_OFF
        if (!canPostNotifications()) return SelfTestResult.NOTIFICATIONS_BLOCKED
        GuardianChannels.ensure(context)
        val token = UUID.randomUUID().toString()
        val seen = CompletableDeferred<Unit>()
        waiters[token] = seen
        val manager = NotificationManagerCompat.from(context)
        try {
            val notification = NotificationCompat.Builder(context, GuardianChannels.SELFTEST)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.guardian_selftest_title))
                .setContentText(context.getString(R.string.guardian_selftest_body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setTimeoutAfter(timeoutMs + SELF_TEST_GRACE_MS)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(token, SELF_TEST_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                return SelfTestResult.NOTIFICATIONS_BLOCKED
            }
            val ok = withTimeoutOrNull(timeoutMs) { seen.await() } != null
            if (!ok) requestRebind()
            return if (ok) SelfTestResult.PASSED else SelfTestResult.TIMED_OUT
        } finally {
            waiters.remove(token)
            manager.cancel(token, SELF_TEST_NOTIFICATION_ID)
        }
    }

    /** Called by the listener when it sees one of RideMate's own self-test posts. */
    fun onSelfTestSeen(tag: String?) {
        if (tag.isNullOrBlank()) return
        seenSelfTests.addLast(tag)
        while (seenSelfTests.size > SEEN_CAPACITY) seenSelfTests.pollFirst()
        waiters[tag]?.complete(Unit)
    }

    /** Onboarding's health check: has the listener seen the self-test post tagged [token]? */
    override fun hasSeen(token: String): Boolean = seenSelfTests.contains(token)

    /**
     * The pre-session check's ping: true when the listener is bound. A granted-but-unbound listener is asked to rebind
     * and given [rebindWaitMs] to come back before it counts as not responding.
     */
    override suspend fun ping(): Boolean = isConnected()

    suspend fun isConnected(rebindWaitMs: Long = REBIND_WAIT_MS): Boolean = when (health()) {
        WatchHealth.OK -> true
        WatchHealth.ACCESS_OFF -> false
        WatchHealth.NOT_CONNECTED -> withTimeoutOrNull(rebindWaitMs) { connected.first { it } } == true
    }

    /** The listener connected; [rereadActive] re-reads the shade on demand (I9, after a guardian restore). */
    fun onConnected(rereadActive: () -> Unit) {
        reread = rereadActive
        lastConnectedAtWallMs = clock.wallMs()
        _connected.value = true
    }

    fun onDisconnected() {
        reread = null
        _connected.value = false
    }

    /** Re-reads every active ride-app post as new (§4.5.8). Returns false when the listener is not connected. */
    fun requestReread(): Boolean {
        val action = reread ?: return false
        action()
        return true
    }

    private fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val DEFAULT_SELF_TEST_TIMEOUT_MS = 5_000L
        private const val SELF_TEST_GRACE_MS = 5_000L
        const val SELF_TEST_NOTIFICATION_ID = 7_301
        private const val SEEN_CAPACITY = 16

        /** How long the pre-session ping waits for a rebound listener (inside the check's own 2 s limit). */
        const val REBIND_WAIT_MS = 1_500L
    }
}
