package app.ridemate.compare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.auth.AuthState
import app.ridemate.bubble.BubbleState
import app.ridemate.bubble.BubbleStatus
import app.ridemate.bubble.BubbleSurface
import app.ridemate.bubble.StatusKind
import app.ridemate.capture.CaptureOutcome
import app.ridemate.capture.ConsentResult
import app.ridemate.capture.FrameOrigin
import app.ridemate.capture.FrameTiming
import app.ridemate.core.gate.DriverReading
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.Box
import app.ridemate.data.RideMateDb
import app.ridemate.net.ApiResult
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import app.ridemate.testing.TestSeams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

class RecordingBubble(override var isShowing: Boolean = true) : BubbleSurface {
    val statuses = mutableListOf<Pair<BubbleStatus?, Boolean>>()
    val pointers = mutableListOf<Pair<Box, String>>()
    var pointerAccepted = true
    var hidden = 0
    var restored = 0

    override fun show(state: BubbleState): Boolean = true
    override fun update(state: BubbleState) = Unit
    override fun setReaderStatus(status: BubbleStatus?, busy: Boolean) {
        statuses += status to busy
    }
    override fun hide() = Unit
    override fun pulse(announcement: String?) = Unit
    override fun showPointer(target: Box, description: String): Boolean {
        if (pointerAccepted) pointers += target to description
        return pointerAccepted
    }
    override fun hidePointer() = Unit
    override suspend fun hideForCapture() {
        hidden++
    }
    override fun restoreAfterCapture() {
        restored++
    }
}

