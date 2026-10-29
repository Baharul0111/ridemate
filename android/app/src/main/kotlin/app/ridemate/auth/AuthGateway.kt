package app.ridemate.auth

import android.app.Activity
import app.ridemate.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sign-in for RideMate (architecture §4.2 Onboarding): Google through Credential Manager for AI features,
 * anonymous for manual mode, and the Firebase ID token every API call carries.
 */
interface AuthGateway : IdTokenSource {
    /** Current user; updates on every sign-in, sign-out and account deletion. */
    val state: StateFlow<AuthState>

    /** Shows Google's account sheet (all accounts, not only previously authorised ones) and signs in to Firebase. */
    suspend fun signInWithGoogle(activity: Activity): SignInResult

    /** Manual mode. A no-op success when someone is already signed in. */
    suspend fun signInAnonymously(): SignInResult

    /**
     * The Firebase ID token, cached until [DefaultAuthGateway.REFRESH_MARGIN_MS] before expiry; [forceRefresh]
     * skips the cache (after a 401).
     */
    override suspend fun idToken(forceRefresh: Boolean): IdTokenResult

    suspend fun signOut()

    /** `FirebaseUser.delete()` (erasure, §4.11). */
    suspend fun deleteAccount(): DeleteAccountResult

    fun currentUid(): String?
}

/** [AuthGateway] over the [AuthBackend] (Firebase) and [GoogleCredentialSource] (Credential Manager) seams. */
class DefaultAuthGateway(
    private val backend: AuthBackend,
    private val google: GoogleCredentialSource,
    private val clock: Clock,
    private val backendTimeoutMs: Long = BACKEND_TIMEOUT_MS,
    private val newNonce: () -> String = Nonce::newRaw,
) : AuthGateway, AutoCloseable {

    private data class CachedToken(val uid: String, val value: String, val expiresAtWallMs: Long)

    private val _state = MutableStateFlow(backend.currentUser().toState())
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val tokenMutex = Mutex()

    @Volatile
    private var cached: CachedToken? = null

    private val registration: AutoCloseable = backend.addStateListener { user ->
        val next = user.toState()
        if (cached?.uid != user?.uid) cached = null
        _state.value = next
    }

    override fun currentUid(): String? = backend.currentUser()?.uid

    override suspend fun signInWithGoogle(activity: Activity): SignInResult {
        val hashedNonce = Nonce.sha256Hex(newNonce())
        return when (val credential = google.requestIdToken(activity, hashedNonce)) {
            is GoogleCredentialResult.Success -> {
                if (Nonce.claimOf(credential.idToken) != hashedNonce) return SignInResult.Failed
                signInWith { bounded { backend.signInWithGoogleIdToken(credential.idToken) } }
            }
            GoogleCredentialResult.Cancelled -> SignInResult.Cancelled
            GoogleCredentialResult.NoGoogleAccount -> SignInResult.NoGoogleAccount
            GoogleCredentialResult.Failed -> SignInResult.Failed
        }
    }

    override suspend fun signInAnonymously(): SignInResult {
        backend.currentUser()?.let { return SignInResult.Success(it.toState()) }
        return signInWith { bounded { backend.signInAnonymously() } }
    }

    private suspend fun signInWith(block: suspend () -> AuthUser): SignInResult = try {
        val user = block()
        cached = null
        val next = user.toState()
        _state.value = next
        SignInResult.Success(next)
    } catch (e: AuthBackendException) {
        if (e.failure == AuthFailure.NETWORK) SignInResult.NetworkError else SignInResult.Failed
    }

    override suspend fun idToken(forceRefresh: Boolean): IdTokenResult = tokenMutex.withLock {
        val user = backend.currentUser()
        if (user == null) {
            cached = null
            return IdTokenResult.SignedOut
        }
        val current = cached
        if (!forceRefresh && current != null && current.uid == user.uid &&
            clock.wallMs() < current.expiresAtWallMs - REFRESH_MARGIN_MS
        ) {
            return IdTokenResult.Token(current.value, current.expiresAtWallMs)
        }
        try {
            val token = bounded { backend.getIdToken(forceRefresh) }
            cached = CachedToken(user.uid, token.value, token.expiresAtWallMs)
            IdTokenResult.Token(token.value, token.expiresAtWallMs)
        } catch (e: AuthBackendException) {
            cached = null
            when (e.failure) {
                AuthFailure.NETWORK -> IdTokenResult.NetworkError
                else -> IdTokenResult.Failed
            }
        }
    }

    override suspend fun signOut() {
        backend.signOut()
        cached = null
        _state.value = AuthState.SignedOut
    }

    override suspend fun deleteAccount(): DeleteAccountResult {
        if (backend.currentUser() == null) return DeleteAccountResult.SignedOut
        return try {
            bounded { backend.deleteCurrentUser() }
            afterDeletion()
            DeleteAccountResult.Deleted
        } catch (e: AuthBackendException) {
            when (e.failure) {
                AuthFailure.RECENT_LOGIN_REQUIRED -> DeleteAccountResult.ReauthRequired
                AuthFailure.NETWORK -> DeleteAccountResult.NetworkError
                AuthFailure.USER_GONE -> {
                    afterDeletion()
                    DeleteAccountResult.Deleted
                }
                else -> DeleteAccountResult.Failed
            }
        }
    }

    /**
     * Goal 21 (never hangs): every Firebase call has a time limit. A call that does not answer within [backendTimeoutMs]
     * counts as a network failure, so sign-in, the token and account deletion say what to do next instead of spinning.
     */
    private suspend fun <T> bounded(block: suspend () -> T): T =
        withTimeoutOrNull(backendTimeoutMs) { block() } ?: throw AuthBackendException(AuthFailure.NETWORK)

    private fun afterDeletion() {
        cached = null
        backend.signOut()
        _state.value = AuthState.SignedOut
    }

    override fun close() = registration.close()

    companion object {
        /** A cached token is refreshed this long before it expires. */
        const val REFRESH_MARGIN_MS: Long = 5 * 60 * 1000

        /** The longest any Firebase Auth call (sign-in, token, delete) may take before it counts as a network failure. */
        const val BACKEND_TIMEOUT_MS: Long = 20_000

        fun AuthUser?.toState(): AuthState = when {
            this == null -> AuthState.SignedOut
            isAnonymous -> AuthState.Anonymous(uid)
            else -> AuthState.Google(email, displayName, photoUrl, uid)
        }
    }
}
