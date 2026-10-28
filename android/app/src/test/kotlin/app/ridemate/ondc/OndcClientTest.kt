@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.ondc

import app.ridemate.core.gate.ComparisonList
import app.ridemate.core.gate.GateText
import app.ridemate.core.guardian.FeeRules
import app.ridemate.core.guardian.Guardian
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.OndcCommandKind
import app.ridemate.core.guardian.RuleBasedFeeEstimator
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.invariants.Invariants
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.state.Protocol
import app.ridemate.core.state.ProtocolKind
import app.ridemate.core.state.TapId
import app.ridemate.demo.SchedulerAppClock
import app.ridemate.demo.SchedulerCoreClock
import app.ridemate.net.ApiResult
import app.ridemate.net.OndcSearchResponse
import app.ridemate.testing.ProgrammableApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ONDC buyer flow against a scripted backend and the real core guardian. */
class OndcClientTest {

    private class Env(val test: TestScope) {
        val api = ProgrammableApiClient()
        val source = FakeOndcTxnSource()
        val enabled = MutableStateFlow(true)
        val client = OndcClient(api, source, enabled, SchedulerAppClock(test.testScheduler), test.backgroundScope)
        val guardian = Guardian(SchedulerCoreClock(test.testScheduler), RuleBasedFeeEstimator(FeeRules()))
        val effects = mutableListOf<GuardianEffect>()
        val legId = "s1:ondc"
        val protocol = mutableListOf<Protocol>()
        private var taps = 0
        private val jobs = mutableListOf<Job>()

        init {
            guardian.effectListener = { effect ->
                effects += effect
                // The session area runs every ONDC command through the client.
                if (effect is GuardianEffect.OndcCommand) jobs += test.backgroundScope.launch { results += client.execute(effect) }
            }
            guardian.startSession("s1", listOf(Provider.ONDC))
            client.bind { leg, event ->
                protocol += event
                guardian.onProtocolEvent(leg, event)
            }
            api.ondcSearch = { ApiResult.Ok(OndcSearchResponse("t1")) }
        }

        val results = mutableListOf<OndcResult>()
        val leg get() = guardian.state.legs.getValue(legId).leg

        fun tap() = TapId("tap-${++taps}")
        fun run() = test.testScheduler.runCurrent()
        fun advance(ms: Long) {
            test.testScheduler.advanceTimeBy(ms)
            test.testScheduler.runCurrent()
        }

        suspend fun search(): OndcResult = client.search("s1", legId, LatLng(26.14, 91.73), LatLng(26.11, 91.63))

        /** A seller that answers each step with its Firestore write. */
        fun cooperativeSeller(driver: Map<String, Any?>? = mapOf("eta_min" to 4L, "distance_text" to "1.1 km"), cancelFee: Long = 30) {
            api.afterOndcStep = { step ->
                when (step) {
                    "select" -> source.write("t1", OndcDocs.doc("ON_SELECT", selected = "auto-1"))
                    "init" -> source.write("t1", OndcDocs.doc("INIT", selected = "auto-1"))
                    "confirm" -> source.write("t1", OndcDocs.doc("CONFIRMED", selected = "auto-1", driver = driver))
                    "cancel.soft" -> source.write("t1", OndcDocs.doc("SOFT_CANCEL", selected = "auto-1", driver = driver, fee = mapOf("amount" to cancelFee, "currency" to "INR")))
                    "cancel.confirm" -> source.write("t1", OndcDocs.doc("CANCEL_CONFIRMED", selected = "auto-1", fee = mapOf("amount" to cancelFee, "currency" to "INR")))
                }
            }
        }

        fun ondcCalls() = api.calls.filter { it.startsWith("ondc.") }
    }

