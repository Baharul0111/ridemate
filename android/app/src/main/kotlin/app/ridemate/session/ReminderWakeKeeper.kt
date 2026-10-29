package app.ridemate.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.ridemate.RideMateApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the guardian's reminders and +60 s checks on time with the phone locked in a pocket (goals 12–13).
 *
 * The session service fires them from a 1 s ticker, a Handler delay that does not advance while the CPU sleeps, and a
 * foreground service does not stop the CPU from sleeping. So while I8 holds (an extra booking still to cancel, or a
 * follow-up check pending) a PARTIAL_WAKE_LOCK keeps the CPU up; it is bounded (re-acquired with a timeout, released
 * as soon as I8 clears). Independently, a while-idle alarm for the next due time wakes the guardian even if the lock
 * is ignored (Doze without the battery exemption). Exact alarms are not used (no SCHEDULE_EXACT_ALARM; the audit
 * forbids it): the wake lock does the precise timing.
 */
class ReminderWakeKeeper(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private var lock: PowerManager.WakeLock? = null
    private var lockUntilElapsed = 0L
    private var alarmAt: Long? = null

    /** Holds or releases the wake lock for [i8] and keeps the backstop alarm at [nextDueElapsedMs]. */
    fun update(i8: Boolean, nextDueElapsedMs: Long?) {
        if (!i8) {
            release()
            return
        }
        hold()
        scheduleAlarm(nextDueElapsedMs)
    }

    fun release() {
        lock?.let { if (it.isHeld) runCatching { it.release() } }
        lock = null
        lockUntilElapsed = 0L
        alarmAt?.let { runCatching { alarms?.cancel(alarmIntent()) } }
        alarmAt = null
    }

    val isHolding: Boolean get() = lock?.isHeld == true

    private fun hold() {
        val now = android.os.SystemClock.elapsedRealtime()
        val current = lock
        if (current != null && current.isHeld && lockUntilElapsed - now > REFRESH_BEFORE_MS) return
        val next = current ?: power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply { setReferenceCounted(false) } ?: return
        runCatching { next.acquire(LOCK_TIMEOUT_MS) }
        lock = next
        lockUntilElapsed = now + LOCK_TIMEOUT_MS
    }

    private fun scheduleAlarm(atElapsedMs: Long?) {
        if (atElapsedMs == alarmAt) return
        val manager = alarms ?: return
        runCatching {
            if (atElapsedMs == null) {
                manager.cancel(alarmIntent())
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, alarmIntent())
            }
        }
        alarmAt = atElapsedMs
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, GuardianAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val TAG = "RideMate:reminders"

        /** Each acquire is bounded; it is renewed while I8 still holds. */
        const val LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val REFRESH_BEFORE_MS = 2 * 60_000L
        private const val REQUEST_CODE = 7_402
    }
}

/** The backstop alarm: fires the guardian's due reminders and checks, and brings the session service back if needed. */
class GuardianAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val graph = RideMateApp.graph(context)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    graph.guardianRuntime.awaitRestored()
                    graph.guardianRuntime.tick()
                    graph.guardianRuntime.awaitIdle()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}
