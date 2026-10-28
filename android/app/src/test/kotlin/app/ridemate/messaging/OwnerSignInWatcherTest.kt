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
    private val epoch = MutableStateFlow(0L)
    private var hashes: Set<String> = emptySet()
    private val owner = OwnerAccess({ auth.value }, { hashes })
    private val api = ProgrammableApiClient()
    private val registrar = OwnerTokenRegistrar(api, owner) { "fcm-current" }
    private val watcher = OwnerSignInWatcher(owner::isOwner) { registrar.registerCurrentToken() }

    @Test
    fun `registers once when the owner signs in, also when the owner list arrives after the sign-in`() = runTest {
        backgroundScope.launch { watcher.run(auth, epoch) }
        runCurrent()
        assertTrue("signed out: nothing sent", api.calls.isEmpty())

        // The owner signs in before Remote Config has delivered the owner list (the bundled list is empty).
        auth.value = ownerSignIn
        runCurrent()
        assertTrue(api.calls.isEmpty())
        hashes = setOf(OwnerAccess.emailHash(ownerEmail))
        epoch.value = 1
        runCurrent()
        assertEquals(listOf("fcm-current"), api.ownerTokens)

        // Later config activations do not send it again.
        epoch.value = 2
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
        hashes = setOf(OwnerAccess.emailHash(ownerEmail))
        api.ownerToken = ApiResult.Offline
        backgroundScope.launch { watcher.run(auth, epoch) }
        auth.value = ownerSignIn
        runCurrent()
        assertEquals(1, api.ownerTokens.size)
        api.ownerToken = ApiResult.Ok(Unit)
        epoch.value = 1
        runCurrent()
        assertEquals(2, api.ownerTokens.size)
        epoch.value = 2
        runCurrent()
        assertEquals(2, api.ownerTokens.size)
    }
}