    @Test
    fun `search shows seller quotes as network rows labelled as the demo seller`() = runTest {
        val e = Env(this)
        assertEquals(OndcResult.Ok, e.search())
        assertTrue(e.source.isListening("t1"))
        assertEquals(OndcPhase.SEARCHING, e.client.view.value.phase)
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET", quotes = emptyList()))
        assertEquals(OndcPhase.SEARCHING, e.client.view.value.phase)
        assertEquals(RideState.SEARCHING_NET, e.leg.state)

        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        val view = e.client.view.value
        assertEquals(OndcPhase.QUOTES, view.phase)
        assertTrue(view.demoSeller)
        assertEquals(listOf("auto-1", "cab-1"), view.quotes.map { it.itemId })
        val auto = view.quotes.first().row
        assertEquals(QuoteStatus.NETWORK, auto.status)
        assertEquals(Provider.ONDC, auto.provider)
        assertEquals(92..92, auto.price)
        assertEquals(4, auto.eta)
        assertEquals(EtaSource.NETWORK, auto.etaSource)
        assertEquals("Demo Seller Autos", auto.displayLabel)
        assertTrue(view.quotes.all { it.demoSeller })
        // In the comparison list a network row says "network quote" and is never flagged cheapest (not VERIFIED).
        val entry = ComparisonList.build(listOf(auto), auto.seenAtMs).groups.single { it.vehicleClass == VehicleClass.AUTO }.entries.single()
        assertEquals(GateText.NETWORK_QUOTE, entry.statusTextKey)
        assertFalse(entry.cheapest)
        // The same snapshot again delivers nothing new, and the quotes keep the time they were first seen.
        val delivered = e.protocol.size
        e.advance(5_000)
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        assertEquals(delivered, e.protocol.size)
        assertEquals(auto.seenAtMs, e.client.view.value.quotes.first().row.seenAtMs)
    }

    @Test
    fun `book after the user's tap - select, init, confirm, each after the seller's callback, carrying the tap (I1)`() = runTest {
        val e = Env(this)
        e.cooperativeSeller()
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        val tap = e.tap()
        e.guardian.onUserAction(UserAction.OndcBook(e.legId, "auto-1", tap))
        e.run()
        assertEquals(listOf("ondc.search", "ondc.select:t1:auto-1", "ondc.init:t1", "ondc.confirm:t1:${tap.value}"), e.ondcCalls())
        assertEquals(listOf<OndcResult>(OndcResult.Ok), e.results)
        assertEquals(RideState.ASSIGNED, e.leg.state)
        assertEquals(4, e.leg.driver.etaMin)
        assertEquals(EtaSource.NETWORK, e.leg.driver.etaSource)
        assertEquals(1100, e.leg.driver.distanceMeters)
        assertEquals(tap, e.protocol.single { it.kind == ProtocolKind.CONFIRMED }.tapId)
        val view = e.client.view.value
        assertEquals(OndcPhase.BOOKED, view.phase)
        assertEquals("auto-1", view.selectedItemId)
        assertEquals(4, view.driverEtaMin)
        assertTrue(Invariants.checkGuardian(e.guardian).ok)
    }

