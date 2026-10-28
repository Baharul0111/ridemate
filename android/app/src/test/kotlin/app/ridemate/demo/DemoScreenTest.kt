package app.ridemate.demo

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.gate.DriverReading
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The demo screen: labels, DEMO marking, touch targets, state in words, and the callbacks. */
@RunWith(AndroidJUnit4::class)
class DemoScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = DemoScreenActions(
        start = { calls += "start:${it.name}" },
        leave = { calls += "leave" },
        openApp = { calls += "open:${it.name}" },
        closeApp = { calls += "close" },
        readFares = { calls += "read:${it.name}" },
        bookedHere = { calls += "book:${it.name}" },
        cancelRide = { calls += "cancel:${it.name}" },
        showMe = { calls += "showme:${it.name}" },
        pay = { calls += "pay:${it.name}" },
        dismissNotice = { calls += "dismiss" },
    )

    private fun SemanticsNodeInteraction.touchTarget() = assertHeightIsAtLeast(48.dp)

    @Test
    fun startPanel_offersEachWalkWithALabelledStartButton() {
        compose.setContent { DemoScreenContent(DemoUiState(), actions) }
        compose.onNodeWithText("Try the demo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Demo mode: fictional ride companies, no real bookings and no money").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start the demo: Keep searching").performScrollTo().touchTarget().performClick()
        compose.onNodeWithContentDescription("Start the demo: Book three, keep one").performScrollTo().touchTarget().performClick()
        compose.onNodeWithContentDescription("Start the demo: Driver cancels").performScrollTo().performClick()
        assertEquals(listOf("start:KEEP_SEARCHING", "start:MAIN", "start:REMATCH"), calls)
    }

    @Test
    fun unavailable_saysWhatToDoNext() {
        compose.setContent { DemoScreenContent(DemoUiState(phase = DemoPhase.UNAVAILABLE, unavailable = DemoUnavailable.SESSION_BUSY), actions) }
        compose.onNodeWithText("RideMate is still looking after your ride bookings. Finish or cancel them first, then start the demo.").assertIsDisplayed()
    }

    @Test
    fun bookingStep_showsTheDemoAppWithReadAndBookButtons() {
        val state = runningState().copy(
            app = DemoAppView(Provider.DEMO_KESTREL, DemoAppPurpose.BOOK, DemoScreens.FARE_OPTIONS, "demo/kestrel/fare_options.png"),
        )
        compose.setContent { DemoScreenContent(state, actions) }
        compose.onNodeWithText("Step 1 of 3: read the prices in Kestrel Go · DEMO, then tap Booked here.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kestrel Go · DEMO demo screen: fare options").assertIsDisplayed()
        compose.onNodeWithContentDescription("Read the prices on the Kestrel Go demo screen with two independent readers").performScrollTo().touchTarget().performClick()
        compose.onNodeWithContentDescription("I booked a ride in Kestrel Go (demo)").performScrollTo().touchTarget().performClick()
        assertEquals(listOf("read:DEMO_KESTREL", "book:DEMO_KESTREL"), calls)
    }

    @Test
    fun cancelStep_offersCancelRideAndShowMe_thenWaitsForProof() {
        val app = DemoAppView(Provider.DEMO_TAMARIND, DemoAppPurpose.CANCEL, DemoScreens.CANCEL_FLOW, "demo/tamarind/cancel_flow.png")
        val state = runningState(booked = true).copy(phase = DemoPhase.WATCHING, app = app)
        compose.setContent { DemoScreenContent(state, actions) }
        compose.onNodeWithText("RideMate opened Tamarind Cabs so you can cancel this extra booking. Tap Cancel ride, as you would in the real app.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Tap Cancel ride in Tamarind Cabs (demo), as you would in the real app").performScrollTo().touchTarget().performClick()
        compose.onNodeWithContentDescription("Show me where Cancel ride is in Tamarind Cabs").performScrollTo().touchTarget().performClick()
        compose.onNodeWithContentDescription("Close the demo app and go back to the demo overview").performScrollTo().touchTarget().performClick()
        assertEquals(listOf("cancel:DEMO_TAMARIND", "showme:DEMO_TAMARIND", "close"), calls)
    }

    @Test
    fun pointer_isAnnouncedAndDrawnBesideTheButton() {
        val app = DemoAppView(Provider.DEMO_PEPPER, DemoAppPurpose.CANCEL, DemoScreens.CANCEL_FLOW, "demo/pepper/cancel_flow.png", pointer = listOf(100, 800, 900, 860))
        compose.setContent { DemoScreenContent(runningState(booked = true).copy(phase = DemoPhase.WATCHING, app = app), actions) }
        compose.onNodeWithContentDescription("Arrow pointing at the Cancel ride button").assertExists()
    }

    @Test
    fun rows_stateEverythingInWords_recordedLabel_unreadableWithoutANumber() {
        val now = DEMO_TEST_WALL_BASE
        val verified = GatedRow(Provider.DEMO_PEPPER, VehicleClass.AUTO, "ऑटो", true, 172..172, QuoteStatus.VERIFIED, 3, EtaSource.VERIFIED, now)
        val dearer = GatedRow(Provider.DEMO_PEPPER, VehicleClass.AUTO, "Auto Plus", true, 190..190, QuoteStatus.VERIFIED, null, EtaSource.NONE, now)
        val unreadable = GatedRow(Provider.DEMO_PEPPER, VehicleClass.CAB_PREMIUM, "Prime", true, null, QuoteStatus.UNREADABLE, null, EtaSource.NONE, now)
        val read = DemoReadResult(
            Provider.DEMO_PEPPER, DemoScreens.FARE_OPTIONS, listOf(verified, dearer, unreadable), DriverReading.NONE, ReadSource.RECORDED,
            recordedOnPhone = true, apiFailure = null, issue = DemoReadIssue.NONE, seenAtMs = now, imageSha256 = "x",
        )
        val state = runningState(booked = true).let { s ->
            s.copy(phase = DemoPhase.WATCHING, providers = s.providers.map { if (it.provider == Provider.DEMO_PEPPER) it.copy(read = read) else it })
        }
        compose.setContent { DemoScreenContent(state, actions, nowMs = { now + 1_000 }) }
        compose.onNodeWithText("Pepper Rides · DEMO · recorded").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("RideMate's server couldn't be reached, so the demo used the recorded read bundled in the app.").assertExists()
        compose.onNodeWithContentDescription(
            "ऑटो: ₹172. 3 min, cheapest. seen in Pepper Rides at 10:30:00 AM · Pepper Rides · DEMO",
        ).assertExists()
        compose.onNodeWithContentDescription("Prime: Couldn't read · check in app. seen in Pepper Rides at 10:30:00 AM · Pepper Rides · DEMO")
            .assertExists()
        compose.onAllNodesWithText("cheapest").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("ETA: see in app").assertExists()
    }

    @Test
    fun rows_olderThanAMinute_sayTheyAreOld() {
        val now = DEMO_TEST_WALL_BASE
        val row = GatedRow(Provider.DEMO_KESTREL, VehicleClass.BIKE, "Bike", true, 112..112, QuoteStatus.VERIFIED, 4, EtaSource.VERIFIED, now)
        val read = DemoReadResult(
            Provider.DEMO_KESTREL, DemoScreens.FARE_OPTIONS, listOf(row), DriverReading.NONE, ReadSource.LIVE,
            recordedOnPhone = false, apiFailure = null, issue = DemoReadIssue.NONE, seenAtMs = now, imageSha256 = "x",
        )
        val state = runningState().let { s -> s.copy(providers = s.providers.map { if (it.provider == Provider.DEMO_KESTREL) it.copy(read = read) else it }) }
        compose.setContent { DemoScreenContent(state, actions, nowMs = { now + 61_000 }) }
        compose.onNodeWithText("Kestrel Go · DEMO · Read by Muse Spark Open").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("old · tap Read fares to refresh").assertExists()
    }

    @Test
    fun rows_ofABookedDemoApp_sayTheyWereReadBeforeBooking_notToTapAButtonThatIsGone() {
        val now = DEMO_TEST_WALL_BASE
        val row = GatedRow(Provider.DEMO_KESTREL, VehicleClass.BIKE, "Bike", true, 112..112, QuoteStatus.VERIFIED, 4, EtaSource.VERIFIED, now)
        val read = DemoReadResult(
            Provider.DEMO_KESTREL, DemoScreens.FARE_OPTIONS, listOf(row), DriverReading.NONE, ReadSource.LIVE,
            recordedOnPhone = false, apiFailure = null, issue = DemoReadIssue.NONE, seenAtMs = now, imageSha256 = "x",
        )
        val state = runningState(booked = true).let { s ->
            s.copy(phase = DemoPhase.WATCHING, providers = s.providers.map { if (it.provider == Provider.DEMO_KESTREL) it.copy(read = read) else it })
        }
        compose.setContent { DemoScreenContent(state, actions, nowMs = { now + 61_000 }) }
        compose.onNodeWithText("old · read before you booked").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("old · tap Read fares to refresh").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription(
            "Bike: ₹112. 4 min, cheapest. seen in Kestrel Go at 10:30:00 AM. old · read before you booked · Kestrel Go · DEMO",
        ).assertExists()
    }

    @Test
    fun overview_listsEachDemoAppWithItsStatusAndOpenButton_andOffersPay() {
        val state = runningState(booked = true).let { s ->
            s.copy(
                phase = DemoPhase.WATCHING,
                providers = s.providers.map {
                    when (it.provider) {
                        Provider.DEMO_KESTREL -> it.copy(cancelProven = true, screen = DemoScreens.CANCELLED)
                        Provider.DEMO_TAMARIND -> it.copy(cancelTapped = true)
                        else -> it.copy(screen = DemoScreens.DRIVER_ASSIGNED)
                    }
                },
                progress = s.progress.copy(payOffered = true, payProvider = Provider.DEMO_PEPPER),
            )
        }
        compose.setContent { DemoScreenContent(state, actions) }
        compose.onNodeWithText("Cancelled, proven").assertExists()
        compose.onNodeWithText("Cancel tapped, waiting for proof").assertExists()
        compose.onNodeWithText("Driver assigned").assertExists()
        compose.onNodeWithContentDescription("Open the Pepper Rides · DEMO demo app").performScrollTo().touchTarget().performClick()
        compose.onNodeWithContentDescription("Open Pepper Rides to pay (demo: no money moves)").performScrollTo().touchTarget().performClick()
        compose.onNodeWithContentDescription("Leave the demo. Your real rides are not affected.").performScrollTo().touchTarget().performClick()
        assertEquals(listOf("open:DEMO_PEPPER", "pay:DEMO_PEPPER", "leave"), calls)
    }

    @Test
    fun progress_andNotice_areInWords() {
        val state = runningState(booked = true).copy(
            phase = DemoPhase.WATCHING,
            notice = DemoNotice.SHOW_ME_UNAVAILABLE,
            progress = DemoProgress(pricesRead = 3, booked = 3, toBook = 3, driverFound = true, reminders = 2),
        )
        compose.setContent { DemoScreenContent(state, actions) }
        compose.onNodeWithText("Show me needs Google sign-in and AI reading. Look for the large Cancel ride button instead.").assertIsDisplayed()
        compose.onNodeWithText("OK").performScrollTo().touchTarget().performClick()
        compose.onNodeWithText("Driver found: Done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reminders sent: 2: Done").assertExists()
        compose.onNodeWithText("Cancel guide shown: Not yet").assertExists()
        assertEquals(listOf("dismiss"), calls)
    }

    private fun runningState(booked: Boolean = false) = DemoUiState(
        phase = DemoPhase.BOOKING,
        track = DemoTrackChoice.MAIN,
        sessionId = "demo-1",
        providers = Provider.DEMO_APPS.map { DemoProviderView(it, "demo-1:${it.wireId}", DemoScreens.FARE_OPTIONS, opened = booked, booked = booked) },
        progress = DemoProgress(toBook = 3),
    )
}
