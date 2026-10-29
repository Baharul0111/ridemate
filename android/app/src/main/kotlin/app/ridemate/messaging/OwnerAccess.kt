package app.ridemate.messaging

import app.ridemate.auth.AuthState
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.ui.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who the owner is, as the server says (contracts §1.3, §2.7). `/v1/config-check` answers `is_owner` for the signed-in
 * Google account: the server compares the verified email with its own `OWNER_EMAILS` secret. The phone ships no owner
 * list, so no install can learn or test-guess the owner's email from Remote Config or the APK.
 *
 * The last answer is remembered for that Google uid only, so developer mode and owner alerts keep working offline.
 * Any other account, an anonymous user or a signed-out phone is never the owner, and nothing but a server answer for
 * the account that is still signed in changes what is remembered (fails closed).
 */
class ServerOwnerStatus(
    private val store: KeyValueStore,
    private val authState: () -> AuthState,
    private val configCheck: suspend () -> ApiResult<ConfigCheckResponse>,
) {
    private val ownerUid = MutableStateFlow(store.get(K_OWNER_UID)?.takeIf { it.isNotBlank() })

    /** The Google uid the server last confirmed as the owner, or null; owner-only work re-checks when it changes. */
    val confirmedOwnerUid: StateFlow<String?> = ownerUid.asStateFlow()

    /** True when [state] is the Google account the server confirmed as the owner. */
    fun isOwner(state: AuthState): Boolean {
        val uid = googleUid(state) ?: return false
        return uid == ownerUid.value
    }

    /**
     * Asks `/v1/config-check` about the signed-in Google account and remembers the answer. Offline, a server error or an
     * account change during the call leave the last answer as it was.
     */
    suspend fun refresh() {
        val uid = googleUid(authState()) ?: return
        val body = configCheck().getOrNull() ?: return
        if (googleUid(authState()) != uid) return
        record(uid, body.isOwner)
    }

    /** Records the server's answer for [uid]: a "no" for another account never clears the owner's entry. */
    fun record(uid: String, isOwner: Boolean) {
        val current = ownerUid.value
        val next = when {
            isOwner -> uid
            current == uid -> null
            else -> current
        }
        if (next == current) return
        store.put(K_OWNER_UID, next)
        ownerUid.value = next
    }

    /** Erase: forget the owner confirmation. */
    fun clear() {
        store.put(K_OWNER_UID, null)
        ownerUid.value = null
    }

    private fun googleUid(state: AuthState): String? = (state as? AuthState.Google)?.uid?.takeIf { it.isNotBlank() }

    companion object {
        const val PREFS_NAME = "ridemate_owner"
        private const val K_OWNER_UID = "server_confirmed_owner_uid"
    }
}

/**
 * Whether the signed-in user is the owner, for owner alerts and the owner's FCM token: true only for the Google account
 * the server confirmed ([ServerOwnerStatus]). Anonymous and signed-out users are never the owner.
 */
class OwnerAccess(
    private val authState: () -> AuthState,
    private val isOwnerAccount: (AuthState) -> Boolean,
) {
    /** True when the signed-in user is the owner (developer mode enabled for them). */
    fun isOwner(): Boolean {
        val state = authState() as? AuthState.Google ?: return false
        return runCatching { isOwnerAccount(state) }.getOrDefault(false)
    }
}
