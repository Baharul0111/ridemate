package app.ridemate.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.AppGraph
import app.ridemate.capture.FrameImage
import app.ridemate.config.AssetBundledConfig
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.model.Provider.DEMO_KESTREL
import app.ridemate.core.model.Provider.DEMO_PEPPER
import app.ridemate.core.model.Provider.DEMO_TAMARIND
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.data.SessionStatus
import app.ridemate.demo.BundledAssets
import app.ridemate.demo.DemoAppPurpose
import app.ridemate.demo.DemoFixtures
import app.ridemate.demo.DemoImages
import app.ridemate.demo.DemoPhase
import app.ridemate.demo.DemoScreens
import app.ridemate.demo.DemoStartResult
import app.ridemate.demo.DemoTrackChoice
import app.ridemate.demo.ReadSource
import app.ridemate.demo.answerDemoReads
import app.ridemate.notify.BetterRideItem
import app.ridemate.notify.DriverSheetItem
import app.ridemate.notify.GuardianTap
import app.ridemate.notify.LegUi
import app.ridemate.notify.TapScope
import app.ridemate.ocr.OcrEngine
import app.ridemate.session.GuardianRuntime
import app.ridemate.testing.ProgrammableApiClient
import app.ridemate.testing.TestRideMateApp
import app.ridemate.trip.TripPlace
import app.ridemate.trip.TripPlan
import app.ridemate.trip.VehicleChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Try the demo end to end through the **real app graph** (AppGraph as RideMateApp builds it, with only the platform
 * fakes swapped in): DemoController → bundled screens → the capture area's OcrEngine port (recorded synthetic tokens of
 * the exact pixels) → `/v1/read-screen` with `demo = true` answered by recorded Channel A reads → the core gate → the
 * demo price list → scripted notifications into the guardian runtime's RideWatch pipeline → "Cancel the others?"
 * answered through the guardian's own sheet button → the guided cancel chain proven by the cancelled templates
 * (rung 2) → reminders while bookings are pending → (keep searching) the better-ride offer → pay hand-off.
 *
 * Time: the guardian runs on the app's FakeClock and the scripted posts on the main looper; [advance] moves both and
 * ticks the guardian the way the session service does every second.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoSessionEndToEndTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp
    private val api = ProgrammableApiClient()
    private lateinit var graph: AppGraph
    private lateinit var runtime: GuardianRuntime

    @Before
    fun setUp() {
        app.fakes.bundled = AssetBundledConfig(context.assets).load()
        app.fakes.api = api
        app.fakes.ocr = PixelFixtureOcr
        graph = app.graph
        runtime = graph.guardianRuntime
        runtime.start()
        settle()
    }

    @Test
    fun `main walk - real graph, two readers, first driver, cancel chain with rung-2 proof, reminders, pay hand-off`() {
        api.answerDemoReads(source = "recorded")
        val c = graph.demoController
        assertTrue(c.isBound)
        assertEquals(DemoStartResult.STARTED, c.start(DemoTrackChoice.MAIN))
        settle()
        val status = runtime.status.value
        assertTrue("a demo session of the one guardian", status.sessionOpen && status.demo)

        for (provider in listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND)) {
            assertEquals(provider, c.state.value.app?.provider)
            c.readFares(provider)
            eventually("read $provider") { c.state.value.provider(provider)?.read != null && c.state.value.provider(provider)?.reading == false }
            val read = c.state.value.provider(provider)!!.read!!
            assertEquals(ReadSource.RECORDED, read.source)
            assertTrue("$provider: every row VERIFIED by both readers", read.rows.isNotEmpty() && read.rows.all { it.status == QuoteStatus.VERIFIED })
            c.bookedHere(provider)
            settle()
        }
        assertEquals(DemoPhase.WATCHING, c.state.value.phase)
        // The uploads are the bundled PNG bytes, unchanged, flagged demo (the server's SHA-256 allowlist).
        assertEquals(3, api.readRequests.size)
        api.readRequests.forEach { req ->
            assertTrue(req.demo)
            val path = BundledAssets.script.provider(req.provider).screens.getValue(DemoScreens.FARE_OPTIONS)
            assertEquals(DemoImages.sha256(BundledAssets.bytes(path)), DemoImages.sha256(req.image))
        }
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { assertTrue(leg(it).booked) }

        advance(1_300)
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { assertEquals(RideState.SEARCHING, leg(it).state) }
        advance(9_000 - 1_300)
        assertEquals(RideState.ASSIGNED, leg(DEMO_PEPPER).state)
        val sheet = runtime.uiState.value.items.filterIsInstance<DriverSheetItem>().single()
        assertEquals(UiKey.SHEET_CANCEL_OTHERS, sheet.key)
        assertEquals(setOf(DEMO_KESTREL, DEMO_TAMARIND), sheet.others.map { it.provider }.toSet())
        assertTrue(c.state.value.progress.driverFound)

        // "Yes, cancel the others" through the guardian's own sheet button (the same path as the in-app sheet).
        runtime.perform(GuardianTap(TapScope.SHEET, sheet.id, UiAction.YES_CANCEL_OTHERS))
        settle()
        eventually("cancel guide") { c.state.value.app?.purpose == DemoAppPurpose.CANCEL }
        assertEquals(DEMO_KESTREL, c.state.value.app?.provider)
        assertTrue(c.state.value.progress.cancelGuideShown)

        // Reminders at 30 / 60 / 120 / 240 s while both extra bookings are pending (I8).
        advance(250_000, stepMs = 1_000)
        assertEquals(4, c.state.value.progress.reminders)
        assertTrue(runtime.status.value.needsForegroundService)

        c.tapCancelRide(DEMO_KESTREL)
        advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, leg(DEMO_KESTREL).state)
        assertEquals(EvidenceRung.TEMPLATE, leg(DEMO_KESTREL).evidence)
        eventually("Tamarind next") { c.state.value.app?.provider == DEMO_TAMARIND }
        c.tapCancelRide(DEMO_TAMARIND)
        advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, leg(DEMO_TAMARIND).state)
        assertEquals(EvidenceRung.TEMPLATE, leg(DEMO_TAMARIND).evidence)
        assertEquals(2, c.state.value.progress.cancelsProven)
        assertFalse("every extra booking proven cancelled", runtime.status.value.needsForegroundService)

        advance(45_000)
        assertTrue(c.state.value.progress.payOffered)
        c.pay(DEMO_PEPPER)
        settle()
        eventually("pay hand-off") { c.state.value.phase == DemoPhase.FINISHED }
        assertEquals(RideState.PAY_HANDOFF, leg(DEMO_PEPPER).state)

        // Everything the guardian decided is persisted like a live session (Room rows, evidence rungs).
        runBlocking {
            val sessionId = runtime.status.value.sessionId!!
            assertNotNull(graph.db.sessionDao().get(sessionId))
            assertEquals(RideState.CANCEL_CONFIRMED, graph.db.legDao().get(GuardianRuntime.legIdOf(sessionId, DEMO_KESTREL))!!.state)
        }

        c.stop()
        settle()
        eventually("demo session ended") { !runtime.status.value.sessionOpen }
        runBlocking {
            assertTrue(graph.db.sessionDao().active().none { it.status == SessionStatus.ACTIVE })
        }
    }

    @Test
    fun `keep searching walk - better-ride offer switches only on Yes, both cancels proven, pay hand-off`() {
        api.answerDemoReads(source = "cache")
        val c = graph.demoController
        assertEquals(DemoStartResult.STARTED, c.start(DemoTrackChoice.KEEP_SEARCHING))
        settle()
        for (provider in listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND)) {
            c.readFares(provider)
            eventually("read $provider") { c.state.value.provider(provider)?.read != null && c.state.value.provider(provider)?.reading == false }
            c.bookedHere(provider)
            settle()
        }
        assertEquals(ReadSource.CACHE, c.state.value.provider(DEMO_PEPPER)!!.read!!.source)

        advance(9_000)
        val first = runtime.uiState.value.items.filterIsInstance<DriverSheetItem>().single()
        assertTrue(UiAction.KEEP_SEARCHING in first.actions)
        runtime.perform(GuardianTap(TapScope.SHEET, first.id, UiAction.KEEP_SEARCHING))
        settle()

        // Tamarind's much closer driver → the better-ride offer; nothing switches by itself (I4).
        advance(8_000)
        val offer = runtime.uiState.value.items.filterIsInstance<BetterRideItem>().single()
        assertEquals(UiKey.SHEET_BETTER_RIDE, offer.key)
        assertTrue(c.state.value.progress.betterRideOffered)
        assertEquals(RideState.ASSIGNED, leg(DEMO_PEPPER).state)

        runtime.perform(GuardianTap(TapScope.SHEET, offer.id, UiAction.SWITCH))
        settle()
        eventually("old leg guided first") { c.state.value.app?.provider == DEMO_PEPPER && c.state.value.app?.purpose == DemoAppPurpose.CANCEL }
        // The new driver is ≤ 2 min away: within the session service's next 1 s tick, better-ride alerts stop and
        // Kestrel (still searching) gets "Cancel the others?".
        advance(1_000)
        val second = runtime.uiState.value.items.filterIsInstance<DriverSheetItem>().single()
        assertEquals(UiKey.SHEET_CANCEL_OTHERS, second.key)
        runtime.perform(GuardianTap(TapScope.SHEET, second.id, UiAction.YES_CANCEL_OTHERS))
        settle()

        c.tapCancelRide(DEMO_PEPPER)
        advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, leg(DEMO_PEPPER).state)
        eventually("Kestrel next") { c.state.value.app?.provider == DEMO_KESTREL }
        c.tapCancelRide(DEMO_KESTREL)
        advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, leg(DEMO_KESTREL).state)

        advance(45_000)
        assertTrue(c.state.value.progress.payOffered)
        c.pay(DEMO_TAMARIND)
        settle()
        eventually("pay hand-off") { c.state.value.phase == DemoPhase.FINISHED }
        assertEquals(RideState.PAY_HANDOFF, leg(DEMO_TAMARIND).state)
        c.stop()
        settle()
    }

    @Test
    fun `a Compare while the demo plays never replaces its session - the demo still reaches the pay hand-off`() {
        // Found on the phone: the guardian ended up on a live Uber/Ola/Rapido session (all SELECTED) in the middle of
        // the keep-searching walk, so the demo's ride never ended and the next test found Home showing that session.
        val c = graph.demoController
        assertEquals(DemoStartResult.STARTED, c.start(DemoTrackChoice.KEEP_SEARCHING))
        settle()
        val demoSession = runtime.status.value.sessionId
        for (provider in listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND)) {
            c.bookedHere(provider)
            settle()
        }
        advance(9_000)
        val first = runtime.uiState.value.items.filterIsInstance<DriverSheetItem>().single()
        runtime.perform(GuardianTap(TapScope.SHEET, first.id, UiAction.KEEP_SEARCHING))
        settle()
        advance(8_000)
        val offer = runtime.uiState.value.items.filterIsInstance<BetterRideItem>().single()
        runtime.perform(GuardianTap(TapScope.SHEET, offer.id, UiAction.SWITCH))
        settle()
        advance(1_000)
        val second = runtime.uiState.value.items.filterIsInstance<DriverSheetItem>().single()
        runtime.perform(GuardianTap(TapScope.SHEET, second.id, UiAction.YES_CANCEL_OTHERS))
        settle()
        eventually("Pepper guided first") { c.state.value.app?.provider == DEMO_PEPPER && c.state.value.app?.purpose == DemoAppPurpose.CANCEL }
        c.tapCancelRide(DEMO_PEPPER)
        advance(2_000)
        eventually("Kestrel next") { c.state.value.app?.provider == DEMO_KESTREL }
        c.tapCancelRide(DEMO_KESTREL)
        advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, leg(DEMO_PEPPER).state)
        assertEquals(RideState.CANCEL_CONFIRMED, leg(DEMO_KESTREL).state)

        // Both other bookings proven cancelled, Tamarind's ride still to end: a real comparison is asked for.
        val plan = TripPlan(
            pickup = TripPlace("Pickup", LatLng(26.1445, 91.7362)),
            drop = TripPlace("Drop", LatLng(26.1158, 91.7086)),
            apps = listOf(Provider.UBER, Provider.OLA, Provider.RAPIDO),
            vehicles = setOf(VehicleChoice.AUTO),
            includeOndc = false,
        )
        assertNull("refused while the demo runs", graph.comparisonStarter.start(plan))
        settle()
        val status = runtime.status.value
        assertEquals("the demo keeps its session", demoSession, status.sessionId)
        assertTrue(status.sessionOpen && status.demo)
        assertTrue("no live leg", runtime.uiState.value.legs.all { it.provider.isDemo })
        assertTrue(c.state.value.running)

        // The scripted ride still ends: pay hand-off in Tamarind Cabs, then the demo ends cleanly.
        advance(45_000)
        assertTrue(c.state.value.progress.payOffered)
        c.pay(DEMO_TAMARIND)
        settle()
        eventually("pay hand-off") { c.state.value.phase == DemoPhase.FINISHED }
        assertEquals(RideState.PAY_HANDOFF, leg(DEMO_TAMARIND).state)
        c.stop()
        settle()
        eventually("demo session ended") { !runtime.status.value.sessionOpen }
        assertEquals(SessionStatus.CLOSED, runBlocking { graph.db.sessionDao().get(demoSession!!) }?.status)
    }

    @Test
    fun `a second demo run in the same process still proves its cancels by rung 2`() {
        // The script posts the same words on the same keys every run; the first run's posts must not make the second
        // run's "Trip cancelled" look like a repeat (found on the phone: the second run fell back to rung 3).
        val c = graph.demoController
        repeat(2) { run ->
            assertEquals(DemoStartResult.STARTED, c.start(DemoTrackChoice.MAIN))
            settle()
            for (provider in listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND)) {
                c.bookedHere(provider)
                settle()
            }
            advance(9_000)
            val sheet = runtime.uiState.value.items.filterIsInstance<DriverSheetItem>().single()
            runtime.perform(GuardianTap(TapScope.SHEET, sheet.id, UiAction.YES_CANCEL_OTHERS))
            settle()
            eventually("cancel guide, run ${run + 1}") { c.state.value.app?.provider == DEMO_KESTREL && c.state.value.app?.purpose == DemoAppPurpose.CANCEL }
            c.tapCancelRide(DEMO_KESTREL)
            advance(2_000)
            assertEquals("run ${run + 1}", RideState.CANCEL_CONFIRMED, leg(DEMO_KESTREL).state)
            assertEquals("run ${run + 1}", EvidenceRung.TEMPLATE, leg(DEMO_KESTREL).evidence)
            c.stop()
            settle()
            eventually("demo session ended, run ${run + 1}") { !runtime.status.value.sessionOpen }
        }
    }

    @Test
    fun `demo posts are dropped outside a demo session (I7)`() {
        runtime.onDemoPost(BundledAssets.script.track("main")!!.events.first().toSnapshot(1L, 1L))
        settle()
        assertTrue(runtime.uiState.value.legs.isEmpty())
        assertFalse(runtime.status.value.sessionOpen)
    }

    // ------------------------------------------------------------------ helpers

    private fun leg(provider: Provider): LegUi = runtime.uiState.value.legs.first { it.provider == provider }

    /** Lets the guardian thread, its storage writer and the main looper run until nothing is left to do. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            // Waited for off the main thread while the main looper keeps running (never block main on the guardian).
            val done = CoroutineScope(Dispatchers.Default).async { withTimeout(20_000) { runtime.awaitIdle() } }
            while (!done.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
            runBlocking { done.await() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Moves the app clock and the main looper together, ticking the guardian like the session service (1 s). */
    private fun advance(ms: Long, stepMs: Long = 500) {
        var left = ms
        while (left > 0) {
            val step = minOf(stepMs, left)
            app.fakes.clock.advance(step)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            runtime.tick()
            settle()
            left -= step
        }
    }

    private fun eventually(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            settle()
            Thread.sleep(5)
        }
    }

    /**
     * The capture area's OcrEngine for these tests: ML Kit can't run on the JVM, so each bundled screen's recorded
     * synthetic tokens are returned for the exact pixels it was given (screens are told apart by a pixel fingerprint).
     */
    private object PixelFixtureOcr : OcrEngine {
        private val bySha: Map<String, String> by lazy {
            BundledAssets.script.providers.values.flatMap { it.screens.values }.distinct().associate { path ->
                val bytes = BundledAssets.bytes(path)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try {
                    fingerprint(bitmap) to DemoImages.sha256(bytes)
                } finally {
                    bitmap.recycle()
                }
            }
        }

        override suspend fun recognize(frame: FrameImage): OcrFrame {
            val sha = bySha[frame.read(::fingerprint)] ?: error("not a bundled demo screen")
            return DemoFixtures.ocr(sha)
        }

        private fun fingerprint(bitmap: Bitmap): String = buildString {
            for (y in 1..GRID) for (x in 1..GRID) {
                append(Integer.toHexString(bitmap.getPixel(x * bitmap.width / (GRID + 1), y * bitmap.height / (GRID + 1))))
            }
        }

        private const val GRID = 24
    }

    private companion object {
        const val SETTLE_ROUNDS = 3
    }
}
