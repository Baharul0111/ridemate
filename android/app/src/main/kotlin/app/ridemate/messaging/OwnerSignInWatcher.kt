package app.ridemate.messaging

import app.ridemate.auth.AuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Registers the owner's FCM token when the owner signs in. FCM issues a token once, usually at first launch, before
 * onboarding's Google sign-in, so [OwnerMessagingService.onNewToken] alone would find nobody signed in and the owner's
 * phone would never receive alert pushes.
 *
 * It re-checks on every sign-in change and whenever the server's owner answer changes (`/v1/config-check` `is_owner`
 * arrives after the sign-in), registers once per signed-in owner, and retries a failed registration at the next change.
 * Nothing is sent for anyone who is not the owner ([isOwner] fails closed).
 */
class OwnerSignInWatcher(
    private val isOwner: () -> Boolean,
    private val register: suspend () -> TokenRegistration,
) {
    /** Runs until the calling scope is cancelled. */
    suspend fun run(authStates: Flow<AuthState>, ownerChanges: Flow<Any?>) {
        var registeredFor: String? = null
        combine(authStates, ownerChanges) { state, _ -> state }.collect { state ->
            val google = state as? AuthState.Google
            if (google == null) {
                registeredFor = null
                return@collect
            }
            val who = "${google.uid}|${google.email}"
            if (who == registeredFor || !isOwner()) return@collect
            if (register() == TokenRegistration.REGISTERED) registeredFor = who
        }
    }
}
