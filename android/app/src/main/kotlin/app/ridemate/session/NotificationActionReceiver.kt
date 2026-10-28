package app.ridemate.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ridemate.RideMateApp
import app.ridemate.crash.CrashSite
import app.ridemate.notify.GuardianIntents
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles the buttons on RideMate's notifications, which mirror the bubble and the in-app sheets: Booked here,
 * Show me, Done, Stop, Yes cancel the others, Keep searching, Not now, Pick this driver, Yes, Not yet, Driver
 * cancelled, No it ended, Check screen, I cancelled it, Open <App>, Dismiss leg, Compare again, Pay, the ONDC fee
 * answers and the Keep searching toggle. Each tap gets a fresh tap id (I1) and is applied in order by the guardian.
 * (Read fares opens RideMate directly: activities cannot be started from here.)
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = GuardianIntents.target(intent) ?: return
        val graph = RideMateApp.graph(context)
        // Null only when called outside a broadcast dispatch (tests); the work still runs.
        val pending: PendingResult? = goAsync()
        graph.appScope.launch {
            try {
                withTimeoutOrNull(HANDLE_TIMEOUT_MS) { graph.guardianRuntime.performAndWait(target) }
            } catch (e: Exception) {
                graph.crashReporter.recordNonFatal(e, CrashSite.GUARDIAN)
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        /** goAsync gives about 10 s; the guardian applies a tap in milliseconds. */
        const val HANDLE_TIMEOUT_MS = 8_000L
    }
}
