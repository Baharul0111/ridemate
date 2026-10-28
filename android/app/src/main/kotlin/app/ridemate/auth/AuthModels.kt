package app.ridemate.auth

import android.app.Activity

/** Who is using RideMate. AI features need [Google]; [Anonymous] is manual mode (no AI calls). */
sealed interface AuthState {
    data object SignedOut : AuthState

    data class Anonymous(val uid: String) : AuthState

    data class Google(
        val email: String?,
        val name: String?,
        val photoUrl: String?,
        val uid: String = "",
    ) : AuthState
}

/** The signed-in Firebase user as the gateway sees it (Firebase types never leave [AuthBackend]). */
data class AuthUser(
    val uid: String,
    val isAnonymous: Boolean,
    val isGoogle: Boolean,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
)

/** Why an [AuthBackend] call failed. */
enum class AuthFailure { NETWORK, RECENT_LOGIN_REQUIRED, INVALID_CREDENTIAL, USER_GONE, OTHER }

/** Thrown by [AuthBackend] implementations; the gateway turns it into a result type. Carries no message text. */
class AuthBackendException(val failure: AuthFailure, cause: Throwable? = null) : Exception(failure.name, cause)

/** A Firebase ID token and its expiry (UTC epoch ms). */
data class BackendToken(val value: String, val expiresAtWallMs: Long)

/**
 * The Firebase Auth seam. Production: [FirebaseAuthBackend]; tests: a fake. Every suspend function throws
 * [AuthBackendException] on failure.
 */
interface AuthBackend {
    fun currentUser(): AuthUser?

    /** Calls [listener] now and on every sign-in / sign-out. Close the result to stop listening. */
    fun addStateListener(listener: (AuthUser?) -> Unit): AutoCloseable

    suspend fun signInWithGoogleIdToken(idToken: String): AuthUser

    suspend fun signInAnonymously(): AuthUser

    /** Throws [AuthBackendException] (USER_GONE when nobody is signed in). */
    suspend fun getIdToken(forceRefresh: Boolean): BackendToken

    fun signOut()

    /** `FirebaseUser.delete()`; RECENT_LOGIN_REQUIRED when Firebase wants a fresh sign-in first. */
    suspend fun deleteCurrentUser()
}

/** Result of the Credential Manager "Sign in with Google" sheet. */
sealed interface GoogleCredentialResult {
    data class Success(val idToken: String) : GoogleCredentialResult
    data object Cancelled : GoogleCredentialResult
    data object NoGoogleAccount : GoogleCredentialResult
    data object Failed : GoogleCredentialResult
}

/** The Credential Manager seam: shows Google's account sheet and returns an ID token bound to [hashedNonce]. */
fun interface GoogleCredentialSource {
    suspend fun requestIdToken(activity: Activity, hashedNonce: String): GoogleCredentialResult
}

sealed interface SignInResult {
    data class Success(val state: AuthState) : SignInResult
    data object Cancelled : SignInResult

    /** No Google account on the phone: offer manual mode / Android settings. */
    data object NoGoogleAccount : SignInResult
    data object NetworkError : SignInResult
    data object Failed : SignInResult
}

sealed interface DeleteAccountResult {
    data object Deleted : DeleteAccountResult

    /** Firebase needs a recent sign-in: sign in with Google again, then retry. */
    data object ReauthRequired : DeleteAccountResult
    data object SignedOut : DeleteAccountResult
    data object NetworkError : DeleteAccountResult
    data object Failed : DeleteAccountResult
}
