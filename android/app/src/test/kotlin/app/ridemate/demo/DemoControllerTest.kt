package app.ridemate.demo

import app.ridemate.core.format.DistanceText
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.SheetChoice
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.invariants.Invariants
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.Provider.DEMO_KESTREL
import app.ridemate.core.model.Provider.DEMO_PEPPER
import app.ridemate.core.model.Provider.DEMO_TAMARIND
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.state.TapId
import app.ridemate.core.watch.RideWatchPipeline
import app.ridemate.net.ApiResult
import app.ridemate.net.PointResponse
import app.ridemate.testing.FakeAuthBackend
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Try the demo end to end on virtual time: the bundled script played by [DemoController] through the real core guardian
 * and RideWatch (CoreDemoHost), prices read through the real gate, and every checkpoint of `script.json` verified
 * against what the guardian actually emitted.
 */
class DemoControllerTest {

    // ------------------------------------------------------------------ the main walk

    @Test
    fun `main track - signed out, recorded reads, first driver, cancel chain with proof and reminders, pay hand-off`() = runTest {
        val h = DemoHarness(this, authBackend = FakeAuthBackend(user = null))
        h.api.answerDemoReads(source = "recorded")
        val c = h.controller

        assertEquals(DemoStartResult.STARTED, c.start(DemoTrackChoice.MAIN))
        h.runCurrent()
        assertEquals(false, h.host.startedKeepSearching)
        assertEquals(DemoPhase.BOOKING, c.state.value.phase)
        assertEquals(DemoAppView(DEMO_KESTREL, DemoAppPurpose.BOOK, DemoScreens.FARE_OPTIONS, "demo/kestrel/fare_options.png"), c.state.value.app)

        // Reading prices and booking, in walk order (Kestrel, Pepper, Tamarind).
        for (provider in listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND)) {
            assertEquals(provider, c.state.value.app?.provider)
            h.readAndBook(provider)
            val read = c.state.value.provider(provider)!!.read!!
            assertEquals(ReadSource.RECORDED, read.source)
            assertFalse("the server answered, nothing came from the phone's copy", read.recordedOnPhone)
            assertTrue("every demo row is VERIFIED by both readers", read.rows.isNotEmpty() && read.rows.all { it.status == QuoteStatus.VERIFIED })
        }
        assertTrue("signed out → anonymous sign-in for the demo cache", "signInAnonymous" in h.authBackend.log)
        assertEquals(DemoPhase.WATCHING, c.state.value.phase)
        assertNull(c.state.value.app)
        assertEquals(3, c.state.value.progress.booked)
        assertEquals(3, c.state.value.progress.pricesRead)
        // The booked option's VERIFIED fares (script `booked`): Kestrel Auto ₹180–210, Pepper ऑटो ₹172, Tamarind Auto ₹176.
        assertEquals(mapOf(leg(h, DEMO_KESTREL) to 210, leg(h, DEMO_PEPPER) to 172, leg(h, DEMO_TAMARIND) to 176), h.host.bookedFares)
        assertEquals(3, h.host.quoted.size)
        // The uploads are the bundled PNG bytes, unchanged, flagged demo.
        h.api.readRequests.forEach { req ->
            assertTrue(req.demo)
            assertEquals(1080, req.frameWidth)
            assertEquals(2400, req.frameHeight)
            val path = BundledAssets.script.provider(req.provider).screens.getValue(DemoScreens.FARE_OPTIONS)
            assertEquals(DemoImages.sha256(BundledAssets.bytes(path)), DemoImages.sha256(req.image))
        }

        // Searching on all three, then Pepper's driver 9 s after the last booking.
        h.advance(1_300)
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { assertEquals(RideState.SEARCHING, h.host.leg(it).leg.state) }
        assertEquals(DemoScreens.SEARCHING, c.state.value.provider(DEMO_PEPPER)!!.screen)
        h.advance(9_000 - 1_300)
        assertEquals(RideState.ASSIGNED, h.host.leg(DEMO_PEPPER).leg.state)
        val sheet = h.host.effectsOf<GuardianEffect.DriverSheet>().single().second
        assertEquals(UiKey.SHEET_CANCEL_OTHERS, sheet.key)
        assertEquals(listOf(UiAction.YES_CANCEL_OTHERS, UiAction.NOT_NOW), sheet.actions)
        assertEquals(EtaSource.TEMPLATE, sheet.drivers.single().etaSource)
        assertEquals(setOf(DEMO_KESTREL, DEMO_TAMARIND), sheet.others.map { it.provider }.toSet())
        assertTrue(c.state.value.progress.driverFound)
        assertEquals(DemoScreens.DRIVER_ASSIGNED, c.state.value.provider(DEMO_PEPPER)!!.screen)

