package app.ridemate.messaging

import app.ridemate.auth.AuthState
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.ui.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner is whoever the server says (`/v1/config-check` `is_owner`, contracts §2.7): the phone ships no owner list,
 * remembers the answer for that Google uid only, and fails closed for everyone else.
 */
class ServerOwnerStatusTest {
    private val owner = AuthState.Google("owner@example.com", "Owner", null, "uid-owner")
    private val rider = AuthState.Google("rider@example.com", "Rider", null, "uid-rider")
    private var auth: AuthState = AuthState.SignedOut
    private var answer: ApiResult<ConfigCheckResponse> = ApiResult.Offline
    private var checks = 0
    private val kv = InMemoryKeyValueStore()

    private fun status() = ServerOwnerStatus(kv, { auth }) {
        checks++
        answer
    }

    @Test
    fun `the owner is confirmed by the server, remembered for that account and only that account`() = runTest {
        val status = status()
        auth = owner
        assertFalse("nothing confirmed yet", status.isOwner(owner))
        answer = ApiResult.Ok(ConfigCheckResponse(isOwner = true))
        status.refresh()
        assertTrue(status.isOwner(owner))
        assertEquals("uid-owner", status.confirmedOwnerUid.value)
        assertFalse(status.isOwner(rider))
        assertFalse(status.isOwner(AuthState.Anonymous("uid-owner")))
        assertFalse(status.isOwner(AuthState.SignedOut))
        // Same email, another account: not the owner (only the server's answer for this uid counts).
        assertFalse(status.isOwner(AuthState.Google("owner@example.com", "Copy", null, "uid-copy")))

        // Remembered across restarts, so developer mode works offline.
        answer = ApiResult.Offline
        val restarted = status()
        restarted.refresh()
        assertTrue(restarted.isOwner(owner))

        // Another account on this phone hears "no": the owner's confirmation stays.
        auth = rider
        answer = ApiResult.Ok(ConfigCheckResponse(isOwner = false))
        restarted.refresh()
        assertTrue(restarted.isOwner(owner))
        assertFalse(restarted.isOwner(rider))

        // The server stops naming the owner: withdrawn at once.
        auth = owner
        restarted.refresh()
        assertFalse(restarted.isOwner(owner))
        assertNull(restarted.confirmedOwnerUid.value)
    }

    @Test
    fun `no call without a Google sign-in, and an account switch during the call records nothing`() = runTest {
        auth = AuthState.Anonymous("anon")
        answer = ApiResult.Ok(ConfigCheckResponse(isOwner = true))
        val status = status()
        status.refresh()
        assertEquals(0, checks)
        assertNull(status.confirmedOwnerUid.value)

        auth = owner
        val switching = ServerOwnerStatus(kv, { auth }) {
            auth = rider
            ApiResult.Ok(ConfigCheckResponse(isOwner = true))
        }
        switching.refresh()
        assertNull("the answer belonged to a different sign-in", switching.confirmedOwnerUid.value)
    }

    @Test
    fun `erase forgets the confirmation`() = runTest {
        auth = owner
        answer = ApiResult.Ok(ConfigCheckResponse(isOwner = true))
        val status = status()
        status.refresh()
        status.clear()
        assertFalse(status.isOwner(owner))
        assertTrue(kv.snapshot().isEmpty())
    }
}
