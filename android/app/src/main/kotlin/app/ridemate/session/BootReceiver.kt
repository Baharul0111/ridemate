package app.ridemate.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ridemate.RideMateApp
import app.ridemate.crash.CrashSite
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After a reboot (or an app update, which also stops the service): moves outbox rows onto this boot's clock, restores
 * the guardian of a still-active session (its countdowns are rebased on the wall clock), re-syncs the reminder rows
 * and restarts the session service when a session is open or I8 holds. Reminders that came due while the phone was
 * off collapse into one immediate reminder.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val graph = RideMateApp.graph(context)
        // Null only when called outside a broadcast dispatch (tests); the work still runs.
        val pending: PendingResult? = goAsync()
        graph.appScope.launch {
            try {
                withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                    graph.outboxRepository.rebaseAfterReboot()
                    // The restore re-syncs the outbox rows and starts the session service when it must run.
                    graph.guardianRuntime.awaitIdle()
                }
            } catch (e: Exception) {
                graph.crashReporter.recordNonFatal(e, CrashSite.GUARDIAN)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        val HANDLED: Set<String> = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        private const val RESTORE_TIMEOUT_MS = 8_000L
    }
}
