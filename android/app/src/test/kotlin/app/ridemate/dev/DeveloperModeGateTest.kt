package app.ridemate.dev

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthState
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

    /** The Google uid `/v1/config-check` confirmed as the owner ([ServerOwnerStatus.confirmedOwnerUid]). */
    private val serverOwnerUid = MutableStateFlow<String?>("owner-uid")
    private val isOwnerAccount: (AuthState) -> Boolean = { state -> (state as? AuthState.Google)?.uid == serverOwnerUid.value }
    private val gate = DeveloperModeGate(kv, auth, isOwnerAccount, clock, serverOwnerUid)

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
        val again = DeveloperModeGate(kv, auth, isOwnerAccount, clock)
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
    fun `the server withdrawing the owner turns developer mode off at once`() = runBlocking {
        auth.value = owner
        assertEquals(VersionTapOutcome.Enabled, tap(7))
        assertTrue(gate.enabled.first())
        serverOwnerUid.value = null
        assertFalse(gate.isEnabled())
        assertFalse(gate.enabled.first())
        assertEquals(VersionTapOutcome.NotOwner, tap(7))
    }

    @Test
    fun `the app ships no owner list - the owner comes only from the server`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestRideMateApp>()
        val bundled = app.assets.list("config").orEmpty().toSet()
        assertFalse("owner_emails_sha256.json" in bundled)
        assertFalse("judge_allowlist.json" in bundled)
        assertTrue(app.graph.configRepository.raw("owner_emails_sha256").let { it.remote == null && it.bundled == null })
        // Signed in as the owner, but the server has not confirmed it on this phone: not the owner.
        app.fakes.auth.signInWithGoogleIdToken("token")
        assertFalse(app.graph.ownerAccess.isOwner())
    }
}
