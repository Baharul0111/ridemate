package app.ridemate.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val log = mutableListOf<String>()

    private inner class FakeSession : ProjectionSession {
        val grabs = mutableListOf<Long>()
        var stopped = false

        override suspend fun grab(notBeforeElapsedMs: Long, timeoutMs: Long): FrameImage? {
            if (stopped) return null
            log += "grab"
            grabs += currentTimeHint()
            return FrameImage(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), clock.elapsed)
        }

        override fun stop() {
            stopped = true
            log += "session.stop"
        }
    }

    private var timeSource: () -> Long = { 0 }
    private fun currentTimeHint(): Long = timeSource()

    private inner class FakeGateway : ProjectionGateway {
        var onStop: (() -> Unit)? = null
        val sessions = mutableListOf<FakeSession>()
        var refuse = false

        override fun consentIntent(): Intent = Intent("android.media.projection.CONSENT")

        override fun start(resultCode: Int, data: Intent, onStop: () -> Unit): ProjectionSession? {
            log += "gateway.start"
            if (refuse) return null
            this.onStop = onStop
            return FakeSession().also { sessions += it }
        }
    }

    private inner class FakeHost(var accept: Boolean = true) : CaptureForegroundHost {
        override fun enterMediaProjectionForeground(): Boolean {
            log += "fgs.enter"
            return accept
        }

        override fun exitMediaProjectionForeground() {
            log += "fgs.exit"
        }
    }

    private inner class FakeOverlays : CaptureOverlayControl {
        override suspend fun hideForCapture() {
            log += "overlay.hide"
        }

        override fun restoreAfterCapture() {
            log += "overlay.show"
        }
    }

    private val gateway = FakeGateway()
    private val launched = mutableListOf<Intent>()
    private var launchWorks = true
    private val manager = CaptureManager(
        context = context,
        gateway = gateway,
        clock = clock,
        overlays = { FakeOverlays() },
        mainDispatcher = Dispatchers.Unconfined,
        launchConsentScreen = { intent -> launched += intent; launchWorks },
    ).apply { foregroundHost = FakeHost() }

    private val okData = Intent("consent-token")

    @Test
    fun `consent shows the transparent activity, then the service gains mediaProjection before the projection starts`() = runTest {
        val answer = async { manager.obtainConsent(CapturePurpose.FARE_READING) }
        yield()
        assertEquals(CaptureState.AwaitingConsent(CapturePurpose.FARE_READING), manager.state.value)
        assertEquals(CaptureConsentActivity::class.java.name, launched.single().component!!.className)

        clock.advance(2_500)
        manager.onConsentResult(Activity.RESULT_OK, okData)

        assertEquals(ConsentResult.Granted(clock.elapsed), answer.await())
        assertEquals(listOf("fgs.enter", "gateway.start"), log)
        assertTrue(manager.isActive)
        assertEquals(CapturePurpose.FARE_READING, (manager.state.value as CaptureState.Active).purpose)
    }

    @Test
    fun `one consent per comparison - a running fare projection is reused without asking`() = runTest {
        val first = async { manager.obtainConsent(CapturePurpose.FARE_READING) }
        yield()
        manager.onConsentResult(Activity.RESULT_OK, okData)
        first.await()

        assertTrue(manager.obtainConsent(CapturePurpose.FARE_READING) is ConsentResult.Granted)
        assertEquals(1, launched.size)
        assertEquals(1, gateway.sessions.size)
    }

    @Test
    fun `a refused or abandoned consent starts nothing`() = runTest {
        val denied = async { manager.obtainConsent(CapturePurpose.FARE_READING) }
        yield()
        manager.onConsentResult(Activity.RESULT_CANCELED, null)
        assertEquals(ConsentResult.Denied, denied.await())

        val silent = manager.obtainConsent(CapturePurpose.FARE_READING, timeoutMs = 1_000)
        assertEquals(ConsentResult.NoAnswer, silent)

        assertTrue(log.isEmpty())
        assertEquals(CaptureState.Idle, manager.state.value)
    }

    @Test
    fun `a stale consent result with nobody waiting never starts a projection`() {
        manager.onConsentResult(Activity.RESULT_OK, okData)
        assertTrue(log.isEmpty())
        assertFalse(manager.isActive)
    }

    @Test
    fun `without the session service the projection can't start`() = runTest {
        manager.foregroundHost = FakeHost(accept = false)
        val answer = async { manager.obtainConsent(CapturePurpose.FARE_READING) }
        yield()
        manager.onConsentResult(Activity.RESULT_OK, okData)

        assertEquals(ConsentResult.Failed, answer.await())
        assertEquals(listOf("fgs.enter"), log)
        assertFalse(manager.isActive)
    }

    @Test
    fun `if Android refuses the projection the foreground type is dropped again`() = runTest {
        gateway.refuse = true
        val answer = async { manager.obtainConsent(CapturePurpose.FARE_READING) }
        yield()
        manager.onConsentResult(Activity.RESULT_OK, okData)

        assertEquals(ConsentResult.Failed, answer.await())
        assertEquals(listOf("fgs.enter", "gateway.start", "fgs.exit"), log)
    }

    @Test
    fun `a launch failure is reported at once`() = runTest {
        launchWorks = false
        assertEquals(ConsentResult.Failed, manager.obtainConsent(CapturePurpose.FARE_READING))
        assertEquals(CaptureState.Idle, manager.state.value)
    }

    private suspend fun startFareProjection() {
        manager.obtainConsentAndGrant()
        log.clear()
    }

    private suspend fun CaptureManager.obtainConsentAndGrant() = kotlinx.coroutines.coroutineScope {
        val answer = async { obtainConsent(CapturePurpose.FARE_READING) }
        yield()
        onConsentResult(Activity.RESULT_OK, okData)
        answer.await()
    }

    @Test
    fun `a tap hides the bubble, grabs two frames 400 ms apart and shows the bubble again`() = runTest {
        timeSource = { testScheduler.currentTime }
        startFareProjection()

        val outcome = manager.captureTwoFrames(clock.elapsed) as CaptureOutcome.Frames

        assertEquals(listOf("overlay.hide", "grab", "grab", "overlay.show"), log)
        val session = gateway.sessions.single()
        assertEquals(CaptureManager.FRAME_GAP_MS, session.grabs[1] - session.grabs[0])
        assertTrue(manager.isActive)
        outcome.close()
        assertTrue(outcome.first.isReleased && outcome.second.isReleased)
    }

    @Test
    fun `no projection means no frames`() = runTest {
        assertSame(CaptureOutcome.NotActive, manager.captureTwoFrames(clock.elapsed))
        assertTrue(log.isEmpty())
    }

    @Test
    fun `stopping ends the projection and drops the foreground type`() = runTest {
        startFareProjection()

        manager.stop(StopReason.FARE_READING_DONE)

        assertEquals(listOf("session.stop", "fgs.exit"), log)
        assertEquals(CaptureState.Idle, manager.state.value)
        assertEquals(StopReason.FARE_READING_DONE, manager.lastStop.value)
        manager.stop(StopReason.SESSION_ENDED) // idempotent
        assertEquals(2, log.size)
        assertSame(CaptureOutcome.NotActive, manager.captureTwoFrames(clock.elapsed))
    }

    @Test
    fun `when Android or the user stops sharing, the manager lets go and needs consent again`() = runTest {
        startFareProjection()

        gateway.onStop!!.invoke()

        assertEquals(CaptureState.Idle, manager.state.value)
        assertEquals(StopReason.STOPPED_BY_SYSTEM, manager.lastStop.value)
        assertTrue("fgs.exit" in log)
        manager.obtainConsent(CapturePurpose.FARE_READING, timeoutMs = 10)
        assertEquals(2, launched.size)
    }

    @Test
    fun `an optional later capture asks again and stops right after its one frame`() = runTest {
        startFareProjection()
        manager.stop(StopReason.FARE_READING_DONE)
        log.clear()

        val answer = async { manager.obtainConsent(CapturePurpose.SHOW_ME) }
        yield()
        manager.onConsentResult(Activity.RESULT_OK, okData)
        assertTrue(answer.await() is ConsentResult.Granted)
        val frame = manager.captureOnceAndStop(clock.elapsed)

        assertTrue(frame != null)
        assertEquals(listOf("fgs.enter", "gateway.start", "overlay.hide", "grab", "overlay.show", "session.stop", "fgs.exit"), log)
        assertFalse(manager.isActive)
        assertEquals(StopReason.ONE_SHOT_DONE, manager.lastStop.value)
        frame!!.close()
        assertNull(manager.captureOnceAndStop(clock.elapsed))
    }
}
