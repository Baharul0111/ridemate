package app.ridemate.messaging

import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import kotlinx.coroutines.CancellationException

/** The current FCM registration token (production: `FirebaseMessaging.getInstance().token`). */
fun interface FcmTokenSource {
    /** The token, or null when FCM can't provide one right now. */
    suspend fun currentToken(): String?
}

enum class TokenRegistration {
    /** `POST /v1/owner/fcm-token` accepted the token. */
    REGISTERED,

    /** The signed-in user is not the owner: nothing was sent. */
    NOT_OWNER,

    /** No token (FCM unavailable, or a blank token). */
    NO_TOKEN,

    /** The server could not be reached or refused it; the next new token or [OwnerTokenRegistrar.registerCurrentToken] retries. */
    FAILED,
}

/**
 * Registers the owner's phone for alert pushes (§4.2 Observability: threshold alerts reach the owner as FCM pushes).
 * A token is sent to the server only when the signed-in user is the owner ([OwnerAccess]); everyone else's token never
 * leaves the phone.
 */
class OwnerTokenRegistrar(
    private val api: ApiClient,
    private val owner: OwnerAccess,
    private val tokens: FcmTokenSource,
) {
    /** FCM issued a new token (`onNewToken`). */
    suspend fun onNewToken(token: String): TokenRegistration {
        if (!owner.isOwner()) return TokenRegistration.NOT_OWNER
        val clean = token.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH } ?: return TokenRegistration.NO_TOKEN
        return when (api.registerOwnerFcmToken(clean)) {
            is ApiResult.Ok -> TokenRegistration.REGISTERED
            else -> TokenRegistration.FAILED
        }
    }

    /** Registers the current token, e.g. when the owner signs in or opens developer mode. */
    suspend fun registerCurrentToken(): TokenRegistration {
        if (!owner.isOwner()) return TokenRegistration.NOT_OWNER
        val token = try {
            tokens.currentToken()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return TokenRegistration.NO_TOKEN
        return onNewToken(token)
    }

    companion object {
        /** FCM tokens are a few hundred characters; anything longer is not a token. */
        const val MAX_TOKEN_LENGTH = 4096
    }
}