        // "Yes, cancel the others" on the session area's sheet: the demo opens Kestrel's cancel screen.
        h.answerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, DemoUserStep.CancelOthersYes)
        assertEquals(DemoAppView(DEMO_KESTREL, DemoAppPurpose.CANCEL, DemoScreens.CANCEL_FLOW, "demo/kestrel/cancel_flow.png"), c.state.value.app)
        assertTrue(c.state.value.progress.cancelGuideShown)

        // Reminders while both extra bookings are pending.
        h.advance(250_000)
        assertEquals(4, c.state.value.progress.reminders)

        // The user taps Cancel ride in each demo app; each cancel is proven by its cancelled template (rung 2).
        h.tapCancel(DEMO_KESTREL)
        h.advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_KESTREL).leg.state)
        assertEquals(EvidenceRung.TEMPLATE, h.host.leg(DEMO_KESTREL).leg.confirmedBy)
        assertTrue(c.state.value.provider(DEMO_KESTREL)!!.cancelProven)
        assertEquals(DemoAppView(DEMO_TAMARIND, DemoAppPurpose.CANCEL, DemoScreens.CANCEL_FLOW, "demo/tamarind/cancel_flow.png"), c.state.value.app)

        h.tapCancel(DEMO_TAMARIND)
        h.advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_TAMARIND).leg.state)
        assertEquals(2, c.state.value.progress.cancelsProven)
        assertTrue(h.host.effectsOf<GuardianEffect.Banner>().any { it.second.key == UiKey.BANNER_ALL_CANCELS_DONE })
        assertFalse(h.host.guardian.needsForegroundService)
        // The cancelled screen replaced the cancel screen in the demo app in front.
        assertEquals(DemoScreens.CANCELLED, c.state.value.app?.screen)

        // Pepper arrives, the trip starts and ends: the pay hand-off is offered; Pay opens Pepper Rides (demo).
        h.advance(45_000)
        assertTrue(c.state.value.progress.payOffered)
        c.pay(DEMO_PEPPER)
        h.runCurrent()
        assertEquals(RideState.PAY_HANDOFF, h.host.leg(DEMO_PEPPER).leg.state)
        assertEquals(DemoPhase.FINISHED, c.state.value.phase)
        assertEquals(DemoAppPurpose.PAY, c.state.value.app?.purpose)
        assertEquals(DEMO_PEPPER, c.state.value.app?.provider)
        assertTrue(c.state.value.progress.paymentHandedOff)

        verifyCheckpoints(h, "main")
        assertEquals(BundledAssets.script.track("main")!!.events.map { it.id }.toSet(), firedIds(h))
        assertInvariants(h)

        c.stop()
        assertTrue(h.host.ended)
        assertFalse(h.host.pipeline.demoSessionActive)
        assertEquals(DemoPhase.IDLE, c.state.value.phase)
    }

    // ------------------------------------------------------------------ keep searching → better ride

    @Test
    fun `keep searching track - better-ride offer, switch only on Yes, old leg and searching leg cancelled with proof`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads(source = "cache")
        val c = h.controller
        assertEquals(DemoStartResult.STARTED, c.start(DemoTrackChoice.KEEP_SEARCHING))
        h.runCurrent()
        assertEquals(true, h.host.startedKeepSearching)
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { h.readAndBook(it) }
        assertEquals(ReadSource.CACHE, c.state.value.provider(DEMO_PEPPER)!!.read!!.source)

        h.advance(9_000)
        val first = h.host.effectsOf<GuardianEffect.DriverSheet>().single().second
        assertTrue(UiAction.KEEP_SEARCHING in first.actions)
        h.answerSheet(first.sheetId, SheetChoice.KEEP_SEARCHING, DemoUserStep.CancelOthersNotNow)
        assertNull("nothing is cancelled when the user keeps searching", c.state.value.app)

        // Tamarind's much closer driver (2 min vs 9 min) → better-ride offer; nothing switches by itself.
        h.advance(8_000)
        val offer = h.host.effectsOf<GuardianEffect.BetterRideSheet>().single().second
        assertEquals(UiKey.SHEET_BETTER_RIDE, offer.key)
        assertEquals(listOf(UiAction.SWITCH, UiAction.KEEP_CURRENT), offer.actions)
        assertTrue(c.state.value.progress.betterRideOffered)
        assertEquals(DEMO_PEPPER, h.host.guardian.state.legs.values.first { it.role == app.ridemate.core.guardian.LegRole.KEPT }.provider)

        // Yes → the old leg (Pepper) is guided first (§4.8 "Yes → guided cancel of the old leg").
        h.answerSheet(offer.sheetId, SheetChoice.SWITCH, DemoUserStep.BetterRideYes)
        assertEquals(DemoAppView(DEMO_PEPPER, DemoAppPurpose.CANCEL, DemoScreens.CANCEL_FLOW, "demo/pepper/cancel_flow.png"), c.state.value.app)
        // The new driver is ≤ 2 min away, so better-ride alerts stop and Kestrel (still searching) gets "Cancel the others?".
        val second = h.host.effectsOf<GuardianEffect.DriverSheet>().last().second
        assertEquals(UiKey.SHEET_CANCEL_OTHERS, second.key)
        assertEquals(DEMO_TAMARIND, second.drivers.single().provider)
        assertEquals(listOf(DEMO_KESTREL), second.others.map { it.provider })
        h.answerSheet(second.sheetId, SheetChoice.YES_CANCEL_OTHERS, DemoUserStep.CancelOthersYes)

        h.tapCancel(DEMO_PEPPER)
        h.advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_PEPPER).leg.state)
        assertEquals(DemoAppView(DEMO_KESTREL, DemoAppPurpose.CANCEL, DemoScreens.CANCEL_FLOW, "demo/kestrel/cancel_flow.png"), c.state.value.app)
        h.tapCancel(DEMO_KESTREL)
        h.advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_KESTREL).leg.state)

        h.advance(45_000)
        assertTrue(c.state.value.progress.payOffered)
        c.pay(DEMO_TAMARIND)
        h.runCurrent()
        assertEquals(RideState.PAY_HANDOFF, h.host.leg(DEMO_TAMARIND).leg.state)
        assertEquals(DemoPhase.FINISHED, c.state.value.phase)

        // The script expects the chain as [kestrel, pepper]; the guardian (authoritative, §4.8) guides the old leg first
        // and adds Kestrel when better-ride alerts stop, so the order is [pepper, kestrel].
        verifyCheckpoints(h, "keep_searching", chainOrders = mapOf("better_ride:yes" to listOf(DEMO_PEPPER, DEMO_KESTREL)))
        assertEquals(BundledAssets.script.track("keep_searching")!!.events.map { it.id }.toSet(), firedIds(h))
        assertInvariants(h)
    }

    @Test
    fun `keep searching on but the user says Yes - the walk continues on the main track's proof for Tamarind`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.KEEP_SEARCHING)
        h.runCurrent()
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { h.readAndBook(it) }
        h.advance(9_000)
        val sheet = h.host.effectsOf<GuardianEffect.DriverSheet>().single().second
        h.answerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, DemoUserStep.CancelOthersYes)
        h.tapCancel(DEMO_KESTREL)
        h.advance(2_000)
        h.tapCancel(DEMO_TAMARIND)
        h.advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_KESTREL).leg.state)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_TAMARIND).leg.state)
        h.advance(45_000)
        assertEquals(RideState.ENDED, h.host.leg(DEMO_PEPPER).leg.state)
        assertTrue(c.state.value.progress.payOffered)
        assertInvariants(h)
    }

    // ------------------------------------------------------------------ re-match

    @Test
    fun `rematch track - driver cancels, new generation with a closer driver, pay hand-off`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads(source = "live")
        val c = h.controller
        assertEquals(DemoStartResult.STARTED, c.start(DemoTrackChoice.REMATCH))
        h.runCurrent()
        assertEquals(listOf(DEMO_PEPPER), c.state.value.providers.map { it.provider })
        h.readAndBook(DEMO_PEPPER)
        assertEquals(ReadSource.LIVE, c.state.value.provider(DEMO_PEPPER)!!.read!!.source)
        h.advance(20_000)
        assertEquals(RideState.DRIVER_CANCELLED, h.host.leg(DEMO_PEPPER).leg.state)
        h.advance(10_000)
        assertEquals(RideState.ASSIGNED, h.host.leg(DEMO_PEPPER).leg.state)
        assertEquals(1, h.host.leg(DEMO_PEPPER).leg.generation)
        assertEquals("driver_assigned_better", c.state.value.provider(DEMO_PEPPER)!!.screen)
        h.advance(46_000)
        assertTrue(c.state.value.progress.payOffered)
        verifyCheckpoints(h, "rematch")
        assertInvariants(h)
    }

    // ------------------------------------------------------------------ other taps

    @Test
    fun `Booked here tapped on the bubble walks the demo on like the screen's button`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        h.host.userAction(UserAction.BookedHere(leg(h, DEMO_KESTREL), TapId("bubble-tap")))
        h.runCurrent()
        assertTrue(c.state.value.provider(DEMO_KESTREL)!!.booked)
        assertEquals(1, c.state.value.progress.booked)
        assertEquals(DEMO_PEPPER, c.state.value.app?.provider)
        h.advance(1_300)
        assertEquals(RideState.SEARCHING, h.host.leg(DEMO_KESTREL).leg.state)
        // The screen's own button for an already-booked app does nothing more.
        c.bookedHere(DEMO_KESTREL)
        assertEquals(1, h.host.guardian.state.actions.count { it.kind == app.ridemate.core.guardian.ActionKind.BOOKED_HERE })
    }

    @Test
    fun `I cancelled it on the cancel card stands in for the demo tap and the scripted proof still confirms`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { h.readAndBook(it) }
        h.advance(9_000)
        val sheet = h.host.effectsOf<GuardianEffect.DriverSheet>().single().second
        h.answerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, DemoUserStep.CancelOthersYes)

        h.host.userAction(UserAction.ICancelledIt(leg(h, DEMO_KESTREL), TapId("card-tap")))
        h.runCurrent()
        assertEquals(RideState.USER_SAYS_CANCELLED, h.host.leg(DEMO_KESTREL).leg.state)
        assertTrue(c.state.value.provider(DEMO_KESTREL)!!.cancelTapped)
        h.advance(1_500)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_KESTREL).leg.state)
        assertEquals(EvidenceRung.TEMPLATE, h.host.leg(DEMO_KESTREL).leg.confirmedBy)
        assertInvariants(h)
    }

    @Test
    fun `Not now with keep searching off continues on the keep-searching posts - two drivers, pick one, proofs, pay`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { h.readAndBook(it) }
        h.advance(9_000)
        val sheet = h.host.effectsOf<GuardianEffect.DriverSheet>().single().second
        h.answerSheet(sheet.sheetId, SheetChoice.NOT_NOW, DemoUserStep.CancelOthersNotNow)
        assertNull(c.state.value.notice)

        // Tamarind's driver 8 s later: "Two drivers found. Pick one" (never picked for the user).
        h.advance(8_000)
        val pick = h.host.effectsOf<GuardianEffect.DriverSheet>().last().second
        assertEquals(UiKey.SHEET_TWO_DRIVERS_PICK_ONE, pick.key)
        assertEquals(setOf(DEMO_PEPPER, DEMO_TAMARIND), pick.drivers.map { it.provider }.toSet())
        h.anchor(DemoUserStep.CancelOthersYes)
        h.host.userAction(UserAction.AnswerSheet(pick.sheetId, SheetChoice.PICK, h.tap(), pickedLegId = leg(h, DEMO_PEPPER)))
        h.runCurrent()
        assertEquals(DEMO_KESTREL, c.state.value.app?.provider)

        h.tapCancel(DEMO_KESTREL)
        h.advance(2_000)
        assertEquals(DEMO_TAMARIND, c.state.value.app?.provider)
        h.tapCancel(DEMO_TAMARIND)
        h.advance(2_000)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_KESTREL).leg.state)
        assertEquals(RideState.CANCEL_CONFIRMED, h.host.leg(DEMO_TAMARIND).leg.state)
        assertEquals(EvidenceRung.TEMPLATE, h.host.leg(DEMO_TAMARIND).leg.confirmedBy)
        h.advance(45_000)
        assertTrue(c.state.value.progress.payOffered)
        assertInvariants(h)
    }

    @Test
    fun `Not now with nothing more scripted leaves a notice that says what to do`() = runTest {
        val onlyMain = run {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(BundledAssets.text(DemoScript.ASSET_PATH)) as JsonObject
            val tracks = (root["tracks"] as JsonArray).filter { (it as JsonObject).str("id") == "main" }
            JsonObject(root + ("tracks" to JsonArray(tracks))).toString()
        }
        val h = DemoHarness(this, assets = FileDemoAssets().also { it.scriptOverride = onlyMain })
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { h.readAndBook(it) }
        h.advance(9_000)
        val sheet = h.host.effectsOf<GuardianEffect.DriverSheet>().single().second
        h.answerSheet(sheet.sheetId, SheetChoice.NOT_NOW, DemoUserStep.CancelOthersNotNow)
        assertEquals(DemoNotice.NOT_NOW_CHOSEN, c.state.value.notice)
        c.dismissNotice()
        assertNull(c.state.value.notice)
    }

    @Test
    fun `Show me asks the guardian, then points beside the Cancel button, or explains why it can't`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { h.readAndBook(it) }
        h.advance(9_000)
        val sheet = h.host.effectsOf<GuardianEffect.DriverSheet>().single().second
        h.answerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, DemoUserStep.CancelOthersYes)

        // Signed out: the server refuses grounding → notice, no arrow.
        h.api.point = { ApiResult.GoogleRequired }
        c.showMe(DEMO_KESTREL)
        h.runCurrent()
        assertEquals(DemoNotice.SHOW_ME_UNAVAILABLE, c.state.value.notice)
        assertNull(c.state.value.app?.pointer)
        assertEquals("cancel_button", h.api.pointRequests.single().target)
        val sentSha = DemoImages.sha256(h.api.pointRequests.single().image)
        assertEquals(DemoImages.sha256(BundledAssets.bytes("demo/kestrel/cancel_flow.png")), sentSha)

        h.api.point = { ApiResult.Ok(PointResponse(supported = true, box0to1000 = listOf(100, 800, 900, 860))) }
        c.showMe(DEMO_KESTREL)
        h.runCurrent()
        assertEquals(listOf(100, 800, 900, 860), c.state.value.app?.pointer)
    }

    @Test
    fun `a cancel-proof capture of a demo app is its current screen checked for cancelled keywords`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        val kestrel = leg(h, DEMO_KESTREL)
        // Fare screen: no cancelled keyword.
        c.onEffect(GuardianEffect.RequestCapture(kestrel, DEMO_KESTREL, app.ridemate.core.guardian.CaptureReason.CANCEL_PROOF))
        h.runCurrent()
        assertEquals(listOf(kestrel to false), h.host.verdicts)
        // Once the demo app shows its cancelled screen (after the scripted proof), the capture finds the keywords.
        h.readAndBook(DEMO_KESTREL)
        h.tapCancel(DEMO_KESTREL)
        h.advance(2_000)
        assertEquals(DemoScreens.CANCELLED, c.state.value.provider(DEMO_KESTREL)!!.screen)
        c.onEffect(GuardianEffect.RequestCapture(kestrel, DEMO_KESTREL, app.ridemate.core.guardian.CaptureReason.CANCEL_PROOF))
        h.runCurrent()
        assertEquals(kestrel to true, h.host.verdicts.last())
    }

    @Test
    fun `a capture still being read when the demo is left never reaches the ended session`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        val kestrel = leg(h, DEMO_KESTREL)
        c.onEffect(GuardianEffect.RequestCapture(kestrel, DEMO_KESTREL, app.ridemate.core.guardian.CaptureReason.CANCEL_PROOF))
        c.onEffect(GuardianEffect.RequestCapture(kestrel, DEMO_KESTREL, app.ridemate.core.guardian.CaptureReason.CHECK_SCREEN))
        c.stop()
        h.runCurrent()
        assertTrue(h.host.ended)
        assertTrue(h.host.verdicts.isEmpty())
        assertTrue(h.host.driverReadings.isEmpty())
    }

    @Test
    fun `live and other non-demo effects are ignored by the demo`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        val before = c.state.value
        c.onEffect(GuardianEffect.OpenApp("live:uber", Provider.UBER, OpenPurpose.CANCEL, null))
        c.onEffect(GuardianEffect.RequestCapture("live:uber", Provider.UBER, app.ridemate.core.guardian.CaptureReason.CANCEL_PROOF))
        h.runCurrent()
        assertEquals(before, c.state.value)
        assertTrue(h.host.verdicts.isEmpty())
    }

    // ------------------------------------------------------------------ availability

    @Test
    fun `start refuses clearly when unbound, switched off, busy or broken`() = runTest {
        val unbound = DemoHarness(this)
        val bare = DemoController(
            unbound.assets, unbound.reader, unbound.api, unbound.clock, backgroundScope, unbound.demoEnabled, { BundledAssets.cancelCards },
        )
        assertFalse(bare.isBound)
        assertEquals(DemoStartResult.NOT_READY, bare.start())
        assertEquals(DemoUnavailable.NOT_READY, bare.state.value.unavailable)
        assertEquals(DemoPhase.UNAVAILABLE, bare.state.value.phase)

        val off = DemoHarness(this)
        off.demoEnabled.value = false
        assertEquals(DemoStartResult.SWITCHED_OFF, off.controller.start())
        assertEquals(DemoUnavailable.SWITCHED_OFF, off.controller.state.value.unavailable)

        val busy = DemoHarness(this)
        busy.host.busy = true
        assertEquals(DemoStartResult.SESSION_BUSY, busy.controller.start())
        assertEquals(DemoUnavailable.SESSION_BUSY, busy.controller.state.value.unavailable)

        val broken = DemoHarness(this, assets = FileDemoAssets().also { it.scriptOverride = "{\"schema\": \"1.0\"}" })
        assertEquals(DemoStartResult.ASSETS_BROKEN, broken.controller.start())
        assertEquals(DemoUnavailable.ASSETS_BROKEN, broken.controller.state.value.unavailable)

        val ok = DemoHarness(this)
        assertEquals(DemoStartResult.STARTED, ok.controller.start())
        assertEquals(DemoStartResult.ALREADY_RUNNING, ok.controller.start())
    }

    @Test
    fun `switching the demo off remotely mid-walk stops it, ends the session and fires nothing more`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        h.readAndBook(DEMO_KESTREL)
        h.demoEnabled.value = false
        h.runCurrent()
        assertTrue(h.host.ended)
        assertEquals(DemoPhase.IDLE, c.state.value.phase)
        assertEquals(DemoNotice.SWITCHED_OFF_WHILE_RUNNING, c.state.value.notice)
        val audited = h.host.pipeline.audit.size
        h.advance(60_000)
        assertEquals("no scripted post after the demo stopped", audited, h.host.pipeline.audit.size)
    }

    @Test
    fun `leaving the demo cancels pending scripted events`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        val c = h.controller
        c.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { h.readAndBook(it) }
        c.stop()
        h.advance(30_000)
        assertTrue(h.host.pipeline.audit.isEmpty())
        assertTrue(h.host.ended)
    }

    @Test
    fun `demo posts reach RideWatch only while the demo session runs (I7)`() = runTest {
        val h = DemoHarness(this)
        h.api.answerDemoReads()
        h.controller.start(DemoTrackChoice.MAIN)
        h.runCurrent()
        assertTrue(h.host.pipeline.isAllowlisted(DEMO_PEPPER.packageName))
        h.controller.stop()
        assertFalse(h.host.pipeline.isAllowlisted(DEMO_PEPPER.packageName))
        assertTrue(RideWatchPipeline.DEMO_PACKAGES.none { it in Provider.RIDE_APP_PACKAGES })
    }

    // ------------------------------------------------------------------ helpers

    private fun leg(h: DemoHarness, provider: Provider): String = h.controller.state.value.provider(provider)!!.legId

    private fun firedIds(h: DemoHarness): Set<String> = h.controller.firedEventIds.toSet()

    private fun assertInvariants(h: DemoHarness) {
        val report = Invariants.checkGuardian(h.host.guardian)
        assertTrue("guardian invariants: ${report.violations}", report.ok)
        val i9 = Invariants.checkI9(h.host.pipeline.audit, h.host.guardian.let { h.now })
        assertTrue("I9: $i9", i9.isEmpty())
    }

    /**
     * Checks every checkpoint of a script track against the guardian's effects, timed from the step it follows.
     * [chainOrders] overrides a `cancel_chain` order where the guardian (authoritative) differs from the script.
     */
    private fun verifyCheckpoints(h: DemoHarness, trackId: String, chainOrders: Map<String, List<Provider>> = emptyMap()) {
        val track = BundledAssets.script.track(trackId)!!
        for (cp in track.checkpoints) {
            val step = cp.str("after_user_step")
            val anchor = h.anchors[step] ?: fail("step $step was never taken").let { 0L }
            val provider = cp.strOrNull("package")?.let { Provider.fromPackage(it) }
            when (val expect = cp.str("expect")) {
                "first_driver_sheet" -> {
                    val (at, sheet) = h.host.effectsOf<GuardianEffect.DriverSheet>().first { it.second.key == UiKey.SHEET_CANCEL_OTHERS }
                    assertEquals("$expect time", anchor + cp.long("at_ms"), at)
                    val driver = sheet.drivers.single()
                    assertEquals(provider, driver.provider)
                    assertEquals(cp.int("driver_eta_min"), driver.etaMin)
                    assertEquals(DistanceText.parseMeters(cp.str("driver_distance_text")), driver.distanceMeters)
                }
                "cancel_chain" -> {
                    val expected = chainOrders[step] ?: (cp["order"] as JsonArray).map { Provider.fromPackage(it.jsonPrimitive.content)!! }
                    val opened = h.host.effectsOf<GuardianEffect.OpenApp>()
                        .filter { (at, e) -> at >= anchor && e.purpose == OpenPurpose.CANCEL }
                        .map { it.second.provider }.distinct()
                    assertEquals("$expect after $step", expected, opened)
                }
                "reminder_while_pending" -> {
                    val reminders = h.host.effectsOf<GuardianEffect.Reminder>()
                    val packages = (cp["packages"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
                    (cp["at_ms"] as JsonArray).map { it.jsonPrimitive.long }.forEach { offset ->
                        val reminder = reminders.firstOrNull { it.first == anchor + offset }
                        assertNotNull("reminder at +$offset ms", reminder)
                        assertEquals(packages, reminder!!.second.legs.map { it.provider.packageName }.toSet())
                    }
                }
                "cancel_confirmed" -> {
                    val at = anchor + cp.long("at_ms")
                    val banner = h.host.effectsOf<GuardianEffect.Banner>()
                        .firstOrNull { it.second.key == UiKey.BANNER_CANCEL_CONFIRMED && it.second.provider == provider }
                    assertNotNull("cancel confirmed for $provider", banner)
                    assertEquals("$expect time for $provider", at, banner!!.first)
                    assertEquals(cp.int("rung"), h.host.leg(provider!!).leg.confirmedBy!!.rung)
                }
                "pay_handoff_offer" -> {
                    val (at, offer) = h.host.effectsOf<GuardianEffect.PayHandoffOffer>().single()
                    assertEquals(provider, offer.provider)
                    assertEquals("$expect time", anchor + cp.long("at_ms"), at)
                }
                "better_ride_offer" -> {
                    val (at, offer) = h.host.effectsOf<GuardianEffect.BetterRideSheet>().first { it.second.key == UiKey.SHEET_BETTER_RIDE }
                    assertEquals("$expect time", anchor + cp.long("at_ms"), at)
                    assertEquals(Provider.fromPackage(cp.str("current")), offer.current.provider)
                    assertEquals(Provider.fromPackage(cp.str("better")), offer.candidate.provider)
                    assertEquals(cp.int("eta_gain_min"), offer.current.etaMin!! - offer.candidate.etaMin!!)
                }
                "driver_cancelled_notice" -> {
                    val (at, _) = h.host.effectsOf<GuardianEffect.Banner>()
                        .first { it.second.key == UiKey.BANNER_FINDING_ANOTHER_CAPTAIN && it.second.provider == provider }
                    assertEquals("$expect time", anchor + cp.long("at_ms"), at)
                }
                "assigned_new_generation" -> {
                    val (at, banner) = h.host.effectsOf<GuardianEffect.RideBanner>().first { it.second.driver.generation > 0 }
                    assertEquals("$expect time", anchor + cp.long("at_ms"), at)
                    assertEquals(provider, banner.driver.provider)
                    assertEquals(cp.int("driver_eta_min"), banner.driver.etaMin)
                    assertEquals(DistanceText.parseMeters(cp.str("driver_distance_text")), banner.driver.distanceMeters)
                }
                else -> fail("unknown checkpoint $expect")
            }
        }
    }

    private fun JsonObject.str(key: String): String = (this[key] as JsonPrimitive).content
    private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long = (this[key] as JsonPrimitive).long
    private fun JsonObject.int(key: String): Int = (this[key] as JsonPrimitive).int
}
