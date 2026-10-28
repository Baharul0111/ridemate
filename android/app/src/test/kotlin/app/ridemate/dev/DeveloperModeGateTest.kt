package app.ridemate.dev

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthState
import app.ridemate.config.ConfigKeys
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestRideMateApp
import app.ridemate.ui.InMemoryKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeveloperModeGateTest {
    private val ownerEmail = "Owner@Example.com"
    private val owner = AuthState.Google(ownerEmail, "Owner", null, "owner-uid")
    private val stranger = AuthState.Google("someone@example.com", "Someone", null, "s-uid")
    private val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val clock = FakeClock()
    private val kv = InMemoryKeyValueStore()
    private val gate = DeveloperModeGate(kv, auth, { setOf(OwnerEmails.hash("owner@example.com")) }, clock)

    private fun tap(times: Int): VersionTapOutcome {
        var last: VersionTapOutcome? = null
        repeat(times) {
            clock.advance(300)
            last = gate.registerVersionTap()
        }
        return last!!
    }

    @Test
    fun `seven taps by the owner turn developer mode on`() = runBlocking {
        auth.value = owner
        assertEquals(VersionTapOutcome.Counting(1), tap(6))
        assertFalse(gate.isEnabled())
        assertEquals(VersionTapOutcome.Enabled, tap(1))
        assertTrue(gate.isEnabled())
        assertTrue(gate.enabled.first())
        assertTrue("Recorder is on by default once developer mode is on", gate.isRecording())
        assertEquals(VersionTapOutcome.AlreadyEnabled, tap(7))
    }

    @Test
    fun `only the owner, and only while signed in`() = runBlocking {
        assertEquals(VersionTapOutcome.SignInRequired, tap(7))
        auth.value = AuthState.Anonymous("a")
        assertEquals(VersionTapOutcome.SignInRequired, tap(7))
        auth.value = stranger
        assertEquals(VersionTapOutcome.NotOwner, tap(7))
        assertFalse(gate.isEnabled())

        auth.value = owner
        assertEquals(VersionTapOutcome.Enabled, tap(7))
        // Signing out (or into another account) turns it off at once; back as the owner, it is on again.
        auth.value = AuthState.SignedOut
        assertFalse(gate.isEnabled())
        assertFalse(gate.enabled.first())
        assertFalse(gate.isRecording())
        auth.value = stranger
        assertFalse(gate.isEnabled())
        auth.value = owner
        assertTrue(gate.isEnabled())
    }

    @Test
    fun `slow taps start counting again`() {
        auth.value = owner
        tap(5)
        clock.advance(DeveloperModeGate.TAP_GAP_MS + 1)
        assertEquals(VersionTapOutcome.Counting(6), gate.registerVersionTap())
        assertEquals(VersionTapOutcome.Enabled, tap(6))
    }

    @Test
    fun `disable, recorder switch and erase reset`() {
        auth.value = owner
        tap(7)
        gate.setRecorderOn(false)
        assertTrue(gate.isEnabled())
        assertFalse(gate.isRecording())
        gate.setOwnerAlertsOn(true)
        assertTrue(gate.ownerAlertsSwitch.value)
        // Persisted: a new gate over the same store sees the same switches.
        val again = DeveloperModeGate(kv, auth, { setOf(OwnerEmails.hash(ownerEmail)) }, clock)
        assertTrue(again.isEnabled())
        assertFalse(again.recorderSwitch.value)
        gate.disable()
        assertFalse(gate.isEnabled())
        gate.reset()
        assertTrue(gate.recorderSwitch.value)
        assertFalse(gate.ownerAlertsSwitch.value)
        assertTrue(kv.snapshot().isEmpty())
    }

    @Test
    fun `owner hashes come from remote config through core's scalar parser`() {
        val app = ApplicationProvider.getApplicationContext<TestRideMateApp>()
        val config = app.graph.configRepository
        assertEquals(emptySet<String>(), OwnerEmails.hashesFrom(config))
        val hash = OwnerEmails.hash("owner@example.com")
        app.fakes.remoteConfig.active[ConfigKeys.OWNER_EMAILS_SHA256] = """["${hash.uppercase()}"]"""
        assertEquals(setOf(hash), OwnerEmails.hashesFrom(config))
        app.fakes.remoteConfig.active[ConfigKeys.OWNER_EMAILS_SHA256] = """["not-a-hash"]"""
        assertEquals("malformed lists count as empty", emptySet<String>(), OwnerEmails.hashesFrom(config))
        // Lower-cased and trimmed before hashing, as contracts §2.7 says.
        assertEquals(OwnerEmails.hash("owner@example.com"), OwnerEmails.hash("  OWNER@example.COM "))
        assertEquals(64, hash.length)
    }
}
