package app.ridemate.compare

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.ridemate.ui.components.RmTags
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

    private fun card(provider: Provider, cls: VehicleClass) = compose.onNodeWithTag(rowTag(provider, cls), useUnmergedTree = true)

    @Test
    fun `only VERIFIED rows are marked cheapest, with a word and not colour alone`() {
        show(seenAt + 30_000)

        compose.onAllNodesWithTag(RmTags.FARE_CHEAPEST, useUnmergedTree = true).assertCountEquals(2)
        card(Provider.UBER, VehicleClass.AUTO).assert(hasAnyDescendant(hasText("Cheapest")))
        card(Provider.RAPIDO, VehicleClass.BIKE).assert(hasAnyDescendant(hasText("Cheapest")))
        card(Provider.RAPIDO, VehicleClass.AUTO)
            .assert(!hasAnyDescendant(hasText("Cheapest")))
            .assert(hasAnyDescendant(hasText("You typed")))
            .assert(hasAnyDescendant(hasText("₹90")))
        card(Provider.ONDC, VehicleClass.AUTO)
            .assert(!hasAnyDescendant(hasText("Cheapest")))
            .assert(hasAnyDescendant(hasText("Network")))
    }

    @Test
    fun `an unreadable row says so and shows no number`() {
        show(seenAt + 30_000)

        card(Provider.OLA, VehicleClass.AUTO)
            .assert(hasAnyDescendant(hasText("Check in app")))
            .assert(!hasAnyDescendant(hasText("₹", substring = true)))
            .assert(!hasAnyDescendant(hasTestTag(RmTags.FARE_PRICE)))
            .assert(!hasAnyDescendant(hasTestTag(RmTags.FARE_ETA)))
    }

    @Test
    fun `rows are grouped by vehicle type and show ETA or ETA see in app`() {
        show(seenAt + 30_000)

        compose.onNode(hasText("Auto") and heading).assertIsDisplayed()
        compose.onNode(hasText("Bike") and heading).assertIsDisplayed()
        // An ONDC row names its protocol provider; a typed row shows the class name.
        card(Provider.ONDC, VehicleClass.AUTO).assert(hasAnyDescendant(hasText("Demo Seller Autos")))
        card(Provider.UBER, VehicleClass.AUTO).assert(hasAnyDescendant(hasText("3 min") and hasAnyAncestor(hasTestTag(RmTags.FARE_ETA))))
        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(
                "Uber, Uber Auto, ₹120, Cheapest, Pickup in 3 min, Seen ${IstTime.seenAt(seenAt)}",
            )))
        card(Provider.RAPIDO, VehicleClass.BIKE).assert(hasAnyDescendant(hasText("ETA: see in app")))
    }

    @Test
    fun `cheapest cards come first within their vehicle section`() {
        val cheaper = verified(Provider.OLA, VehicleClass.AUTO, "Ola Auto", 100)
        compose.setContent { ComparisonScreen(data.copy(rows = rows.drop(1) + cheaper + rows.first()), seenAt + 30_000, null, callbacks) }

        val ola = compose.onNodeWithTag(rowTag(Provider.OLA, VehicleClass.AUTO)).fetchSemanticsNode().boundsInRoot.top
        val uber = compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO)).fetchSemanticsNode().boundsInRoot.top
        assert(ola < uber) { "₹100 Ola must sit above ₹120 Uber" }
        card(Provider.OLA, VehicleClass.AUTO).assert(hasAnyDescendant(hasText("Cheapest")))
        card(Provider.UBER, VehicleClass.AUTO).assert(!hasAnyDescendant(hasText("Cheapest")))
    }

    @Test
    fun `fresh rows say when and where they were seen`() {
        show(seenAt + 59_000)

        val time = IstTime.seenAt(seenAt)
        card(Provider.UBER, VehicleClass.AUTO)
            .assert(hasAnyDescendant(hasText("Uber")))
            .assert(hasAnyDescendant(hasText("Seen $time")))
        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "seen in Uber at $time"))
        compose.onAllNodesWithTag(RmTags.FARE_STALE, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `after 60 s every row greys out with old, tap to refresh, and a tap reopens the app`() {
        show(seenAt + 60_000)

        compose.onAllNodesWithTag(RmTags.FARE_STALE, useUnmergedTree = true).assertCountEquals(rows.size)
        compose.onAllNodesWithTag(RmTags.FARE_REFRESH, useUnmergedTree = true).assertCountEquals(rows.size)
        // §4.3.6: the row still says when it was seen, and how old it is (clock + words, not colour alone).
        card(Provider.UBER, VehicleClass.AUTO)
            .assert(hasAnyDescendant(hasText("1 min ago", substring = true) and hasText("Seen ${IstTime.seenAt(seenAt)}", substring = true)))
            .assert(!hasAnyDescendant(hasText("Cheapest")))
        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO)).assert(hasClickAction()).performClick()
        assertEquals(listOf(Provider.UBER), refreshed)
        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "old, tap to refresh"))
        compose.onNodeWithContentDescription("Refresh Ola price", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the ticker greys rows out at 60 s without any other change`() {
        val clock = FakeClock(wall = seenAt + 55_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { ComparisonScreen(data, rememberWallClock(clock), null, callbacks) }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag(RmTags.FARE_STALE, useUnmergedTree = true).assertCountEquals(0)

        clock.advance(6_000)
        compose.mainClock.advanceTimeBy(1_100)

        compose.onAllNodesWithTag(RmTags.FARE_STALE, useUnmergedTree = true).assertCountEquals(rows.size)
    }

    @Test
    fun `a card tap opens the app, the bottom bar books the cheapest, and I booked reports the app`() {
        show(seenAt + 30_000)

        compose.onNodeWithTag(rowTag(Provider.UBER, VehicleClass.AUTO)).assert(hasClickAction()).performClick()
        compose.onNodeWithTag(ComparisonTags.PRIMARY).assertHeightIsAtLeast(56.dp).assert(hasText("Book in Rapido · ₹60")).performClick()
        compose.onNodeWithTag(ComparisonTags.BOOKED).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Booked in Rapido").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(Provider.UBER, Provider.RAPIDO), booked)
        assertEquals(listOf(Provider.RAPIDO), bookedHere)
        // ONDC rows are booked inside RideMate: no card action and no "Booked in" for the network.
        compose.onNodeWithTag(rowTag(Provider.ONDC, VehicleClass.AUTO)).assert(hasNoClickAction())
        compose.onAllNodesWithText("Booked in Open network (ONDC)").assertCountEquals(0)
    }

    @Test
    fun `an app not read yet is offered as the next app to open`() {
        val withNext = data.copy(legs = data.legs + TestData.leg("l5", "s1", Provider.DEMO_KESTREL, RideState.QUOTED))
        compose.setContent { ComparisonScreen(withNext, seenAt + 30_000, null, callbacks) }

        val name = FareReadingMessages.name(androidx.test.core.app.ApplicationProvider.getApplicationContext(), Provider.DEMO_KESTREL)
        compose.onNodeWithTag(ComparisonTags.PRIMARY).assert(hasText("Open $name")).performClick()
        assertEquals(listOf(Provider.DEMO_KESTREL), booked)
    }

    @Test
    fun `manual mode shows the resume time and saves a typed price as entered by you`() {
        show(seenAt, ManualPrompt("AI reading resumes at 1:30 pm (IST). You can type the price meanwhile.", Provider.OLA, listOf(VehicleClass.AUTO)))
        compose.waitForIdle()

        compose.onNodeWithTag(ComparisonTags.MANUAL_FORM).assertExists()
        compose.onNodeWithText("AI reading resumes at 1:30 pm (IST). You can type the price meanwhile.").assertExists()
        compose.onNodeWithTag(ComparisonTags.PRICE_FIELD).performTextInput("₹105")
        compose.onNodeWithText("Save price").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(Triple(Provider.OLA, VehicleClass.AUTO, "₹105")), typed)
        compose.onNodeWithText("Saved. It shows as \"entered by you\".").assertExists()
    }

    @Test
    fun `an unreadable card's Type price opens the price sheet for that app and vehicle`() {
        show(seenAt + 30_000)
        compose.onAllNodesWithTag(ComparisonTags.MANUAL_FORM).assertCountEquals(0)

        compose.onNode(hasTestTag(RmTags.FARE_TYPE_PRICE) and hasAnyAncestor(hasTestTag(rowTag(Provider.OLA, VehicleClass.AUTO))), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(ComparisonTags.PRICE_FIELD).performTextInput("110")
        compose.onNodeWithText("Save price").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(Triple(Provider.OLA, VehicleClass.AUTO, "110")), typed)
    }

    @Test
    fun `the bottom bar Type price opens the sheet and asks for an app and vehicle first`() {
        show(seenAt + 30_000)

        compose.onNodeWithTag(ComparisonTags.TYPE_PRICE).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ComparisonTags.PRICE_FIELD).performTextInput("110")
        compose.onNodeWithText("Save price").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(emptyList<Triple<Provider, VehicleClass, String>>(), typed)
        compose.onNodeWithText("Choose the ride app and vehicle type first.").assertExists()
    }

    @Test
    fun `the info button explains the badges in a sheet`() {
        show(seenAt + 30_000)

        compose.onNodeWithContentDescription("How prices work").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RmTags.INFO_SHEET).assertExists()
        compose.onNodeWithText("never marked Cheapest", substring = true).assertExists()
    }

    @Test
    fun `with no prices the screen shows a short empty state`() {
        compose.setContent { ComparisonScreen(ComparisonData.empty("s1"), seenAt, null, callbacks) }

        compose.onNodeWithText("No prices yet").assertIsDisplayed()
        compose.onAllNodesWithTag(ComparisonTags.PRIMARY).assertCountEquals(0)
    }

    @Test
    fun `without a guardian bound there is no Booked here button`() {
        compose.setContent { ComparisonScreen(data, seenAt, null, callbacks.copy(onBookedHere = null)) }
        compose.onAllNodesWithTag(ComparisonTags.BOOKED).assertCountEquals(0)
        compose.onAllNodesWithText("Booked in Uber").assertCountEquals(0)
    }
}
