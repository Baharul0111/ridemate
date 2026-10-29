package app.ridemate.watch

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import app.ridemate.onboarding.RideWatchSelfTest
import app.ridemate.precheck.RideWatchPing
import app.ridemate.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * RideWatch health for onboarding and the pre-session check: whether the listener is connected, a ping that asks the
 * system to rebind a granted-but-unbound listener, and what the listener saw of the one self-test (onboarding's
 * HealthCheck posts on RideMate's own `rm_selftest` channel). The listener reads nothing of its own app's posts except
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

    /** Called by the listener when it sees one of RideMate's own self-test posts. */
    fun onSelfTestSeen(tag: String?) {
        if (tag.isNullOrBlank()) return
        seenSelfTests.addLast(tag)
        while (seenSelfTests.size > SEEN_CAPACITY) seenSelfTests.pollFirst()
    }

    /**
     * The one self-test (onboarding's [app.ridemate.onboarding.HealthCheck] posts on `rm_selftest` and polls this):
     * has the listener seen the self-test post tagged [token]?
     */
    override fun hasSeen(token: String): Boolean = seenSelfTests.contains(token)

    /** The self-test post went unseen: ask the system to bind the listener again. */
    override fun onNotSeen() = requestRebind()

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

    companion object {
        private const val SEEN_CAPACITY = 16

        /** How long the pre-session ping waits for a rebound listener (inside the check's own 2 s limit). */
        const val REBIND_WAIT_MS = 1_500L
    }
}
