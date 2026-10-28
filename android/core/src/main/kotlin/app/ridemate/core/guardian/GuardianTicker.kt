package app.ridemate.core.guardian

import app.ridemate.core.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives [Guardian.tick] at each due time. The app runs it in the foreground service's scope during a session (I8:
 * reminders never depend on WorkManager under Doze); tests run it on a virtual-time dispatcher. Must share the
 * guardian's dispatcher.
 */
class GuardianTicker(private val guardian: Guardian, private val clock: Clock) {

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val version = guardian.scheduleVersion.value
            val due = guardian.nextDueElapsedMs()
            val now = clock.elapsedMs()
            if (due != null && due <= now) {
                guardian.tick()
                continue
            }
            val changed: suspend () -> Unit = { guardian.scheduleVersion.first { it != version } }
            if (due == null) changed() else withTimeoutOrNull(due - now) { changed() }
        }
    }
}