    @Test
    fun `cancel shows the network's fee before the user confirms, and on_cancel is evidence rung 1`() = runTest {
        val e = Env(this)
        e.cooperativeSeller(cancelFee = 30)
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        e.guardian.onUserAction(UserAction.OndcBook(e.legId, "auto-1", e.tap()))
        e.run()

        // Nothing can be confirmed before a fee was quoted and shown.
        assertEquals(OndcResult.FeeNotShown, e.client.confirmCancel(e.legId, e.tap()))
        assertFalse(e.api.calls.any { it.startsWith("ondc.cancel") })

        // Holding the seller back shows the in-between state.
        e.api.afterOndcStep = {}
        e.guardian.onUserAction(UserAction.OndcCancel(e.legId, e.tap()))
        e.run()
        assertEquals(listOf("ondc.cancel.soft"), e.api.calls.filter { it.startsWith("ondc.cancel") }.map { it.substringBefore(':') })
        assertEquals(OndcPhase.CANCEL_REQUESTED, e.client.view.value.phase)
        assertNull(e.client.view.value.feeInr)
        assertEquals("the fee is not on screen yet", OndcResult.FeeNotShown, e.client.confirmCancel(e.legId, e.tap()))

        e.source.write("t1", OndcDocs.doc("SOFT_CANCEL", selected = "auto-1", driver = mapOf("eta_min" to 4L, "distance_text" to "1.1 km"), fee = mapOf("amount" to 30L, "currency" to "INR")))
        assertEquals(RideState.SOFT_CANCEL, e.leg.state)
        assertEquals(30, e.leg.ondcFeeInr)
        assertEquals(30, e.effects.filterIsInstance<GuardianEffect.OndcCancelFee>().single().feeInr)
        assertEquals(OndcPhase.CANCEL_FEE_SHOWN, e.client.view.value.phase)
        assertEquals(30, e.client.view.value.feeInr)
        assertEquals("still nothing cancelled", 1, e.api.calls.count { it.startsWith("ondc.cancel") })

        // The user's Confirm tap → CONFIRM_CANCEL → confirm=true → on_cancel.
        e.cooperativeSeller(cancelFee = 30)
        e.guardian.onUserAction(UserAction.OndcConfirmCancel(e.legId, e.tap()))
        e.run()
        assertEquals(listOf("ondc.cancel.soft", "ondc.cancel.confirm"), e.api.calls.filter { it.startsWith("ondc.cancel") }.map { it.substringBefore(':') })
        assertEquals(RideState.CANCEL_CONFIRMED, e.leg.state)
        assertEquals(EvidenceRung.ONDC_ON_CANCEL, e.leg.confirmedBy)
        assertEquals(OndcPhase.CANCELLED, e.client.view.value.phase)
        assertTrue(Invariants.checkGuardian(e.guardian).ok)
        val feeShownAt = e.effects.indexOfFirst { it is GuardianEffect.OndcCancelFee }
        val confirmAt = e.effects.indexOfFirst { it is GuardianEffect.OndcCommand && it.command == OndcCommandKind.CONFIRM_CANCEL }
        assertTrue("fee shown before the confirm command", feeShownAt in 0 until confirmAt)
    }

    @Test
    fun `keeping the ride after seeing the fee cancels nothing, and a new request is quoted and shown again`() = runTest {
        val e = Env(this)
        e.cooperativeSeller(cancelFee = 30)
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        e.guardian.onUserAction(UserAction.OndcBook(e.legId, "auto-1", e.tap()))
        e.run()
        e.guardian.onUserAction(UserAction.OndcCancel(e.legId, e.tap()))
        e.run()
        assertEquals(OndcPhase.CANCEL_FEE_SHOWN, e.client.view.value.phase)

        e.guardian.onUserAction(UserAction.OndcKeepRide(e.legId, e.tap()))
        e.client.keepRide(e.legId)
        assertEquals(RideState.ASSIGNED, e.leg.state)
        assertEquals(OndcPhase.BOOKED, e.client.view.value.phase)
        assertNull(e.client.view.value.feeInr)
        assertEquals(OndcResult.FeeNotShown, e.client.confirmCancel(e.legId, e.tap()))

        // Same fee, second request: shown again before any confirm.
        e.guardian.onUserAction(UserAction.OndcCancel(e.legId, e.tap()))
        e.run()
        assertEquals(RideState.SOFT_CANCEL, e.leg.state)
        assertEquals(2, e.effects.filterIsInstance<GuardianEffect.OndcCancelFee>().size)
        assertEquals(OndcPhase.CANCEL_FEE_SHOWN, e.client.view.value.phase)
    }

    @Test
    fun `free cancellation is shown as fee zero before confirming`() = runTest {
        val e = Env(this)
        e.cooperativeSeller(cancelFee = 0)
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        e.guardian.onUserAction(UserAction.OndcBook(e.legId, "auto-1", e.tap()))
        e.run()
        e.guardian.onUserAction(UserAction.OndcCancel(e.legId, e.tap()))
        e.run()
        assertEquals(0, e.client.view.value.feeInr)
        assertEquals(0, e.effects.filterIsInstance<GuardianEffect.OndcCancelFee>().single().feeInr)
    }