@RunWith(AndroidJUnit4::class)
class FareReadingCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RideMateDb
    private val clock = FakeClock()
    private val api = ScriptedApiClient().apply { readScreenResult = { readOk(perfectRead(FareFrames.STANDARD)) } }
    private var auth: AuthState = AuthState.Google("rider@example.com", "Rider", null, "g-uid")
    private val bubble = RecordingBubble()
    private val opened = mutableListOf<Pair<ActiveLeg, ReadFaresResult.ManualMode>>()
    private var consentAnswer: suspend () -> ConsentResult = { ConsentResult.Granted(0) }
    private var consentAsks = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val frames = FakeFrameSource { tap ->
        clock.advance(650)
        CaptureOutcome.Frames(frameAt(tap + 200), frameAt(tap + 600))
    }
    private lateinit var coordinator: FareReadingCoordinator
    private val ocr = FakeOcrEngine { FareFrames.STANDARD }
    private val errors = mutableListOf<Throwable>()

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(context)
        runBlocking {
            db.sessionDao().insert(TestData.session("s1"))
            db.legDao().upsert(TestData.leg("l1", "s1", Provider.UBER, RideState.OPENED))
        }
        val useCase = ReadFaresUseCase(
            frames = frames, ocr = ocr, masker = FakeMasker(), api = api, auth = { auth },
            config = FakeFareReadingConfig(), store = RoomQuoteStore(db, clock), clock = clock,
            installedVersions = InstalledVersions { 1L }, cpu = Dispatchers.Default,
        )
        coordinator = FareReadingCoordinator(
            context = context,
            readFares = useCase,
            consent = { consentAsks++; consentAnswer() },
            // The fixture session is Barpeta Road → Guwahati airport; a city trip keeps the standard screen in band.
            activeComparison = { ActiveComparison { RoomActiveComparison(db).current()?.copy(tripKm = FareConfigs.TRIP_KM) } },
            bubble = { bubble },
            clock = clock,
            scope = scope,
            main = Dispatchers.Unconfined,
            openManualEntry = { leg, manual -> opened += leg to manual },
            onError = { errors += it },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun lastStatus(): BubbleStatus = bubble.statuses.last().first!!

    @Test
    fun `with no comparison running the bubble says how to start one`() = runBlocking {
        db.legDao().upsert(TestData.leg("l1", "s1", Provider.UBER, RideState.SEARCHING))

        assertEquals(ReadFaresResult.NoActiveComparison, coordinator.readFares("t1", clock.elapsed))

        assertEquals(context.getString(R.string.compare_no_comparison), lastStatus().text)
        assertEquals(0, consentAsks)
    }

    @Test
    fun `manual mode opens the price form without asking to share the screen`() = runBlocking {
        auth = AuthState.SignedOut

        val result = coordinator.readFares("t1", clock.elapsed) as ReadFaresResult.ManualMode

        assertEquals(ManualReason.SIGNED_OUT, result.reason)
        assertEquals(0, consentAsks)
        assertTrue(frames.calls.isEmpty())
        assertEquals("l1", opened.single().first.legId)
        assertEquals(StatusKind.PROBLEM, lastStatus().kind)
        assertEquals(context.getString(R.string.platform_sign_in_needed), lastStatus().text)
    }

    @Test
    fun `a refused consent captures nothing and says what to do`() = runBlocking {
        consentAnswer = { ConsentResult.Denied }

        assertEquals(ReadFaresResult.NeedsConsent, coordinator.readFares("t1", clock.elapsed))

        assertTrue(frames.calls.isEmpty())
        assertEquals(context.getString(R.string.compare_consent_needed), lastStatus().text)
        assertEquals(false, bubble.statuses.last().second)
    }

    @Test
    fun `a fresh consent counts as the tap and the read result reaches the bubble`() = runBlocking {
        val tap = clock.elapsed
        consentAnswer = {
            clock.advance(3_000) // the user read Android's dialog
            ConsentResult.Granted(clock.elapsed)
        }

        val result = coordinator.readFares("t1", tap) as ReadFaresResult.Read

        assertEquals(3, result.verifiedCount)
        assertEquals("the read's tap is the consent tap", tap + 3_000, frames.calls.single())
        assertEquals(true, bubble.statuses.first().second)
        assertEquals(StatusKind.WORKING, bubble.statuses.first().first!!.kind)
        assertEquals("3 prices checked in Uber.", lastStatus().text)
        assertEquals(StatusKind.INFO, lastStatus().kind)
        assertEquals(result, coordinator.lastRead.value!!.result)
    }

    @Test
    fun `a running projection reads straight away from the original tap`() = runBlocking {
        val tap = clock.elapsed
        consentAnswer = { ConsentResult.Granted(tap - 10_000, fresh = false) }

        coordinator.readFares("t1", tap)

        assertEquals(tap, frames.calls.single())
    }

    @Test
    fun `I6 - a reused projection never moves the tap, even when time passed before the read started`() = runBlocking {
        val tap = clock.elapsed
        consentAnswer = {
            clock.advance(40) // main-thread hops between the tap and the read
            ConsentResult.Granted(clock.elapsed, fresh = false)
        }

        coordinator.readFares("t1", tap)

        assertEquals("the user's Read fares tap stays the I6 tap", tap, frames.calls.single())
    }

    @Test
    fun `I6 - after a fresh consent the frames count from the Start now tap itself`() = runBlocking {
        val tap = clock.elapsed
        consentAnswer = {
            clock.advance(3_000)
            ConsentResult.Granted(clock.elapsed - 120) // Start now was tapped 120 ms before the answer arrived
        }

        coordinator.readFares("t1", tap)

        assertEquals(tap + 2_880, frames.calls.single())
    }

    @Test
    fun `without a bubble on screen the outcome is shown as a toast, so a notification tap never ends silently`() = runBlocking {
        bubble.isShowing = false

        val result = coordinator.readFares("t1", clock.elapsed) as ReadFaresResult.Read

        assertEquals(3, result.verifiedCount)
        assertEquals("3 prices checked in Uber.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertTrue("nothing goes to a bubble that isn't shown", bubble.statuses.none { it.second == false })
    }

    @Test
    fun `an unexpected failure never leaves the bubble spinning and the next tap works`() = runBlocking {
        ocr.forFrame = { throw IllegalStateException("storage") }

        assertEquals(ReadFaresResult.CaptureFailed, coordinator.readFares("t1", clock.elapsed))

        assertEquals(1, errors.size)
        assertEquals(false, bubble.statuses.last().second)
        assertEquals(context.getString(R.string.compare_capture_failed), lastStatus().text)
        assertTrue(frames.handedOut.all { it.isReleased })

        ocr.forFrame = { FareFrames.STANDARD }
        assertTrue(coordinator.readFares("t2", clock.elapsed) is ReadFaresResult.Read)
    }

    @Test
    fun `taps while a read runs are ignored`() = runBlocking {
        val gate = CompletableDeferred<ConsentResult>()
        consentAnswer = { gate.await() }
        val first: Job = launch(Dispatchers.Unconfined) { coordinator.readFares("t1", clock.elapsed) }
        yield()

        assertNull(coordinator.readFares("t2", clock.elapsed))

        gate.complete(ConsentResult.Granted(0))
        first.join()
        assertEquals(1, frames.calls.size)
    }

    @Test
    fun `a shared screenshot is read for the current app and the frame released`() = runBlocking {
        val frame = frameAt(clock.elapsed, FrameOrigin.SHARED)

        val message = coordinator.onSharedScreenshot(frame)

        assertEquals(context.getString(R.string.capture_share_reading), message)
        scope.coroutineContext[Job]!!.children.forEach { it.join() }
        assertTrue(frame.isReleased)
        assertEquals(3, (coordinator.lastRead.value!!.result as ReadFaresResult.Read).verifiedCount)
    }
}

@RunWith(AndroidJUnit4::class)
class FareReadingMessagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun row(status: QuoteStatus) = GatedRow(
        Provider.OLA, VehicleClass.AUTO, "Auto", true, if (status == QuoteStatus.UNREADABLE) null else 100..100, status,
        null, EtaSource.NONE, 0,
    )

    @Test
    fun `every outcome has wording, and problems say what to do next`() {
        val outcomes = listOf(
            ReadFaresResult.NotFareScreen(Provider.OLA),
            ReadFaresResult.CaptureBlocked(Provider.OLA),
            ReadFaresResult.NeedsConsent,
            ReadFaresResult.CaptureFailed,
            ReadFaresResult.TooSlow(FrameTiming.Verdict.TAKEN_TOO_LATE),
            ReadFaresResult.TimedOut,
            ReadFaresResult.NoActiveComparison,
            ReadFaresResult.Read(Provider.OLA, emptyList(), DriverReading.NONE, false, false),
            ReadFaresResult.Read(Provider.OLA, listOf(row(QuoteStatus.UNREADABLE)), DriverReading.NONE, false, true),
        )
        for (outcome in outcomes) {
            val status = FareReadingMessages.status(context, outcome)
            assertEquals(StatusKind.PROBLEM, status.kind)
            assertTrue("$outcome: ${status.text}", Regex("(?i)tap|type|share|open|start|check").containsMatchIn(status.text))
        }
        assertTrue(FareReadingMessages.status(context, ReadFaresResult.CaptureBlocked(Provider.OLA)).text.contains("share it to RideMate"))
    }

    @Test
    fun `read summaries count checked and unreadable prices`() {
        val mixed = ReadFaresResult.Read(
            Provider.OLA, listOf(row(QuoteStatus.VERIFIED), row(QuoteStatus.UNREADABLE)), DriverReading.NONE, true, true,
        )
        assertEquals("1 price checked in Ola. 1 couldn't be read: check it in the app.", FareReadingMessages.status(context, mixed).text)
    }

    @Test
    fun `manual mode wording comes from the server answer or the reason`() {
        val paused = ReadFaresResult.ManualMode(Provider.UBER, ManualReason.AI_PAUSED, "21:05", ApiResult.AiPaused("ai_paused", "21:05", null))
        assertTrue(FareReadingMessages.manualText(context, paused).startsWith("AI reading resumes at 9:05 pm (IST)"))
        val offline = ReadFaresResult.ManualMode(Provider.UBER, ManualReason.OFFLINE, apiResult = ApiResult.Offline)
        assertEquals(context.getString(R.string.platform_offline), FareReadingMessages.manualText(context, offline))
        val off = ReadFaresResult.ManualMode(Provider.UBER, ManualReason.KILL_SWITCH)
        assertEquals(context.getString(R.string.compare_manual_switched_off), FareReadingMessages.manualText(context, off))
    }

    @Test
    fun `the price form only takes up a manual-mode tap of the comparison it shows`() {
        val manual = ReadFaresResult.ManualMode(Provider.OLA, ManualReason.OFFLINE, apiResult = ApiResult.Offline)
        val tap = LastRead(ActiveLeg("s1", "l2", Provider.OLA, null), manual, 0)

        val same = manualPromptFor(context, tap, null, "s1")!!
        assertEquals(Provider.OLA, same.provider)
        assertEquals(context.getString(R.string.platform_offline), same.message)
        assertNull("an earlier comparison's tap never prompts a new one", manualPromptFor(context, tap, null, "s2"))
        assertEquals(ManualPrompt(null, Provider.UBER), manualPromptFor(context, tap, Provider.UBER, "s2"))
    }

    @Test
    fun `demo providers are labelled DEMO and every gate text key has a string`() {
        assertEquals("Kestrel Go (DEMO)", FareReadingMessages.name(context, Provider.DEMO_KESTREL))
        val keys = listOf("quote_unreadable", "eta_see_in_app", "driver_see_in_app", "quote_entered_by_you", "quote_network",
            "quote_seen_at", "quote_stale", "quote_cheapest")
        for (key in keys) assertTrue(context.getString(FareReadingMessages.gateTextRes(key)).isNotBlank())
        VehicleClass.entries.forEach { assertTrue(context.getString(FareReadingMessages.vehicleClassRes(it)).isNotBlank()) }
    }
}

@RunWith(AndroidJUnit4::class)
class RoomActiveComparisonTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the app on screen is the most recently opened readable leg of the active session`() = runBlocking {
        val db = TestSeams.inMemoryDb(context)
        try {
            val active = RoomActiveComparison(db)
            assertNull(active.current())
            db.sessionDao().insert(TestData.session("s1"))
            db.legDao().upsert(TestData.leg("l1", "s1", Provider.UBER, RideState.QUOTED).copy(lastEventAt = 1_000))
            db.legDao().upsert(TestData.leg("l2", "s1", Provider.OLA, RideState.OPENED).copy(lastEventAt = 2_000))
            db.legDao().upsert(TestData.leg("l3", "s1", Provider.RAPIDO, RideState.BOOK_REQUESTED).copy(lastEventAt = 3_000))
            db.legDao().upsert(TestData.leg("l4", "s1", Provider.ONDC, RideState.SELECTED).copy(lastEventAt = 4_000))

            val leg = active.current()!!
            assertEquals("l2", leg.legId)
            assertEquals(Provider.OLA, leg.provider)
            // Barpeta Road → Guwahati airport is about 63 km in a straight line.
            assertTrue(leg.tripKm!! in 55.0..70.0)
        } finally {
            db.close()
        }
    }
}
