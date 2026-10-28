package app.ridemate.compare

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.format.IstTime
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class ComparisonScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val seenAt = 1_793_000_000_000L
    private val refreshed = mutableListOf<Provider>()
    private val booked = mutableListOf<Provider>()
    private val bookedHere = mutableListOf<Provider>()
    private val typed = mutableListOf<Triple<Provider, VehicleClass, String>>()

    private val callbacks = ComparisonCallbacks(
        onBook = { booked += it },
        onBookedHere = { bookedHere += it },
        onRefresh = { refreshed += it },
        onSubmitManual = { p, c, text, done -> typed += Triple(p, c, text); done(null) },
    )

    private fun verified(provider: Provider, cls: VehicleClass, label: String, price: Int, eta: Int? = 3) = GatedRow(
        provider, cls, label, labelFromOcr = true, price = price..price, status = QuoteStatus.VERIFIED, eta = eta,
        etaSource = if (eta == null) EtaSource.NONE else EtaSource.VERIFIED, seenAtMs = seenAt,
    )

    private val rows = listOf(
        verified(Provider.UBER, VehicleClass.AUTO, "Uber Auto", 120),
        GatedRow(Provider.OLA, VehicleClass.AUTO, "Ola Auto", true, null, QuoteStatus.UNREADABLE, null, EtaSource.NONE, seenAt),
        // Cheaper than Uber, but typed by the user: never "cheapest".
        GatedRow.userEntered(Provider.RAPIDO, VehicleClass.AUTO, 90..90, seenAt),
        verified(Provider.RAPIDO, VehicleClass.BIKE, "Bike Taxi", 60, eta = null),
        GatedRow.network(VehicleClass.AUTO, "Demo Seller Autos", 95, 4, seenAt),
    )

    private val data = ComparisonData(
        sessionId = "s1",
        legs = listOf(
            TestData.leg("l1", "s1", Provider.UBER, RideState.QUOTED),
            TestData.leg("l2", "s1", Provider.OLA, RideState.QUOTED),
            TestData.leg("l3", "s1", Provider.RAPIDO, RideState.QUOTED),
            TestData.leg("l4", "s1", Provider.ONDC, RideState.SEARCHING_NET),
        ),
        rows = rows,
    )

    private fun show(nowMs: Long, manual: ManualPrompt? = null) {
        compose.setContent { ComparisonScreen(data, nowMs, manual, callbacks) }
    }

    private fun rowTag(provider: Provider, cls: VehicleClass) = ComparisonTags.row(provider, cls)

    private val heading = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading)

    @Test
    fun `only VERIFIED rows are marked cheapest, with a word and not colour alone`() {
        show(seenAt + 30_000)

        compose.onAllNodesWithText("cheapest").assertCountEquals(2)
        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO), useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("cheapest")))
        compose.onNodeWithTag(rowTag(Provider.RAPIDO, VehicleClass.BIKE), useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("cheapest")))
        compose.onNodeWithTag(rowTag(Provider.RAPIDO, VehicleClass.AUTO), useUnmergedTree = true)
            .assert(!hasAnyDescendant(hasText("cheapest")))
            .assert(hasAnyDescendant(hasText("entered by you")))
            .assert(hasAnyDescendant(hasText("₹90")))
        compose.onNodeWithTag(rowTag(Provider.ONDC, VehicleClass.AUTO), useUnmergedTree = true)
            .assert(!hasAnyDescendant(hasText("cheapest")))
            .assert(hasAnyDescendant(hasText("network quote")))
    }

    @Test
    fun `an unreadable row says so and shows no number`() {
        show(seenAt + 30_000)

        compose.onNodeWithTag(rowTag(Provider.OLA, VehicleClass.AUTO), useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Couldn't read · check in app")))
            .assert(!hasAnyDescendant(hasText("₹", substring = true)))
            .assert(!hasAnyDescendant(hasText("Pickup in", substring = true)))
    }

    @Test
    fun `rows are grouped by vehicle type and show ETA or ETA see in app`() {
        show(seenAt + 30_000)

        compose.onNode(hasText("Auto") and heading).assertIsDisplayed()
        compose.onNode(hasText("Bike") and heading).assertIsDisplayed()
        // An ONDC row names its protocol provider; a typed row shows the class name.
        compose.onNodeWithTag(rowTag(Provider.ONDC, VehicleClass.AUTO), useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Demo Seller Autos")))
        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO), useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Pickup in 3 min")))
        compose.onNodeWithTag(rowTag(Provider.RAPIDO, VehicleClass.BIKE), useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("ETA: see in app")))
    }

    @Test
    fun `fresh rows say when and where they were seen`() {
        show(seenAt + 59_000)

        val time = IstTime.seenAt(seenAt)
        compose.onNodeWithText("seen in Uber at $time").assertIsDisplayed()
        compose.onAllNodesWithText("old, tap to refresh").assertCountEquals(0)
    }

    @Test
    fun `after 60 s every row greys out with old, tap to refresh, and a tap reopens the app`() {
        show(seenAt + 60_000)

        compose.onAllNodesWithText("old, tap to refresh").assertCountEquals(rows.size)
        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO)).assert(hasClickAction()).performClick()
        assertEquals(listOf(Provider.UBER), refreshed)
        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO))
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "old, tap to refresh"))
    }

    @Test
    fun `the ticker greys rows out at 60 s without any other change`() {
        val clock = FakeClock(wall = seenAt + 55_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { ComparisonScreen(data, rememberWallClock(clock), null, callbacks) }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithText("old, tap to refresh").assertCountEquals(0)

        clock.advance(6_000)
        compose.mainClock.advanceTimeBy(1_100)

        compose.onAllNodesWithText("old, tap to refresh").assertCountEquals(rows.size)
    }

    @Test
    fun `Book opens the app and Booked here reports the app`() {
        show(seenAt + 30_000)

        compose.onNodeWithText("Book in Uber").performClick()
        compose.onNodeWithText("Booked in Rapido").performClick()

        assertEquals(listOf(Provider.UBER), booked)
        assertEquals(listOf(Provider.RAPIDO), bookedHere)
        // ONDC rows are booked inside RideMate: no "Book in" button and no "Booked in" for the network.
        compose.onAllNodesWithText("Book in Open network (ONDC)").assertCountEquals(0)
        compose.onAllNodesWithText("Booked in Open network (ONDC)").assertCountEquals(0)
    }

    @Test
    fun `manual mode shows the resume time and saves a typed price as entered by you`() {
        show(seenAt, ManualPrompt("AI reading resumes at 1:30 pm (IST). You can type the price meanwhile.", Provider.OLA, listOf(VehicleClass.AUTO)))

        compose.onNodeWithText("AI reading resumes at 1:30 pm (IST). You can type the price meanwhile.").assertIsDisplayed()
        compose.onNodeWithTag(ComparisonTags.PRICE_FIELD).performTextInput("₹105")
        compose.onNodeWithText("Save price").performClick()

        assertEquals(listOf(Triple(Provider.OLA, VehicleClass.AUTO, "₹105")), typed)
        compose.onNodeWithText("Saved. It shows as \"entered by you\".").assertIsDisplayed()
    }

    @Test
    fun `without a guardian bound there is no Booked here button`() {
        compose.setContent { ComparisonScreen(data, seenAt, null, callbacks.copy(onBookedHere = null)) }
        compose.onAllNodesWithText("Booked in Uber").assertCountEquals(0)
    }
}
