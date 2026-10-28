package app.ridemate.integration

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.AppGraph
import app.ridemate.R
import app.ridemate.compare.ComparisonActivity
import app.ridemate.config.AssetBundledConfig
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.state.Protocol
import app.ridemate.core.state.RideEvent
import app.ridemate.core.state.TapId
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.demo.DemoStartResult
import app.ridemate.demo.DemoTrackChoice
import app.ridemate.launch.LaunchRequest
import app.ridemate.notify.ActionTarget
import app.ridemate.notify.GuardianUiState
import app.ridemate.notify.LegUi
import app.ridemate.ondc.OndcResult
import app.ridemate.onboarding.SignInChoice
import app.ridemate.session.GuardianRuntime
import app.ridemate.session.GuardianSessionApi
import app.ridemate.session.GuardianStatus
import app.ridemate.session.SessionSpec
import app.ridemate.testing.TestData
import app.ridemate.testing.TestRideMateApp
import app.ridemate.trip.TripPlace
import app.ridemate.trip.TripPlan
import app.ridemate.trip.VehicleChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Integration seams that only show up when the areas run together:
 * - "Read fares" reads the app RideMate opened last (the guardian's notion that "Booked here" uses too), not the
 *   first app of the trip;
 * - a Check screen on a booked leg still gives the gate the trip distance (₹/km band);
 * - Try the demo never replaces a live session that still looks after a real booking;
 * - a Compare tap that the guardian refuses (I8) opens no ride app and starts no network search;
 * - Google sign-in is offered once more at the first "Read fares" that falls back to typing (the `signin` screen is
 *   reachable from the app, not only from tests).
 */
@RunWith(AndroidJUnit4::class)
class IntegrationGuardsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp
    private lateinit var graph: AppGraph
    private lateinit var runtime: GuardianRuntime

    @Before
    fun setUp() {
        app.fakes.bundled = AssetBundledConfig(context.assets).load()
        graph = app.graph
        runtime = graph.guardianRuntime
        runtime.start()
        settle()
    }

    @Test
    fun `Read fares reads the app RideMate opened last, not the first app of the trip`() {
        runtime.startSession(liveSpec(SESSION))
        settle()
        val uber = GuardianRuntime.legIdOf(SESSION, Provider.UBER)
        val ola = GuardianRuntime.legIdOf(SESSION, Provider.OLA)

        runtime.markOpenedLeg(uber)
        settle()
        assertEquals(Provider.UBER, activeLeg()?.provider)

        // The user tapped Book / refresh for Ola on the list: Ola's app is on screen now.
        runtime.markOpenedLeg(ola)
        settle()
        val leg = activeLeg()
        assertEquals(Provider.OLA, leg?.provider)
        assertEquals(ola, leg?.legId)
        assertEquals(SESSION, leg?.sessionId)
        assertNotNull("the trip distance still reaches the gate (₹/km band)", leg?.tripKm)

        runtime.markOpenedLeg(uber)
        settle()
        assertEquals(Provider.UBER, activeLeg()?.provider)
    }

    @Test
    fun `a Check screen on a booked leg still gets the trip distance for the gate's per-km band`() {
        runtime.startSession(liveSpec(SESSION))
        settle()
        val expected = runBlocking { graph.fareReading.activeComparison.current()?.tripKm }
        assertNotNull(expected)
        for (provider in listOf(Provider.UBER, Provider.OLA)) {
            runtime.onUserAction(UserAction.BookedHere(GuardianRuntime.legIdOf(SESSION, provider), TapId("tap-$provider")))
        }
        settle()
        assertNull("every leg is booked: no price screen left to read", activeLeg())
        assertEquals(expected!!, runBlocking { graph.sessionTripKm() }!!, 1e-9)
    }

    @Test
    fun `Try the demo never replaces a live session that still looks after a booking`() {
        runtime.startSession(liveSpec(SESSION))
        settle()
        runtime.onUserAction(UserAction.BookedHere(GuardianRuntime.legIdOf(SESSION, Provider.UBER), TapId("tap-book")))
        settle()
        assertTrue(runtime.uiState.value.legs.single { it.provider == Provider.UBER }.booked)

        assertEquals(DemoStartResult.SESSION_BUSY, graph.demoController.start(DemoTrackChoice.MAIN))
        settle()
        val status = runtime.status.value
        assertEquals("the live session is still the guardian's", SESSION, status.sessionId)
        assertTrue(status.sessionOpen)
        assertFalse(status.demo)
    }

    @Test
    fun `the demo waits only for live bookings that are still running`() {
        val open = GuardianStatus(restored = true, sessionId = SESSION, sessionOpen = true)
        fun ui(vararg legs: LegUi) = GuardianUiState(sessionId = SESSION, legs = legs.toList())
        fun leg(state: RideState, booked: Boolean = true, dismissed: Boolean = false) =
            LegUi("$SESSION:uber", Provider.UBER, state, 1, LegRole.NONE, dismissed = dismissed, booked = booked, endedByApp = false)

        assertFalse("comparing, nothing booked", GuardianDemoHost.liveBookingsGuarded(open, ui(leg(RideState.OPENED, booked = false))))
        assertTrue("searching", GuardianDemoHost.liveBookingsGuarded(open, ui(leg(RideState.SEARCHING))))
        assertTrue("riding", GuardianDemoHost.liveBookingsGuarded(open, ui(leg(RideState.ASSIGNED))))
        assertFalse("the app ended it", GuardianDemoHost.liveBookingsGuarded(open, ui(leg(RideState.SEARCH_EXPIRED))))
        assertFalse("dismissed by the user", GuardianDemoHost.liveBookingsGuarded(open, ui(leg(RideState.SEARCHING, dismissed = true))))
        assertTrue("I8", GuardianDemoHost.liveBookingsGuarded(open.copy(needsForegroundService = true), ui()))
        assertFalse("a demo session", GuardianDemoHost.liveBookingsGuarded(open.copy(demo = true), ui(leg(RideState.SEARCHING))))
        assertFalse("no session", GuardianDemoHost.liveBookingsGuarded(GuardianStatus(restored = true), ui()))
    }

    @Test
    fun `a Compare the guardian refuses (I8) opens no app and starts no network search`() {
        val guardian = RecordingGuardian(
            GuardianStatus(restored = true, sessionId = "old", sessionOpen = true, needsForegroundService = true, pendingExtras = 1),
        )
        val opened = mutableListOf<LaunchRequest>()
        val searched = mutableListOf<String>()
        val starter = ComparisonStarter(
            guardian = guardian,
            launcher = RouteLauncher { request -> opened += request; null },
            network = NetworkSearch { _, legId, _, _ -> searched += legId; OndcResult.Ok },
            keepSearchingDefault = { false },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val plan = TripPlan(
            pickup = TripPlace("Pickup", LatLng(26.1445, 91.7362)),
            drop = TripPlace("Drop", LatLng(26.1158, 91.7086)),
            apps = listOf(Provider.UBER, Provider.OLA),
            vehicles = setOf(VehicleChoice.AUTO),
            includeOndc = true,
        )

        assertNull(starter.start(plan))
        assertEquals("the guardian still gets the start, refuses it and says why", 1, guardian.started.size)
        assertTrue("no ride app opened for a session that won't exist", opened.isEmpty())
        assertTrue("no network search either", searched.isEmpty())

        // Nothing left to cancel: the comparison starts, the first app opens with its route, ONDC searches.
        guardian.status.value = GuardianStatus(restored = true, sessionId = "old", sessionOpen = true)
        val id = starter.start(plan)
        assertNotNull(id)
        assertEquals(listOf(Provider.UBER), opened.map { it.provider })
        assertEquals(GuardianRuntime.legIdOf(id!!, Provider.UBER), opened.single().legId)
        assertEquals(listOf(GuardianRuntime.legIdOf(id, Provider.ONDC)), searched)
    }

    @Test
    fun `the first Read fares that falls back to typing offers Google sign-in once`() {
        val store = graph.appShell.onboardingStore
        store.chooseSignIn(SignInChoice.MANUAL)
        runBlocking {
            graph.db.sessionDao().insert(TestData.session("s1"))
            graph.db.legDao().upsert(TestData.leg("l1", "s1", Provider.RAPIDO, RideState.OPENED))
        }
        val google = context.getString(R.string.onb_signin_google)
        val skip = context.getString(R.string.onb_signin_skip)
        val form = context.getString(R.string.compare_manual_title)

        ActivityScenario.launch<ComparisonActivity>(ComparisonActivity.intent(context, "s1", Provider.RAPIDO)).use {
            compose.onNode(hasText(google) and hasClickAction()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(skip).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText(form).assertIsDisplayed()
        }
        assertTrue("offered once", store.answers.value.signInReoffered)

        // The next manual read goes straight to the price form.
        ActivityScenario.launch<ComparisonActivity>(ComparisonActivity.intent(context, "s1", Provider.RAPIDO)).use {
            compose.onNodeWithText(form).assertIsDisplayed()
            compose.onAllNodes(hasText(google) and hasClickAction()).assertCountEquals(0)
        }
        // The list opened without a manual read never offers it.
        store.clear()
        store.chooseSignIn(SignInChoice.MANUAL)
        ActivityScenario.launch<ComparisonActivity>(ComparisonActivity.intent(context, "s1")).use {
            compose.waitForIdle()
            compose.onAllNodes(hasText(google) and hasClickAction()).assertCountEquals(0)
        }
    }

    private fun activeLeg() = runBlocking { graph.fareReading.activeComparison.current() }

    private fun liveSpec(sessionId: String) = SessionSpec(
        pickup = Place("Pickup", LatLng(26.1445, 91.7362)),
        drop = Place("Drop", LatLng(26.1158, 91.7086)),
        providers = listOf(Provider.UBER, Provider.OLA),
        vehicleClasses = listOf(VehicleClass.AUTO),
        keepSearching = false,
        sessionId = sessionId,
    )

    /** Lets the guardian thread, its storage writer and the main looper run until nothing is left to do. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            val done = CoroutineScope(Dispatchers.Default).async { withTimeout(20_000) { runtime.awaitIdle() } }
            while (!done.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
            runBlocking { done.await() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A guardian that records starts and reports the [status] a test sets. */
    private class RecordingGuardian(status: GuardianStatus) : GuardianSessionApi {
        override val uiState = MutableStateFlow(GuardianUiState.EMPTY)
        override val status = MutableStateFlow(status)
        val started = mutableListOf<SessionSpec>()

        override fun startSession(spec: SessionSpec) {
            started += spec
        }

        override fun startDemoSession(spec: SessionSpec) = Unit
        override fun legIdFor(provider: Provider): String? = null
        override fun onRideEvent(legId: String, event: RideEvent) = Unit
        override fun onDemoPost(post: PostSnapshot) = Unit
        override fun onDemoRemoval(removal: PostRemoval) = Unit
        override fun onProtocolEvent(legId: String, event: Protocol) = Unit
        override fun onUserAction(action: UserAction) = Unit
        override fun perform(target: ActionTarget) = Unit
        override fun requestStop() = Unit
    }

    private companion object {
        const val SESSION = "s-live"
        const val SETTLE_ROUNDS = 3
    }
}
