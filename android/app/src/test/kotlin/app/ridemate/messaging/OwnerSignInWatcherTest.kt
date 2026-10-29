@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.messaging

import app.ridemate.auth.AuthState
import app.ridemate.net.ApiResult
import app.ridemate.testing.ProgrammableApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's token is registered when the owner signs in (FCM's onNewToken usually fires before any sign-in). */
class OwnerSignInWatcherTest {
    private val ownerEmail = "baharul.owner@example.com"
    private val ownerSignIn = AuthState.Google(ownerEmail, "Baharul", null, "uid-owner")
    private val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
    /** What `/v1/config-check` last confirmed (`is_owner`), as [ServerOwnerStatus.confirmedOwnerUid] publishes it. */
    private val serverOwnerUid = MutableStateFlow<String?>(null)
    private val owner = OwnerAccess({ auth.value }, { state -> (state as? AuthState.Google)?.uid == serverOwnerUid.value })
    private val api = ProgrammableApiClient()
    private val registrar = OwnerTokenRegistrar(api, owner) { "fcm-current" }
    private val watcher = OwnerSignInWatcher(owner::isOwner) { registrar.registerCurrentToken() }

    @Test
    fun `registers once when the owner signs in, also when the server's owner answer arrives after the sign-in`() = runTest {
        backgroundScope.launch { watcher.run(auth, serverOwnerUid) }
        runCurrent()
        assertTrue("signed out: nothing sent", api.calls.isEmpty())

        // The owner signs in before the server has answered /v1/config-check (the phone ships no owner list).
        auth.value = ownerSignIn
        runCurrent()
        assertTrue(api.calls.isEmpty())
        serverOwnerUid.value = "uid-owner"
        runCurrent()
        assertEquals(listOf("fcm-current"), api.ownerTokens)

        // The same answer again does not send it again.
        serverOwnerUid.value = "uid-owner"
        runCurrent()
        assertEquals(1, api.ownerTokens.size)

        // Someone else signs in on this phone: their token never leaves it.
        auth.value = AuthState.Google("rider@example.com", "Rider", null, "uid-rider")
        runCurrent()
        auth.value = AuthState.Anonymous("anon")
        runCurrent()
        assertEquals(1, api.ownerTokens.size)

        // The owner signs in again: registered again (the server may have dropped it on erase).
        auth.value = ownerSignIn
        runCurrent()
        assertEquals(2, api.ownerTokens.size)
    }

    @Test
    fun `a failed registration is retried at the next change, then not repeated`() = runTest {
        val changes = MutableStateFlow(0)
        serverOwnerUid.value = "uid-owner"
        api.ownerToken = ApiResult.Offline
        backgroundScope.launch { watcher.run(auth, changes) }
        auth.value = ownerSignIn
        runCurrent()
        assertEquals(1, api.ownerTokens.size)
        api.ownerToken = ApiResult.Ok(Unit)
        changes.value = 1
        runCurrent()
        assertEquals(2, api.ownerTokens.size)
        changes.value = 2
        runCurrent()
        assertEquals(2, api.ownerTokens.size)
    }
}
