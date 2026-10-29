package app.ridemate.dev

import app.ridemate.auth.AuthState
import app.ridemate.ui.KeyValueStore
import app.ridemate.ui.getBoolean
import app.ridemate.ui.putBoolean
import app.ridemate.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** What a tap on the version line did. */
sealed interface VersionTapOutcome {
    /** Not enough taps yet; [remaining] more open developer mode (shown from the third tap). */
    data class Counting(val remaining: Int) : VersionTapOutcome

    /** Developer mode is now on. */
    data object Enabled : VersionTapOutcome

    /** Developer mode was already on (open it). */
    data object AlreadyEnabled : VersionTapOutcome

    /** Seven taps, but no Google sign-in: only the owner's Google account can open developer mode. */
    data object SignInRequired : VersionTapOutcome

    /** Seven taps by a Google account that is not the owner's. */
    data object NotOwner : VersionTapOutcome
}

/**
 * The hidden developer mode (architecture §4.19): seven taps on the version in About, owner only. It is on while
 * the switch is on **and** the server confirmed the signed-in Google account as the owner (`/v1/config-check`
 * `is_owner`, [app.ridemate.messaging.ServerOwnerStatus]); the phone ships no owner list. Signing out, another
 * account, or the server no longer naming this account turns it off immediately.
 */
class DeveloperModeGate(
    private val store: KeyValueStore,
    private val auth: StateFlow<AuthState>,
    private val isOwnerAccount: (AuthState) -> Boolean,
    private val clock: Clock,
    ownerChanges: Flow<Any?> = flowOf(Unit),
) {
    private val switchedOn = MutableStateFlow(store.getBoolean(K_ENABLED))
    private val recorderOn = MutableStateFlow(store.getBoolean(K_RECORDER, default = true))
    private val ownerFcm = MutableStateFlow(store.getBoolean(K_OWNER_FCM))

    private var taps = 0
    private var lastTapElapsedMs = Long.MIN_VALUE

    /** Whether developer mode is on, re-evaluated on every sign-in change. */
    val enabled: Flow<Boolean> = combine(switchedOn, auth, ownerChanges) { on, state, _ -> on && isOwner(state) }.distinctUntilChanged()

    /** The Recorder's own switch (on by default once developer mode is on). */
    val recorderSwitch: StateFlow<Boolean> = recorderOn.asStateFlow()

    /** The owner-alerts (FCM token registration) switch. */
    val ownerAlertsSwitch: StateFlow<Boolean> = ownerFcm.asStateFlow()

    fun isEnabled(): Boolean = switchedOn.value && isOwner(auth.value)

    /** Developer mode is on and the Recorder switch is on: RideWatch should hand posts to the Recorder. */
    fun isRecording(): Boolean = isEnabled() && recorderOn.value

    fun isOwner(state: AuthState = auth.value): Boolean {
        if (state !is AuthState.Google) return false
        return runCatching { isOwnerAccount(state) }.getOrDefault(false)
    }

    /** One tap on the version line. Taps more than [TAP_GAP_MS] apart start counting again. */
    @Synchronized
    fun registerVersionTap(): VersionTapOutcome {
        val now = clock.elapsedRealtimeMs()
        taps = if (lastTapElapsedMs != Long.MIN_VALUE && now - lastTapElapsedMs <= TAP_GAP_MS) taps + 1 else 1
        lastTapElapsedMs = now
        if (taps < TAPS_TO_ENABLE) return VersionTapOutcome.Counting(TAPS_TO_ENABLE - taps)
        taps = 0
        val state = auth.value
        return when {
            isEnabled() -> VersionTapOutcome.AlreadyEnabled
            state !is AuthState.Google -> VersionTapOutcome.SignInRequired
            !isOwner(state) -> VersionTapOutcome.NotOwner
            else -> {
                setSwitch(true)
                VersionTapOutcome.Enabled
            }
        }
    }

    fun disable() = setSwitch(false)

    fun setRecorderOn(on: Boolean) {
        store.putBoolean(K_RECORDER, on)
        recorderOn.value = on
    }

    fun setOwnerAlertsOn(on: Boolean) {
        store.putBoolean(K_OWNER_FCM, on)
        ownerFcm.value = on
    }

    /** Erase: developer mode off, switches back to their defaults. */
    fun reset() {
        store.clear()
        switchedOn.value = false
        recorderOn.value = true
        ownerFcm.value = false
        taps = 0
    }

    private fun setSwitch(on: Boolean) {
        store.putBoolean(K_ENABLED, on)
        switchedOn.value = on
    }

    companion object {
        const val TAPS_TO_ENABLE = 7
        const val TAP_GAP_MS = 3_000L

        private const val K_ENABLED = "enabled"
        private const val K_RECORDER = "recorder_on"
        private const val K_OWNER_FCM = "owner_fcm_on"
    }
}