    @Test
    fun `unavailable - each failure becomes the ONDC unavailable row with its reason`() = runTest {
        val cases = listOf(
            ApiResult.NotConfigured to OndcUnavailableReason.NOT_CONFIGURED,
            ApiResult.GoogleRequired to OndcUnavailableReason.SIGN_IN_NEEDED,
            ApiResult.AuthNeeded to OndcUnavailableReason.SIGN_IN_NEEDED,
            ApiResult.Offline to OndcUnavailableReason.OFFLINE,
            ApiResult.Timeout to OndcUnavailableReason.TIMEOUT,
            ApiResult.Http(503, "ondc_unavailable") to OndcUnavailableReason.SERVER,
            ApiResult.RateLimited(30) to OndcUnavailableReason.SERVER,
        )
        for ((result, reason) in cases) {
            val e = Env(this)
            e.api.ondcSearch = { result }
            assertEquals(OndcResult.Unavailable(reason), e.search())
            assertEquals(OndcPhase.UNAVAILABLE, e.client.view.value.phase)
            assertEquals(reason, e.client.view.value.unavailable)
            assertTrue(e.source.listened.isEmpty())
        }
    }

    @Test
    fun `switched off - no search and no booking, but a booked ride can still be cancelled`() = runTest {
        val off = Env(this)
        off.enabled.value = false
        assertEquals(OndcResult.Unavailable(OndcUnavailableReason.SWITCHED_OFF), off.search())
        assertTrue(off.api.calls.isEmpty())

        val e = Env(this)
        e.cooperativeSeller()
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        e.enabled.value = false
        assertEquals(OndcResult.Unavailable(OndcUnavailableReason.SWITCHED_OFF), e.client.book(e.legId, "auto-1", e.tap()))
        e.enabled.value = true
        e.guardian.onUserAction(UserAction.OndcBook(e.legId, "auto-1", e.tap()))
        e.run()
        e.enabled.value = false
        e.guardian.onUserAction(UserAction.OndcCancel(e.legId, e.tap()))
        e.run()
        assertEquals(RideState.SOFT_CANCEL, e.leg.state)
    }

    @Test
    fun `listener failures and quota show the unavailable row`() = runTest {
        val e = Env(this)
        e.search()
        e.source.fail("t1", OndcListenFailure.DENIED)
        assertEquals(OndcPhase.UNAVAILABLE, e.client.view.value.phase)
        assertEquals(OndcUnavailableReason.LISTENER_FAILED, e.client.view.value.unavailable)
        e.source.fail("t1", OndcListenFailure.QUOTA)
        assertEquals(OndcUnavailableReason.QUOTA, e.client.view.value.unavailable)
        // A later good snapshot recovers.
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        assertEquals(OndcPhase.QUOTES, e.client.view.value.phase)
        assertNull(e.client.view.value.unavailable)
    }

    @Test
    fun `no quotes for 30 s says no rides on the network`() = runTest {
        val e = Env(this)
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET", quotes = emptyList()))
        e.advance(OndcClient.SEARCH_TIMEOUT_MS)
        assertEquals(OndcPhase.NO_RESULTS, e.client.view.value.phase)
    }

    @Test
    fun `a seller that never calls back times out the booking step`() = runTest {
        val e = Env(this)
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        val job = launch { e.results += e.client.book(e.legId, "auto-1", e.tap()) }
        e.advance(OndcClient.STEP_TIMEOUT_MS + 1)
        job.join()
        assertEquals(listOf<OndcResult>(OndcResult.Timeout), e.results)
        assertEquals(OndcUnavailableReason.TIMEOUT, e.client.view.value.unavailable)
        assertFalse("init never sent", e.api.calls.any { it.startsWith("ondc.init") })
    }

