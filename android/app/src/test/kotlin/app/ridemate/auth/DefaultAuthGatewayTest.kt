package app.ridemate.auth

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.testing.FakeAuthBackend
import app.ridemate.testing.FakeClock
import app.ridemate.testing.FakeGoogleCredentialSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class DefaultAuthGatewayTest {
    private val clock = FakeClock(wall = 1_793_000_000_000L)
    private val backend = FakeAuthBackend().also { it.now = { clock.wall } }
    private val google = FakeGoogleCredentialSource()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    private fun gateway(nonce: String = "raw-nonce") = DefaultAuthGateway(backend, google, clock) { nonce }

    @Test
    fun `state follows the backend user`() {
        assertEquals(AuthState.SignedOut, gateway().state.value)
        backend.user = FakeAuthBackend.anonymousUser("a1")
        assertEquals(AuthState.Anonymous("a1"), gateway().state.value)
        backend.user = FakeAuthBackend.googleUser("g1", "x@example.com")
        assertEquals(AuthState.Google("x@example.com", "Rider", "https://example.com/p.png", "g1"), gateway().state.value)
    }

    @Test
    fun `google sign-in passes the hashed nonce and checks it in the token`() = runBlocking {
        val gw = gateway("raw-1")
        val result = gw.signInWithGoogle(activity)

        assertTrue(result is SignInResult.Success)
        assertEquals(listOf(Nonce.sha256Hex("raw-1")), google.requestedNonces)
        assertTrue(gw.state.value is AuthState.Google)
        assertEquals(listOf("signInGoogle"), backend.log)
    }

    @Test
    fun `a token with another nonce is rejected before Firebase sees it`() = runBlocking {
        google.result = { GoogleCredentialResult.Success(FakeGoogleCredentialSource.jwtWithNonce("someone-else")) }
        assertEquals(SignInResult.Failed, gateway().signInWithGoogle(activity))
        google.result = { GoogleCredentialResult.Success(FakeGoogleCredentialSource.jwtWithNonce(null)) }
        assertEquals(SignInResult.Failed, gateway().signInWithGoogle(activity))
        assertTrue(backend.log.isEmpty())
        assertEquals(AuthState.SignedOut, gateway().state.value)
    }

    @Test
    fun `credential manager outcomes map to sign-in results`() = runBlocking {
        google.result = { GoogleCredentialResult.Cancelled }
        assertEquals(SignInResult.Cancelled, gateway().signInWithGoogle(activity))
        google.result = { GoogleCredentialResult.NoGoogleAccount }
        assertEquals(SignInResult.NoGoogleAccount, gateway().signInWithGoogle(activity))
        google.result = { GoogleCredentialResult.Failed }
        assertEquals(SignInResult.Failed, gateway().signInWithGoogle(activity))
        google.result = { GoogleCredentialResult.Success(FakeGoogleCredentialSource.jwtWithNonce(it)) }
        backend.nextFailure = AuthFailure.NETWORK
        assertEquals(SignInResult.NetworkError, gateway().signInWithGoogle(activity))
        backend.nextFailure = AuthFailure.INVALID_CREDENTIAL
        assertEquals(SignInResult.Failed, gateway().signInWithGoogle(activity))
    }

    @Test
    fun `anonymous sign-in is manual mode and never replaces an existing user`() = runBlocking {
        val gw = gateway()
        assertEquals(SignInResult.Success(AuthState.Anonymous("anon-uid")), gw.signInAnonymously())
        assertEquals(AuthState.Anonymous("anon-uid"), gw.state.value)

        backend.user = FakeAuthBackend.googleUser()
        backend.log.clear()
        val again = gateway().signInAnonymously()
        assertTrue((again as SignInResult.Success).state is AuthState.Google)
        assertTrue(backend.log.isEmpty())
    }

    @Test
    fun `id token is cached until five minutes before expiry`() = runBlocking {
        backend.user = FakeAuthBackend.googleUser()
        val gw = gateway()

        val first = gw.idToken(false) as IdTokenResult.Token
        assertEquals("token-1", first.value)
        assertEquals("token-1", (gw.idToken(false) as IdTokenResult.Token).value)

        clock.advance(54 * 60 * 1000L) // 6 min left: still cached
        assertEquals("token-1", (gw.idToken(false) as IdTokenResult.Token).value)
        clock.advance(2 * 60 * 1000L) // 4 min left: refresh
        assertEquals("token-2", (gw.idToken(false) as IdTokenResult.Token).value)

        assertEquals("token-3", (gw.idToken(true) as IdTokenResult.Token).value)
        assertEquals(listOf("getIdToken:false", "getIdToken:false", "getIdToken:true"), backend.log)
    }

    @Test
    fun `the token cache is dropped when the user changes`() = runBlocking {
        backend.user = FakeAuthBackend.googleUser("u1")
        val gw = gateway()
        val first = (gw.idToken(false) as IdTokenResult.Token).value
        gw.signOut()
        assertEquals(IdTokenResult.SignedOut, gw.idToken(false))
        backend.user = FakeAuthBackend.googleUser("u2")
        assertNotEquals(first, (gw.idToken(false) as IdTokenResult.Token).value)
    }

    @Test
    fun `token failures are classified`() = runBlocking {
        backend.user = FakeAuthBackend.googleUser()
        val gw = gateway()
        backend.nextFailure = AuthFailure.NETWORK
        assertEquals(IdTokenResult.NetworkError, gw.idToken(false))
        backend.nextFailure = AuthFailure.USER_GONE
        assertEquals(IdTokenResult.Failed, gw.idToken(false))
    }

    @Test
    fun `sign out and delete account`() = runBlocking {
        backend.user = FakeAuthBackend.googleUser()
        val gw = gateway()
        gw.signOut()
        assertEquals(AuthState.SignedOut, gw.state.value)
        assertEquals(DeleteAccountResult.SignedOut, gw.deleteAccount())

        backend.user = FakeAuthBackend.googleUser()
        val signedIn = gateway()
        backend.deleteFailure = AuthFailure.RECENT_LOGIN_REQUIRED
        assertEquals(DeleteAccountResult.ReauthRequired, signedIn.deleteAccount())
        assertTrue(signedIn.state.value is AuthState.Google)
        backend.deleteFailure = AuthFailure.NETWORK
        assertEquals(DeleteAccountResult.NetworkError, signedIn.deleteAccount())
        backend.deleteFailure = AuthFailure.OTHER
        assertEquals(DeleteAccountResult.Failed, signedIn.deleteAccount())

        backend.deleteFailure = null
        assertEquals(DeleteAccountResult.Deleted, signedIn.deleteAccount())
        assertEquals(AuthState.SignedOut, signedIn.state.value)
        assertNull(backend.user)
        assertEquals(listOf("signOut", "deleteUser", "deleteUser", "deleteUser", "deleteUser", "signOut"), backend.log)
    }

    @Test
    fun `listener stops on close`() = runBlocking {
        val gw = gateway()
        gw.close()
        backend.signInAnonymously()
        assertEquals(AuthState.SignedOut, gw.state.value)
    }
}
