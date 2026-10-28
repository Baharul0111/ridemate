package app.ridemate.update

import app.ridemate.config.LatestVersion
import app.ridemate.util.BuildInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val epoch = MutableStateFlow(0L)
    private val session = MutableStateFlow(false)
    private var latest: LatestVersion? = null
    private var minSupported = 1
    private val sha = "a".repeat(64)

    @After
    fun tearDown() = scope.cancel()

    private fun checker(versionCode: Int = 3) =
        UpdateChecker(BuildInfo(versionCode, "x", debug = false), { latest }, { minSupported }, epoch, session, scope)

    private fun release(code: Int, url: String = "https://github.com/Baharul0111/ridemate/releases/tag/v$code", hash: String = sha) =
        LatestVersion("1.0", code, "1.$code.0", url, hash)

    @Test
    fun `a newer release is offered with its checksum`() {
        latest = release(5)
        val state = checker().check()
        assertTrue(state.available)
        assertEquals(5, state.versionCode)
        assertEquals("1.5.0", state.versionName)
        assertEquals("https://github.com/Baharul0111/ridemate/releases/tag/v5", state.url)
        assertEquals(sha, state.sha256)
        assertFalse(state.forceUpdate)
    }

    @Test
    fun `same or older releases and unsafe links are not offered`() {
        latest = release(3)
        assertFalse(checker().check().available)
        latest = release(2)
        assertFalse(checker().check().available)
        latest = release(9, url = "http://example.com/app.apk")
        assertFalse(checker().check().available)
        latest = release(9, url = "not a url")
        assertFalse(checker().check().available)
        latest = null
        assertEquals(UpdateState(), checker().check())
    }

    @Test
    fun `a malformed checksum is dropped but the update still shows`() {
        latest = release(9, hash = "xyz")
        val state = checker().check()
        assertTrue(state.available)
        assertNull(state.sha256)
        latest = release(9, hash = sha.uppercase())
        assertEquals(sha, checker().check().sha256)
    }

    @Test
    fun `a forced update waits for the active session to end`() = runBlocking {
        minSupported = 4
        session.value = true
        val checker = checker(versionCode = 3)
        assertTrue(checker.state.value.belowMinSupported)
        assertFalse(checker.state.value.forceUpdate)

        session.value = false
        assertTrue(withTimeout(5_000) { checker.state.first { it.forceUpdate } }.belowMinSupported)
    }

    @Test
    fun `config activations recompute the state`() = runBlocking {
        val checker = checker()
        assertFalse(checker.state.value.available)
        latest = release(7)
        epoch.value = 1
        withTimeout(5_000) { checker.state.first { it.available } }
        minSupported = 1
        assertFalse(checker.check().belowMinSupported)
    }

    @Test
    fun `failing config reads are treated as no update`() {
        val checker = UpdateChecker(BuildInfo(3, "x", false), { error("bad") }, { error("bad") }, epoch, session, scope)
        assertEquals(UpdateState(), checker.check())
    }
}