    @Test
    fun `a seller failure during booking fails the leg`() = runTest {
        val e = Env(this)
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        e.api.afterOndcStep = { if (it == "select") e.source.write("t1", OndcDocs.doc("FAILED")) }
        e.guardian.onUserAction(UserAction.OndcBook(e.legId, "auto-1", e.tap()))
        e.run()
        assertEquals(listOf<OndcResult>(OndcResult.Failed), e.results)
        assertEquals(RideState.SEARCH_EXPIRED, e.leg.state)
        assertEquals(OndcPhase.FAILED, e.client.view.value.phase)
    }

    @Test
    fun `a jump from searching straight to arriving still walks the leg through every step`() = runTest {
        val e = Env(this)
        e.search()
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        e.guardian.onUserAction(UserAction.OndcBook(e.legId, "auto-1", e.tap()))
        e.source.write("t1", OndcDocs.doc("ARRIVING", driver = mapOf("eta_min" to 1L, "distance_text" to "200 m")))
        assertEquals(RideState.ARRIVING, e.leg.state)
        assertEquals(1, e.leg.driver.etaMin)
        assertEquals(OndcPhase.ARRIVING, e.client.view.value.phase)
        e.source.write("t1", OndcDocs.doc("ON_TRIP"))
        e.source.write("t1", OndcDocs.doc("ENDED"))
        assertEquals(RideState.ENDED, e.leg.state)
        assertEquals(OndcPhase.ENDED, e.client.view.value.phase)
    }

    @Test
    fun `guards - unknown item, wrong state, no transaction, busy, stop`() = runTest {
        val e = Env(this)
        assertEquals(OndcResult.NoTransaction, e.client.book(e.legId, "auto-1", e.tap()))
        assertEquals(OndcResult.NoTransaction, e.client.softCancel(e.legId, e.tap()))
        e.search()
        assertEquals("no quotes yet", OndcResult.NoSuchItem, e.client.book(e.legId, "auto-1", e.tap()))
        e.source.write("t1", OndcDocs.doc("SEARCHING_NET"))
        assertEquals(OndcResult.NoSuchItem, e.client.book(e.legId, "bike-9", e.tap()))
        assertEquals(OndcResult.NotAllowed, e.client.softCancel(e.legId, e.tap()))
        assertEquals(OndcResult.NoTransaction, e.client.book("other-leg", "auto-1", e.tap()))
        e.source.write("t1", OndcDocs.doc("CONFIRMED"))
        assertEquals(OndcResult.NotAllowed, e.client.book(e.legId, "auto-1", e.tap()))
        assertEquals("a live booking blocks a new search", OndcResult.Busy, e.search())
        e.client.stop()
        assertEquals(listOf("t1"), e.source.closed)
        assertEquals(OndcPhase.IDLE, e.client.view.value.phase)
    }

    @Test
    fun `protocol events that arrive before the guardian is bound are delivered in order on bind`() = runTest {
        val api = ProgrammableApiClient().also { it.ondcSearch = { ApiResult.Ok(OndcSearchResponse("t7")) } }
        val source = FakeOndcTxnSource()
        val client = OndcClient(api, source, MutableStateFlow(true), SchedulerAppClock(testScheduler), backgroundScope)
        client.search("s", "s:ondc", LatLng(0.0, 0.0), LatLng(1.0, 1.0))
        source.write("t7", OndcDocs.doc("INIT"))
        val got = mutableListOf<ProtocolKind>()
        client.bind { _, event -> got += event.kind }
        assertEquals(listOf(ProtocolKind.SEARCH_STARTED, ProtocolKind.QUOTES, ProtocolKind.SELECTED, ProtocolKind.INITIALIZED), got)
        source.write("t7", OndcDocs.doc("CONFIRMED"))
        assertEquals(ProtocolKind.CONFIRMED, got.last())
    }

    @Test
    fun `status refresh asks the seller`() = runTest {
        val e = Env(this)
        e.search()
        assertEquals(OndcResult.Ok, e.client.refreshStatus(e.legId))
        assertTrue("ondc.status:t1" in e.api.calls)
        e.api.ondcStep = { ApiResult.Offline }
        assertEquals(OndcResult.Unavailable(OndcUnavailableReason.OFFLINE), e.client.refreshStatus(e.legId))
    }
}
