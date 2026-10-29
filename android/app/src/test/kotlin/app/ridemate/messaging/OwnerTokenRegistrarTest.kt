package app.ridemate.messaging

import app.ridemate.auth.AuthState
import app.ridemate.net.ApiResult
import app.ridemate.testing.ProgrammableApiClient
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's FCM token reaches `/v1/owner/fcm-token`; nobody else's token ever leaves the phone. */
class OwnerTokenRegistrarTest {
    private val ownerEmail = "Baharul.Owner@Example.com"
    private var auth: AuthState = AuthState.SignedOut
    /** The Google uid the server confirmed as the owner (`/v1/config-check` `is_owner`). */
    private var serverOwnerUid: String? = "uid-1"
    private val owner = OwnerAccess({ auth }, { state -> (state as? AuthState.Google)?.uid == serverOwnerUid })
    private val api = ProgrammableApiClient()
    private var currentToken: String? = "fcm-current"
    private val registrar = OwnerTokenRegistrar(api, owner) { currentToken }

    @Test
    fun `the owner's new token is registered`() = runTest {
        auth = AuthState.Google(ownerEmail, "Baharul", null, "uid-1")
        assertTrue(owner.isOwner())
        assertEquals(TokenRegistration.REGISTERED, registrar.onNewToken(" fcm-new "))
        assertEquals(listOf("fcm-new"), api.ownerTokens)
    }

    @Test
    fun `nobody else's token is sent - signed out, anonymous, another Google account, no server confirmation`() = runTest {
        val notOwners = listOf(
            AuthState.SignedOut,
            AuthState.Anonymous("anon-1"),
            AuthState.Google("rider@example.com", "Rider", null, "uid-2"),
            AuthState.Google(null, "No email", null, "uid-3"),
            // The owner's email on another account is not the owner: only the server's answer for this uid counts.
            AuthState.Google(ownerEmail, "Copy", null, "uid-4"),
        )
        for (state in notOwners) {
            auth = state
            assertFalse(state.toString(), owner.isOwner())
            assertEquals(TokenRegistration.NOT_OWNER, registrar.onNewToken("fcm"))
            assertEquals(TokenRegistration.NOT_OWNER, registrar.registerCurrentToken())
        }
        auth = AuthState.Google(ownerEmail, "Baharul", null, "uid-1")
        serverOwnerUid = null
        assertEquals(TokenRegistration.NOT_OWNER, registrar.onNewToken("fcm"))
        assertTrue(api.ownerTokens.isEmpty())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `blank tokens and server failures are reported, not retried in a loop`() = runTest {
        auth = AuthState.Google(ownerEmail, "Baharul", null, "uid-1")
        assertEquals(TokenRegistration.NO_TOKEN, registrar.onNewToken("   "))
        assertEquals(TokenRegistration.NO_TOKEN, registrar.onNewToken("x".repeat(OwnerTokenRegistrar.MAX_TOKEN_LENGTH + 1)))
        api.ownerToken = ApiResult.Offline
        assertEquals(TokenRegistration.FAILED, registrar.onNewToken("fcm"))
        api.ownerToken = ApiResult.Http(403, "forbidden")
        assertEquals(TokenRegistration.FAILED, registrar.onNewToken("fcm"))
        assertEquals(2, api.ownerTokens.size)
    }

    @Test
    fun `the current token is registered on demand (owner signs in, opens developer mode)`() = runTest {
        auth = AuthState.Google(ownerEmail, "Baharul", null, "uid-1")
        assertEquals(TokenRegistration.REGISTERED, registrar.registerCurrentToken())
        assertEquals(listOf("fcm-current"), api.ownerTokens)
        currentToken = null
        assertEquals(TokenRegistration.NO_TOKEN, registrar.registerCurrentToken())
        val failing = OwnerTokenRegistrar(api, owner) { throw IllegalStateException("FCM down") }
        assertEquals(TokenRegistration.NO_TOKEN, failing.registerCurrentToken())
    }

    @Test
    fun `a hung server is cut off by the handler's deadline`() = runTest {
        auth = AuthState.Google(ownerEmail, "Baharul", null, "uid-1")
        val hanging = object : app.ridemate.net.ApiClient by api {
            override suspend fun registerOwnerFcmToken(token: String): ApiResult<Unit> = awaitCancellation()
        }
        val messaging = OwnerMessaging(OwnerTokenRegistrar(hanging, owner) { "t" }, owner, { true })
        assertEquals(TokenRegistration.FAILED, messaging.onNewToken("t"))
        assertEquals(TokenRegistration.FAILED, messaging.registerCurrentToken())
    }

    @Test
    fun `a throwing owner check fails closed`() = runTest {
        auth = AuthState.Google(ownerEmail, "Baharul", null, "uid-1")
        val broken = OwnerAccess({ auth }, { error("status unreadable") })
        assertFalse(broken.isOwner())
    }
}
