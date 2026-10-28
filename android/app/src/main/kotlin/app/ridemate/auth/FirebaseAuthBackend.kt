package app.ridemate.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** [AuthBackend] over Firebase Auth. [auth] is resolved lazily so nothing touches Firebase until first use. */
class FirebaseAuthBackend(private val auth: () -> FirebaseAuth) : AuthBackend {

    override fun currentUser(): AuthUser? = auth().currentUser?.toAuthUser()

    override fun addStateListener(listener: (AuthUser?) -> Unit): AutoCloseable {
        val instance = auth()
        val firebaseListener = FirebaseAuth.AuthStateListener { listener(it.currentUser?.toAuthUser()) }
        instance.addAuthStateListener(firebaseListener)
        return AutoCloseable { instance.removeAuthStateListener(firebaseListener) }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): AuthUser = mapped {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = auth().signInWithCredential(credential).await().user ?: throw AuthBackendException(AuthFailure.OTHER)
        user.toAuthUser()
    }

    override suspend fun signInAnonymously(): AuthUser = mapped {
        val user = auth().signInAnonymously().await().user ?: throw AuthBackendException(AuthFailure.OTHER)
        user.toAuthUser()
    }

    override suspend fun getIdToken(forceRefresh: Boolean): BackendToken = mapped {
        val user = auth().currentUser ?: throw AuthBackendException(AuthFailure.USER_GONE)
        val result = user.getIdToken(forceRefresh).await()
        val token = result.token ?: throw AuthBackendException(AuthFailure.OTHER)
        BackendToken(token, result.expirationTimestamp * 1000)
    }

    override fun signOut() = auth().signOut()

    override suspend fun deleteCurrentUser() = mapped {
        val user = auth().currentUser ?: throw AuthBackendException(AuthFailure.USER_GONE)
        user.delete().await()
        Unit
    }

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthBackendException) {
        throw e
    } catch (e: Exception) {
        throw AuthBackendException(failureOf(e), e)
    }

    companion object {
        fun failureOf(e: Exception): AuthFailure = when (e) {
            is FirebaseNetworkException -> AuthFailure.NETWORK
            is FirebaseAuthRecentLoginRequiredException -> AuthFailure.RECENT_LOGIN_REQUIRED
            is FirebaseAuthInvalidUserException -> AuthFailure.USER_GONE
            is FirebaseAuthInvalidCredentialsException -> AuthFailure.INVALID_CREDENTIAL
            else -> AuthFailure.OTHER
        }

        private fun FirebaseUser.toAuthUser(): AuthUser = AuthUser(
            uid = uid,
            isAnonymous = isAnonymous,
            isGoogle = providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID },
            email = email,
            displayName = displayName,
            photoUrl = photoUrl?.toString(),
        )
    }
}
