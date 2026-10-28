package app.ridemate.auth

/** Result of asking for a Firebase ID token. */
sealed interface IdTokenResult {
    /** [expiresAtWallMs] is the token's expiry (UTC epoch ms). */
    data class Token(val value: String, val expiresAtWallMs: Long) : IdTokenResult

    /** Nobody is signed in. */
    data object SignedOut : IdTokenResult

    /** The token needed a refresh and the network was unavailable. */
    data object NetworkError : IdTokenResult

    /** The account is gone, disabled or the refresh was rejected. */
    data object Failed : IdTokenResult
}

/** Source of the `Authorization: Bearer` token for API calls ([AuthGateway] implements it). */
fun interface IdTokenSource {
    suspend fun idToken(forceRefresh: Boolean): IdTokenResult
}
