package app.ridemate.trip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [awaitDetached]: the caller's time limit holds even when the work ignores cancellation (blocking Geocoder < 33). */
class DetachedTest {

    @Test
    fun `a call stuck in blocking code is abandoned at the caller's time limit`() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            val result = withTimeoutOrNull(200) {
                awaitDetached(Dispatchers.IO) {
                    release.await(10, TimeUnit.SECONDS) // like Geocoder.getFromLocationName below Android 13
                    "late"
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertNull(result)
            assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `results and failures pass through`() = runBlocking {
        assertEquals("ok", awaitDetached(Dispatchers.IO) { "ok" })
        try {
            awaitDetached(Dispatchers.IO) { throw IOException("offline") }
            fail("expected the failure")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test
    fun `work that cancels itself is a failure, never mistaken for the caller's cancellation`() = runBlocking {
        try {
            awaitDetached(Dispatchers.Default) { withTimeout(10) { awaitCancellation() } }
            fail("expected the failure")
        } catch (e: CancellationException) {
            fail("the caller was not cancelled")
        } catch (e: DetachedWorkCancelled) {
            assertTrue(e.cause is CancellationException)
        }
    }
}
