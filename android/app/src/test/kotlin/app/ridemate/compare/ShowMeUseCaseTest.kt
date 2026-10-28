package app.ridemate.compare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.auth.AuthState
import app.ridemate.bubble.StatusKind
import app.ridemate.capture.ConsentResult
import app.ridemate.capture.FrameImage
import app.ridemate.capture.FrameTiming
import app.ridemate.core.model.Provider
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import app.ridemate.net.ApiResult
import app.ridemate.net.PointResponse
import app.ridemate.testing.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShowMeUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val api = ScriptedApiClient().apply {
        pointResult = { ApiResult.Ok(PointResponse(supported = true, box0to1000 = listOf(800, 100, 950, 140))) }
    }
    private val masker = FakeMasker()
    private val bubble = RecordingBubble()
    private val config = FakeFareReadingConfig()
    private var auth: AuthState = AuthState.Google("rider@example.com", "Rider", null, "g-uid")
    private val ocr = FakeOcrEngine { FareFrames.STANDARD }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Android's dialog and the one-frame capture, scripted. */
    private inner class FakeOneShot : OneShotCapture {
        var answer: suspend () -> ConsentResult = {
            clock.advance(2_000) // the user reads Android's dialog
            ConsentResult.Granted(clock.elapsed)
        }
        var frame: (tapAt: Long) -> FrameImage? = { tapAt ->
            clock.advance(300) // the bubble hides, one frame
            frameAt(tapAt + 250)
        }
        var consents = 0
        val captures = mutableListOf<Long>()
        val handedOut = mutableListOf<FrameImage>()
        var abandoned = 0

        override suspend fun consent(): ConsentResult {
            consents++
            return answer()
        }

        override suspend fun captureOnceAndStop(tapAtElapsedMs: Long): FrameImage? {
            captures += tapAtElapsedMs
            return frame(tapAtElapsedMs)?.also { handedOut += it }
        }

        override fun abandon() {
            abandoned++
        }
    }

    private val capture = FakeOneShot()

    private fun useCase(deadlineMs: Long = ShowMeUseCase.DEADLINE_MS) = ShowMeUseCase(
        context = context,
        capture = capture,
        ocr = ocr,
        masker = masker,
        api = api,
        auth = { auth },
        config = config,
        bubble = { bubble },
        screenSize = { ScreenSize(1080, 2400) },
        clock = clock,
        scope = scope,
        main = Dispatchers.Unconfined,
        cpu = Dispatchers.Unconfined,
        deadlineMs = deadlineMs,
        consentSettleMs = 0,
    )

    private fun request() = ShowMeRequest("s1", Provider.UBER, "cancel_button", "tap-1", clock.elapsed)

    private fun lastStatus() = bubble.statuses.last().first!!

    private fun assertLetGo() {
        assertTrue("the one-frame projection never outlives the tap", capture.abandoned >= 1)
        assertTrue("frames recycled", capture.handedOut.all { it.isReleased })
        assertEquals("masked copies closed", masker.uploads, masker.closed.size)
    }

    @Test
    fun `Show me asks again, captures one masked frame and draws the arrow beside the button`() = runBlocking {
        val tap = clock.elapsed

        val result = useCase().show(request())

        // 800–950 × 100–140 on the 0–1000 grid of a 1080 × 2400 screen.
        val target = Box(864, 240, 1026, 336)
        assertEquals(ShowMeResult.Shown(target), result)
        assertEquals(1, capture.consents)
        assertEquals("I6 counts from the Start now tap", listOf(tap + 2_000), capture.captures)
        val (pointed, description) = bubble.pointers.single()
        assertEquals(target, pointed)
        assertEquals(context.getString(R.string.show_me_pointer_description, "Uber"), description)
        // Only the masked JPEG left the phone, with the card's target.
        assertEquals(1, masker.uploads)
        val sent = api.pointRequests.single()
        assertEquals("cancel_button", sent.target)
        assertEquals("s1", sent.sessionId)
        assertTrue(sent.image.contentEquals(masker.closed.single().jpeg))
        assertLetGo()
        assertEquals(StatusKind.WORKING, bubble.statuses.first().first!!.kind)
        assertEquals(true, bubble.statuses.first().second)
        assertEquals(context.getString(R.string.show_me_shown, "Uber"), lastStatus().text)
        assertEquals(false, bubble.statuses.last().second)
    }

    @Test
    fun `without Google sign-in or with AI switched off nothing is captured or asked`() = runBlocking {
        for (setup in listOf<() -> Unit>(
            { auth = AuthState.SignedOut },
            { auth = AuthState.Anonymous("a-uid") },
            { auth = AuthState.Google("rider@example.com", "Rider", null, "g-uid"); config.aiEnabled = false },
        )) {
            setup()
            assertEquals(ShowMeResult.Unavailable(), useCase().show(request()))
            assertEquals(StatusKind.PROBLEM, lastStatus().kind)
            assertTrue(lastStatus().text.contains("card"))
        }
        assertEquals(0, capture.consents)
        assertTrue(api.pointRequests.isEmpty())
        assertTrue(useCase().isAvailable(Provider.UBER).not())
        assertTrue("ONDC has no screen to point at", useCase().isAvailable(Provider.ONDC).not())
    }

    @Test
    fun `grounding off, nothing found or no room beside it all fall back to the card`() = runBlocking {
        api.pointResult = { ApiResult.Ok(PointResponse(supported = false, box0to1000 = null)) }
        assertEquals(ShowMeResult.Unsupported, useCase().show(request()))
        assertEquals(context.getString(R.string.show_me_unsupported), lastStatus().text)

        api.pointResult = { ApiResult.Ok(PointResponse(supported = true, box0to1000 = null)) }
        assertEquals(ShowMeResult.NotFound, useCase().show(request()))
        api.pointResult = { ApiResult.Ok(PointResponse(supported = true, box0to1000 = listOf(500, 500, 500, 700))) }
        assertEquals("a zero-width box is not a button", ShowMeResult.NotFound, useCase().show(request()))
        assertEquals(context.getString(R.string.show_me_not_found), lastStatus().text)

        api.pointResult = { ApiResult.Ok(PointResponse(supported = true, box0to1000 = listOf(0, 0, 1000, 1000))) }
        bubble.pointerAccepted = false
        assertEquals(ShowMeResult.NoRoom, useCase().show(request()))
        assertEquals(context.getString(R.string.show_me_no_room), lastStatus().text)
        assertTrue(bubble.pointers.isEmpty())
        assertLetGo()
    }

    @Test
    fun `AI paused says when Show me is back, other failures say to follow the card`() = runBlocking {
        api.pointResult = { ApiResult.AiPaused("ai_paused", "21:05", null) }
        assertEquals(ShowMeResult.Unavailable("21:05"), useCase().show(request()))
        assertTrue(lastStatus().text, lastStatus().text.contains("9:05 pm (IST)"))

        api.pointResult = { ApiResult.Offline }
        assertEquals(ShowMeResult.Unavailable(), useCase().show(request()))
        assertEquals(context.getString(R.string.show_me_unavailable), lastStatus().text)
        assertLetGo()
    }

    @Test
    fun `a refused consent captures nothing and says what to do`() = runBlocking {
        capture.answer = { ConsentResult.Denied }

        assertEquals(ShowMeResult.NeedsConsent, useCase().show(request()))

        assertTrue(capture.captures.isEmpty())
        assertEquals(context.getString(R.string.show_me_consent_needed), lastStatus().text)
        capture.answer = { ConsentResult.Failed }
        assertEquals(ShowMeResult.CaptureFailed, useCase().show(request()))
    }

    @Test
    fun `a secure screen is never bypassed and nothing is uploaded`() = runBlocking {
        capture.frame = { tapAt ->
            clock.advance(300)
            frameAt(tapAt + 250, suspectedSecure = true)
        }
        ocr.forFrame = { OcrFrame(1080, 2400, listOf(OcrToken("10:42", Box(40, 20, 120, 50), 0, 0))) }

        assertEquals(ShowMeResult.CaptureBlocked(Provider.UBER), useCase().show(request()))

        assertEquals(0, masker.uploads)
        assertTrue(api.pointRequests.isEmpty())
        assertEquals(context.getString(R.string.show_me_blocked, "Uber"), lastStatus().text)
        assertLetGo()
    }

    @Test
    fun `I6 - a frame more than 2 s after the tap is discarded unprocessed`() = runBlocking {
        capture.frame = { tapAt ->
            clock.advance(2_500)
            frameAt(tapAt + 2_400)
        }

        assertEquals(ShowMeResult.TooSlow(FrameTiming.Verdict.TAKEN_TOO_LATE), useCase().show(request()))

        assertEquals(0, ocr.calls)
        assertEquals(0, masker.uploads)
        assertLetGo()
    }

    @Test
    fun `never hangs - a point call that never answers ends at the deadline and lets the projection go`() = runBlocking {
        api.pointResult = { awaitCancellation() }

        assertEquals(ShowMeResult.TimedOut, useCase(deadlineMs = 300).show(request()))

        assertEquals(context.getString(R.string.show_me_timed_out), lastStatus().text)
        assertEquals(false, bubble.statuses.last().second)
        assertLetGo()
    }

    @Test
    fun `taps while one runs are ignored, and the tap entry point uses the tap time`() = runBlocking {
        val gate = CompletableDeferred<ConsentResult>()
        capture.answer = { gate.await() }
        val showMe = useCase()
        val first: Job = showMe.onShowMeTap("s1", Provider.UBER, "cancel_button", "tap-1")
        yield()

        assertNull(showMe.show(request()))

        gate.complete(ConsentResult.Granted(clock.elapsed, fresh = true))
        first.join()
        assertEquals(1, capture.captures.size)
        assertEquals(1, bubble.pointers.size)
    }

    @Test
    fun `without a bubble on screen the outcome is a toast`() = runBlocking {
        bubble.isShowing = false

        useCase().show(request())

        assertEquals(context.getString(R.string.show_me_shown, "Uber"), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `every outcome has wording that leaves the user a next step`() {
        val outcomes = listOf(
            ShowMeResult.NotFound, ShowMeResult.NoRoom, ShowMeResult.Unsupported, ShowMeResult.Unavailable(),
            ShowMeResult.NeedsConsent, ShowMeResult.CaptureFailed, ShowMeResult.CaptureBlocked(Provider.OLA),
            ShowMeResult.TooSlow(FrameTiming.Verdict.TAKEN_TOO_LATE), ShowMeResult.TimedOut,
        )
        for (outcome in outcomes) {
            val status = ShowMeMessages.status(context, Provider.OLA, outcome)
            assertEquals(StatusKind.PROBLEM, status.kind)
            assertTrue("$outcome: ${status.text}", Regex("(?i)card|tap").containsMatchIn(status.text))
        }
    }
}
