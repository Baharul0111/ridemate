package app.ridemate.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a ride session's foreground service is running. The session service sets it; the outbox (reminders
 * come from the service's tick while active, from WorkManager otherwise) and the update checker (a forced update
 * waits for the session to end, F5) read it.
 */
class SessionActivity {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(active: Boolean) {
        _active.value = active
    }
}
