package app.ridemate.messaging

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What [OwnerMessagingService] does with FCM callbacks, kept free of the service so it is unit-tested:
 * - a new token is registered with the server only for the owner ([OwnerTokenRegistrar]);
 * - an owner alert push is shown only while the owner is signed in on this phone (a push that reaches a phone the owner
 *   signed out of is dropped, so ops metadata never shows to anyone else).
 */
class OwnerMessaging(
    private val registrar: OwnerTokenRegistrar,
    private val owner: OwnerAccess,
    private val notify: (OwnerAlert) -> Boolean,
    private val deadlineMs: Long = DEADLINE_MS,
) {
    /** FCM's new token; returns when the registration finished or [deadlineMs] passed (FCM calls this off the main thread). */
    suspend fun onNewToken(token: String): TokenRegistration =
        withTimeoutOrNull(deadlineMs) { registrar.onNewToken(token) } ?: TokenRegistration.FAILED

    /** Registers the current token now (owner signed in, or opened developer mode). */
    suspend fun registerCurrentToken(): TokenRegistration =
        withTimeoutOrNull(deadlineMs) { registrar.registerCurrentToken() } ?: TokenRegistration.FAILED

    /** A push arrived: returns true when an owner alert was shown. */
    fun onMessage(data: Map<String, String>, notificationTitle: String?, notificationBody: String?): Boolean {
        val alert = OwnerAlert.parse(data, notificationTitle, notificationBody) ?: return false
        if (!owner.isOwner()) return false
        return notify(alert)
    }

    companion object {
        /** FCM gives a handler about 10–20 s; registration must finish well inside that. */
        const val DEADLINE_MS: Long = 9_000
    }
}

/** Production [FcmTokenSource]. */
object FirebaseFcmTokenSource : FcmTokenSource {
    override suspend fun currentToken(): String? = FirebaseMessaging.getInstance().token.await()?.takeIf { it.isNotBlank() }
}
