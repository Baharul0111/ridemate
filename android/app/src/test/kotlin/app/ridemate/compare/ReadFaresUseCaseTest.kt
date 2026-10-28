package app.ridemate.compare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthState
import app.ridemate.capture.CaptureOutcome
import app.ridemate.capture.FrameOrigin
import app.ridemate.capture.FrameTiming
import app.ridemate.core.gate.RereadRow
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import app.ridemate.data.RideMateDb
import app.ridemate.net.ApiResult
import app.ridemate.net.RereadResponse
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import app.ridemate.testing.TestSeams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReadFaresUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RideMateDb
    private val clock = FakeClock()
    private val api = ScriptedApiClient()
    private val masker = FakeMasker()
    private val config = FakeFareReadingConfig()
    private var auth: AuthState = AuthState.Google("rider@example.com", "Rider", null, "g-uid")
    private var projectionStops = 0
    private val ocr = FakeOcrEngine { FareFrames.STANDARD }
    private lateinit var frames: FakeFrameSource

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(context)
        runBlocking {
            db.sessionDao().insert(TestData.session("s1"))
            db.legDao().upsert(leg("l1", Provider.UBER))
            db.legDao().upsert(leg("l2", Provider.OLA))
        }
        frames = FakeFrameSource { tap ->
            clock.advance(650) // hiding the bubble + two grabs 400 ms apart
            CaptureOutcome.Frames(frameAt(tap + 200), frameAt(tap + 600))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun leg(id: String, provider: Provider, state: RideState = RideState.OPENED) =
        TestData.leg(id = id, sessionId = "s1", provider = provider, state = state).copy(dismissed = false)

    private fun useCase(cpu: CoroutineDispatcher = Dispatchers.Default) = ReadFaresUseCase(
        frames = frames,
        ocr = ocr,
        masker = masker,
        api = api,
        auth = { auth },
        config = config,
        store = RoomQuoteStore(db, clock),
        clock = clock,
        installedVersions = InstalledVersions { 1L },
        onAllFaresRead = { projectionStops++ },
        cpu = cpu,
    )

    private fun request(provider: Provider = Provider.UBER, legId: String = "l1") =
        ReadFaresRequest("s1", legId, provider, "tap-1", clock.elapsed, FareConfigs.TRIP_KM)

    private suspend fun quotes(legId: String) = db.quoteDao().observeForLeg(legId).first()
    private suspend fun legState(legId: String): RideState? = db.legDao().get(legId)?.state

    private fun assertFramesReleased() = frames.handedOut.forEach { assertTrue("frame recycled", it.isReleased) }

    @Test
    fun `two agreeing readers give VERIFIED rows saved in Room and the leg QUOTED`() = runBlocking {
        api.readScreenResult = { readOk(perfectRead(FareFrames.STANDARD)) }

        val result = useCase().read(request()) as ReadFaresResult.Read

        assertEquals(3, result.verifiedCount)
        assertEquals(0, result.unreadableCount)
        assertFalse(result.rereadUsed)
        assertTrue(result.saved)
        val stored = quotes("l1")
        assertEquals(setOf(45..45, 110..110, 210..210), stored.map { it.priceMin!!..it.priceMax!! }.toSet())
        assertTrue(stored.all { it.status == QuoteStatus.VERIFIED && it.sourceFrameHash == "f".repeat(64) })
        assertEquals(RideState.QUOTED, legState("l1"))
        // One masked upload of frame 2 for this session, closed afterwards; frames recycled.
        assertEquals(1, api.readRequests.size)
        assertEquals("s1", api.readRequests.single().sessionId)
        assertEquals(1, masker.closed.size)
        assertFramesReleased()
        // The tap budget is 20 s from the tap.
        assertEquals(frames.calls.single() + 20_000, api.budgets.single().deadlineElapsedMs)
        // Ola still waits for a read, so the projection keeps running.
        assertEquals(0, projectionStops)
    }

    @Test
    fun `the projection stops right after the last pending app is read`() = runBlocking {
        db.legDao().upsert(leg("l2", Provider.OLA, RideState.QUOTED))
        api.readScreenResult = { readOk(perfectRead(FareFrames.STANDARD)) }

        useCase().read(request())

        assertEquals(1, projectionStops)
    }

    @Test
    fun `a disagreeing row is re-read once and stays unreadable with no number when still wrong`() = runBlocking {
        api.readScreenResult = {
            readOk(perfectRead(FareFrames.STANDARD) { i, o -> if (i == 1) o.copy(priceMin = 120, priceMax = 120) else o })
        }
        api.rereadResult = {
            ApiResult.Ok(RereadResponse(listOf(rereadRow("r1", "auto", "Auto", 120)), "live", "rr-1"))
        }

        val result = useCase().read(request()) as ReadFaresResult.Read

        assertTrue(result.rereadUsed)
        assertEquals(1, api.rereadRequests.size)
        assertEquals(listOf("r1"), api.rereadRequests.single().crops.map { it.rowId })
        assertEquals(2, result.verifiedCount)
        val auto = result.rows.single { it.vehicleClass == VehicleClass.AUTO }
        assertEquals(QuoteStatus.UNREADABLE, auto.status)
        assertNull(auto.price)
        assertNull(auto.eta)
        val stored = quotes("l1").single { it.vehicleClass == VehicleClass.AUTO }
        assertEquals(QuoteStatus.UNREADABLE, stored.status)
        assertNull(stored.priceMin)
        assertNull(stored.priceMax)
    }

    @Test
    fun `a re-read that agrees with the phone's reader verifies the row`() = runBlocking {
        api.readScreenResult = {
            readOk(perfectRead(FareFrames.STANDARD) { i, o -> if (i == 1) o.copy(priceMin = 120, priceMax = 120) else o })
        }
        api.rereadResult = { ApiResult.Ok(RereadResponse(listOf(rereadRow("r1", "auto", "Auto", 110, eta = 3)), "live", "rr-1")) }

        val result = useCase().read(request()) as ReadFaresResult.Read

        assertEquals(3, result.verifiedCount)
        assertEquals(110..110, result.rows.single { it.vehicleClass == VehicleClass.AUTO }.price)
    }

    @Test
    fun `a failed re-read leaves the disagreeing row unreadable`() = runBlocking {
        api.readScreenResult = {
            readOk(perfectRead(FareFrames.STANDARD) { i, o -> if (i == 1) o.copy(priceMin = 120, priceMax = 120) else o })
        }
        api.rereadResult = { ApiResult.Timeout }

        val result = useCase().read(request()) as ReadFaresResult.Read

        assertFalse(result.rereadUsed)
        assertEquals(QuoteStatus.UNREADABLE, result.rows.single { it.vehicleClass == VehicleClass.AUTO }.status)
    }

    @Test
    fun `AI paused gives manual mode with the resume time and nothing saved`() = runBlocking {
        api.readScreenResult = { ApiResult.AiPaused("ai_paused", "13:30", "day_cap") }

        val result = useCase().read(request()) as ReadFaresResult.ManualMode

        assertEquals(ManualReason.AI_PAUSED, result.reason)
        assertEquals("13:30", result.resumeAtIst)
        assertEquals(listOf(VehicleClass.BIKE, VehicleClass.AUTO, VehicleClass.CAB_ECONOMY), result.suggestedClasses)
        assertTrue(FareReadingMessages.manualText(context, result).contains("AI reading resumes at 1:30 pm (IST)"))
        assertTrue(quotes("l1").isEmpty())
        assertEquals(RideState.OPENED, legState("l1"))
        assertFramesReleased()
    }

    @Test
    fun `every AI error means manual mode`() = runBlocking {
        val cases = mapOf(
            ApiResult.Timeout to ManualReason.TIMEOUT,
            ApiResult.Offline to ManualReason.OFFLINE,
            ApiResult.AuthNeeded to ManualReason.AUTH_NEEDED,
            ApiResult.GoogleRequired to ManualReason.GOOGLE_REQUIRED,
            ApiResult.RateLimited(30) to ManualReason.RATE_LIMITED,
            ApiResult.NotConfigured to ManualReason.NOT_CONFIGURED,
            ApiResult.Http(503, "ai_unavailable") to ManualReason.SERVER_ERROR,
            ApiResult.AiPaused("quota_exhausted", null, "quota") to ManualReason.AI_PAUSED,
        )
        for ((apiResult, reason) in cases) {
            api.readScreenResult = { apiResult }
            val result = useCase().read(request(Provider.UBER)) as ReadFaresResult.ManualMode
            assertEquals(reason, result.reason)
            assertEquals(apiResult, result.apiResult)
        }
    }

    @Test
    fun `signed out, anonymous on a live app and the kill switch skip capture entirely`() = runBlocking {
        auth = AuthState.SignedOut
        assertEquals(ManualReason.SIGNED_OUT, (useCase().read(request()) as ReadFaresResult.ManualMode).reason)
        auth = AuthState.Anonymous("anon")
        assertEquals(ManualReason.ANONYMOUS, (useCase().read(request()) as ReadFaresResult.ManualMode).reason)
        auth = AuthState.Google(null, null, null, "g")
        config.aiEnabled = false
        assertEquals(ManualReason.KILL_SWITCH, (useCase().read(request()) as ReadFaresResult.ManualMode).reason)
        config.aiEnabled = true
        config.parsers = null
        assertEquals(ManualReason.READER_UNAVAILABLE, (useCase().read(request()) as ReadFaresResult.ManualMode).reason)

        assertTrue(frames.calls.isEmpty())
        assertEquals(0, ocr.calls)
        assertTrue(api.readRequests.isEmpty())
    }

    @Test
    fun `anonymous users can read demo screens`() = runBlocking {
        db.legDao().upsert(leg("d1", Provider.DEMO_KESTREL))
        auth = AuthState.Anonymous("anon")
        api.readScreenResult = { readOk(perfectRead(FareFrames.STANDARD, Provider.DEMO_KESTREL)) }

        val result = useCase().read(request(Provider.DEMO_KESTREL, "d1")) as ReadFaresResult.Read

        assertEquals(3, result.verifiedCount)
        assertTrue(api.readRequests.single().demo)
    }

    @Test
    fun `K3 rejects a screen without prices or status words and uploads nothing`() = runBlocking {
        ocr.forFrame = { FareFrames.HOME_SCREEN }

        val result = useCase().read(request())

        assertEquals(ReadFaresResult.NotFareScreen(Provider.UBER), result)
        assertEquals(0, masker.uploads)
        assertTrue(api.readRequests.isEmpty())
        assertFramesReleased()
    }

    @Test
    fun `the tap never hangs - OCR that never returns ends at the 20 s deadline`() = runTest {
        ocr.hang = true
        val started = currentTime

        val result = useCase(StandardTestDispatcher(testScheduler)).read(request())

        assertEquals(ReadFaresResult.TimedOut, result)
        assertEquals(20_000, currentTime - started)
        assertFramesReleased()
    }

    @Test
    fun `a server call that ignores its budget still ends at the deadline`() = runTest {
        api.readScreenResult = { awaitCancellation() }

        val result = useCase(StandardTestDispatcher(testScheduler)).read(request())

        assertEquals(ReadFaresResult.TimedOut, result)
        assertEquals(1, masker.closed.size)
        assertFramesReleased()
    }

    @Test
    fun `I6 - frames taken more than 2 s after the tap are discarded unprocessed`() = runBlocking {
        frames.next = { tap ->
            clock.advance(2_600)
            CaptureOutcome.Frames(frameAt(tap + 2_100), frameAt(tap + 2_500))
        }

        val result = useCase().read(request())

        assertEquals(ReadFaresResult.TooSlow(FrameTiming.Verdict.TAKEN_TOO_LATE), result)
        assertEquals(0, ocr.calls)
        assertFramesReleased()
    }

    @Test
    fun `I6 - processing that would start after 2 s is refused`() = runBlocking {
        frames.next = { tap ->
            clock.advance(2_100)
            CaptureOutcome.Frames(frameAt(tap + 200), frameAt(tap + 600))
        }

        assertEquals(ReadFaresResult.TooSlow(FrameTiming.Verdict.PROCESSED_TOO_LATE), useCase().read(request()))
        assertEquals(0, ocr.calls)
    }

    @Test
    fun `a black frame with no text is a secure screen - never bypassed, nothing uploaded`() = runBlocking {
        frames.next = { tap ->
            clock.advance(650)
            CaptureOutcome.Frames(frameAt(tap + 200, suspectedSecure = true), frameAt(tap + 600, suspectedSecure = true))
        }
        // Only the status bar (top 6%) still has text.
        ocr.forFrame = { OcrFrame(1080, 2400, listOf(OcrToken("10:42", Box(40, 20, 120, 50), 0, 0))) }

        assertEquals(ReadFaresResult.CaptureBlocked(Provider.UBER), useCase().read(request()))
        assertEquals(0, masker.uploads)
    }

    @Test
    fun `a blank shared screenshot means screenshots are blocked too - manual mode, never share again`() = runBlocking {
        val blank = frameAt(clock.elapsed, FrameOrigin.SHARED, suspectedSecure = true)
        ocr.forFrame = { OcrFrame(1080, 2400, listOf(OcrToken("10:42", Box(40, 20, 120, 50), 0, 0))) }

        val result = useCase().readShared(request(), blank) as ReadFaresResult.ManualMode

        assertEquals(ManualReason.SCREENSHOT_BLANK, result.reason)
        assertEquals(Provider.UBER, result.provider)
        assertEquals(0, masker.uploads)
        assertTrue(api.readRequests.isEmpty())
        val text = FareReadingMessages.manualText(context, result)
        assertTrue(text, text.contains("blocks screenshots") && !text.contains("share", ignoreCase = true))
        blank.close()
    }

    @Test
    fun `a dark screen with text is read normally`() = runBlocking {
        frames.next = { tap ->
            clock.advance(650)
            CaptureOutcome.Frames(frameAt(tap + 200, suspectedSecure = true), frameAt(tap + 600, suspectedSecure = true))
        }
        api.readScreenResult = { readOk(perfectRead(FareFrames.STANDARD)) }

        assertEquals(3, (useCase().read(request()) as ReadFaresResult.Read).verifiedCount)
    }

    @Test
    fun `malformed model output is never shown - rows stay unreadable`() = runBlocking {
        val garbage = Json.parseToJsonElement("""{"app": "uber", "options": "Auto ₹1"}""").jsonObject
        api.readScreenResult = { ApiResult.Ok(app.ridemate.net.ReadScreenResponse(garbage, "live", null, null, null)) }

        val result = useCase().read(request()) as ReadFaresResult.Read

        assertEquals(0, result.verifiedCount)
        assertTrue(result.rows.all { it.price == null && it.status == QuoteStatus.UNREADABLE })
        assertTrue(api.rereadRequests.isEmpty())
    }

    @Test
    fun `a screen that can't be squeezed under 1 MB is not uploaded`() = runBlocking {
        val tooBig = object : app.ridemate.ocr.FrameMasker by masker {
            override fun maskForUpload(frame: app.ridemate.capture.FrameImage, ocr: OcrFrame) = throw app.ridemate.ocr.ImageTooLargeException()
        }
        val useCase = ReadFaresUseCase(
            frames, ocr, tooBig, api, { auth }, config, RoomQuoteStore(db, clock), clock, InstalledVersions { 1L },
        )

        assertEquals(ReadFaresResult.CaptureFailed, useCase.read(request()))
        assertTrue(api.readRequests.isEmpty())
        assertFramesReleased()
    }

    @Test
    fun `no projection asks for consent and a failed grab says so`() = runBlocking {
        frames.next = { CaptureOutcome.NotActive }
        assertEquals(ReadFaresResult.NeedsConsent, useCase().read(request()))
        frames.next = { CaptureOutcome.Failed }
        assertEquals(ReadFaresResult.CaptureFailed, useCase().read(request()))
    }

    @Test
    fun `a shared screenshot is read as one frame from the moment it arrived`() = runBlocking {
        api.readScreenResult = { readOk(perfectRead(FareFrames.STANDARD)) }
        val shared = frameAt(clock.elapsed, FrameOrigin.SHARED)

        val result = useCase().readShared(request(), shared) as ReadFaresResult.Read

        assertEquals(3, result.verifiedCount)
        assertEquals(1, ocr.calls)
        assertFalse("the caller owns the shared frame", shared.isReleased)
        shared.close()
    }

    private fun rereadRow(id: String, cls: String, label: String, price: Int, eta: Int? = null) =
        Json.parseToJsonElement(
            Json.encodeToString(RereadRow.serializer(), RereadRow(id, VehicleClass.fromWireId(cls), label, price, price, eta)),
        ).jsonObject
}
