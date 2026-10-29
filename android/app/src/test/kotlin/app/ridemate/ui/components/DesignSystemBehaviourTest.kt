package app.ridemate.ui.components

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.ui.ChipTone
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmMotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesignSystemBehaviourTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun resetAnimatorScale() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun inCard(cardTag: String, tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag(cardTag))

    private fun cardTag(q: FareQuote) = RmTags.fareCard(q.provider.wireId, q.vehicleClass.wireId)

    private fun description(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")

    // ------------------------------------------------------------------------------------------- FareCard

    @Test
    fun `an unreadable fare shows no number, only check in app`() {
        // Even if a caller passes a price (and cheapest) for an unreadable row, nothing of it reaches the screen (I2).
        val q = Samples.unreadable
        rule.setContent { RideMateTheme { FareCard(q, onTypePrice = {}) } }
        val card = cardTag(q)
        rule.onAllNodesWithTag(RmTags.FARE_PRICE, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag(RmTags.FARE_CHEAPEST, useUnmergedTree = true).assertCountEquals(0)
        rule.onNode(inCard(card, RmTags.FARE_UNREADABLE), useUnmergedTree = true).assert(hasAnyDescendant(hasText("Check in app")))
        rule.onNodeWithText("₹", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("120", substring = true, useUnmergedTree = true).assertDoesNotExist()
        val spoken = description(card)
        assertFalse("number in '$spoken'", spoken.contains("120") || spoken.contains("₹"))
        assertTrue(spoken.contains("Couldn't read the price. Check in app"))
        rule.onNodeWithTag(RmTags.FARE_TYPE_PRICE).assertHeightIsAtLeast(48.dp)
        assertFalse(q.showsPrice)
        assertFalse(q.showsCheapest)
    }

    @Test
    fun `the cheapest badge shows only on a fresh verified price`() {
        val quotes = listOf(
            Samples.cheapest,
            Samples.typed,
            Samples.network,
            Samples.unreadable,
            Samples.stale.copy(provider = Provider.UBER, cheapest = true),
        )
        rule.setContent { RideMateTheme { Column { quotes.forEach { FareCard(it) } } } }
        for (q in quotes) {
            val badges = rule.onAllNodes(inCard(cardTag(q), RmTags.FARE_CHEAPEST), useUnmergedTree = true).fetchSemanticsNodes()
            val expected = q.status == QuoteStatus.VERIFIED && !q.stale
            assertEquals("cheapest badge for ${q.status} stale=${q.stale}", if (expected) 1 else 0, badges.size)
            assertEquals(expected, description(cardTag(q)).contains("Cheapest"))
        }
        // The badge is icon + word, not colour.
        rule.onNode(inCard(cardTag(Samples.cheapest), RmTags.FARE_CHEAPEST), useUnmergedTree = true).assert(hasAnyDescendant(hasText("Cheapest")))
    }

    @Test
    fun `stale, typed and network fares say so in words`() {
        var refreshed = 0
        rule.setContent {
            RideMateTheme {
                Column {
                    FareCard(Samples.stale, onRefresh = { refreshed++ })
                    FareCard(Samples.typed)
                    FareCard(Samples.network)
                }
            }
        }
        rule.onNode(inCard(cardTag(Samples.stale), RmTags.FARE_STALE), useUnmergedTree = true)
            .assert(hasText("2 min ago · Seen 10:28 AM"))
        assertTrue(description(cardTag(Samples.stale)).endsWith("Seen 10:28 AM, Old price"))
        rule.onNodeWithContentDescription("Refresh Ola price").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(1, refreshed)
        rule.onNode(inCard(cardTag(Samples.typed), RmTags.FARE_ENTERED), useUnmergedTree = true).assert(hasAnyDescendant(hasText("You typed")))
        rule.onNode(inCard(cardTag(Samples.network), RmTags.FARE_NETWORK), useUnmergedTree = true).assert(hasAnyDescendant(hasText("Network")))
        rule.onNode(inCard(cardTag(Samples.network), RmTags.FARE_ETA), useUnmergedTree = true).assert(hasAnyDescendant(hasText("4 min")))
        assertEquals(
            "Open network (ONDC), Namma Yatri, ₹90–110, Network, Pickup in 4 min, Seen 10:31 AM",
            description(cardTag(Samples.network)),
        )
    }

    // ----------------------------------------------------------------------------------------- StatusPill

    @Test
    fun `every ride state shows a pill with an icon and a word`() {
        val states = RideState.entries
        rule.setContent { RideMateTheme { Column { states.forEach { StatusPill(it) } } } }
        val pills = rule.onAllNodesWithTag(RmTags.STATUS_PILL, useUnmergedTree = true)
        pills.assertCountEquals(states.size)
        states.forEachIndexed { i, state ->
            val word = context.getString(state.group.label)
            pills[i].assert(hasAnyDescendant(hasTestTag(RmTags.PILL_ICON)))
            pills[i].assert(hasAnyDescendant(hasText(word)))
            pills[i].assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(word)))
        }
    }

    @Test
    fun `ride state groups are distinct and keep the invariants`() {
        assertEquals(RideStateGroup.entries.size, RideStateGroup.entries.map { it.icon }.toSet().size)
        assertEquals(RideStateGroup.entries.size, RideStateGroup.entries.map { it.label }.toSet().size)
        // I10: only a driver-assigned leg reads as "Driver coming".
        assertEquals(RideState.entries.filter { it.hasDriver }.toSet(), RideState.entries.filter { it.group == RideStateGroup.DRIVER_COMING }.toSet())
        // Only a proven cancel reads as "Cancelled"; maybe-states always say "Check app".
        assertEquals(setOf(RideState.CANCEL_CONFIRMED), RideState.entries.filter { it.group == RideStateGroup.CANCELLED }.toSet())
        RideState.entries.filter { it.isMaybe }.forEach { assertEquals(RideStateGroup.CHECK_APP, it.group) }
        assertEquals(RideStateGroup.CANCELLING, RideState.USER_SAYS_CANCELLED.group)
    }

    // ---------------------------------------------------------------------------------------------- motion

    @Test
    fun `motion collapses to nothing when the animator scale is 0`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var motion: RmMotion? = null
        rule.setContent { RideMateTheme { motion = RideMateTheme.motion } }
        rule.waitForIdle()
        val m = assertNotNullAndGet(motion)
        assertFalse(m.enabled)
        assertTrue(m.tween<Float>() is SnapSpec)
        assertTrue(m.spring<Float>() is SnapSpec)
        assertEquals(EnterTransition.None, m.enter())
        assertEquals(ExitTransition.None, m.exit())
        assertEquals(0, m.mediumMs)
    }

    @Test
    fun `motion is calm and short when animations are on`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var motion: RmMotion? = null
        rule.setContent { RideMateTheme { motion = RideMateTheme.motion } }
        rule.waitForIdle()
        val m = assertNotNullAndGet(motion)
        assertTrue(m.enabled)
        val tween = m.tween<Float>() as TweenSpec<Float>
        assertTrue("≤ 250 ms", tween.durationMillis <= 250)
        assertTrue(m.enter() != EnterTransition.None)
        assertFalse(RmMotion.fromSystem(context.also { Settings.Global.putFloat(it.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f) }).enabled)
    }

    private fun assertNotNullAndGet(m: RmMotion?): RmMotion {
        assertNotNull(m)
        return m!!
    }

    // --------------------------------------------------------------------------- semantics & touch targets

    @Test
    fun `avatars say the provider, never show logos, and demo ones carry the DEMO tag`() {
        rule.setContent { RideMateTheme { Column { Provider.entries.forEach { ProviderAvatar(it) } } } }
        for (p in Provider.entries) {
            val spoken = if (p.isDemo) "${p.displayName}, demo" else p.displayName
            rule.onNodeWithContentDescription(spoken).assertExists()
        }
        rule.onAllNodesWithTag(RmTags.DEMO_TAG, useUnmergedTree = true).assertCountEquals(Provider.DEMO_APPS.size)
        assertEquals(listOf("U", "O", "R", "N", "K", "P", "T"), Provider.entries.map { it.initial })
        // Neutral tones: no two providers share a colour, and the letter reads on it in both themes.
        assertEquals(Provider.entries.size, Provider.entries.map { it.avatarColors(false).container }.toSet().size)
        for (p in Provider.entries) for (dark in listOf(false, true)) {
            val c = p.avatarColors(dark)
            assertTrue("$p avatar contrast", Contrast.ratio(c.content, c.container) >= 4.5)
        }
    }

    @Test
    fun `choice tiles are 48 dp+, show a check when selected and expose their state`() {
        rule.setContent {
            RideMateTheme {
                var uber by remember { mutableStateOf(false) }
                Column {
                    ProviderChoiceTile(Provider.UBER, uber, { uber = !uber }, Modifier.width(100.dp))
                    VehicleChoiceTile(VehicleClass.AUTO, true, {}, Modifier.width(100.dp))
                }
            }
        }
        rule.onAllNodesWithTag(RmTags.CHOICE_CHECK, useUnmergedTree = true).assertCountEquals(1)
        val uberTile = rule.onNode(hasTestTag(RmTags.CHOICE_TILE) and hasText("Uber"))
        uberTile.assertIsOff().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        uberTile.assertIsOn()
        rule.onAllNodesWithTag(RmTags.CHOICE_CHECK, useUnmergedTree = true).assertCountEquals(2)
        rule.onNode(hasTestTag(RmTags.CHOICE_TILE) and hasText("Auto")).assertIsSelected()
    }

    @Test
    fun `route, where-to, step dots and vehicles have spoken labels`() {
        rule.setContent {
            RideMateTheme {
                Column {
                    WhereToBar(onClick = {})
                    RouteStack(pickup = "Current location", drop = "LGBI Airport", onPickupClick = {}, onDropClick = {})
                    StepDots(count = 6, current = 1)
                    VehicleIcon(VehicleClass.AUTO)
                    PlaceRow("LGBI Airport", "Borjhar", {})
                    BigPrimaryButton("Compare 3 apps", {})
                }
            }
        }
        rule.onNodeWithContentDescription("Search where to go").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Pickup: Current location").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Drop: LGBI Airport").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Step 2 of 6").assertExists()
        rule.onNodeWithContentDescription("Auto").assertExists()
        rule.onNodeWithContentDescription("LGBI Airport, Borjhar").assertHeightIsAtLeast(56.dp)
        rule.onNodeWithTag(RmTags.BIG_BUTTON).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `the info sheet shows its text and closes`() {
        var open by mutableStateOf(true)
        var primary = 0
        rule.setContent {
            RideMateTheme {
                if (open) {
                    InfoSheet(
                        onDismiss = { open = false },
                        title = "Why notifications?",
                        body = "To tell you when a driver is found.",
                        primaryLabel = "Allow",
                        onPrimary = { primary++ },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(RmTags.INFO_SHEET).assertExists()
        rule.onNodeWithText("Why notifications?").assert(isHeading())
        rule.onNodeWithText("To tell you when a driver is found.").assertExists()
        // The sheet lives in its own window; semantic clicks don't depend on where Robolectric places it.
        rule.onNodeWithText("Allow").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, primary)
        rule.onNodeWithContentDescription("Close").assertHeightIsAtLeast(48.dp).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertFalse(open)
    }

    @Test
    fun `legacy components keep their spoken labels in the new look`() {
        var back = 0
        rule.setContent {
            RideMateTheme {
                @Suppress("DEPRECATION")
                app.ridemate.ui.ScreenScaffold(title = "Settings", onBack = { back++ }) {
                    app.ridemate.ui.StatusChip("Off", ChipTone.NEUTRAL, label = "Uber reading")
                    app.ridemate.ui.DemoBadge()
                    app.ridemate.ui.PrimaryButton("Compare ride prices", {})
                    app.ridemate.ui.InfoCard(title = "Ride in progress", body = "Open the guardian", tone = ChipTone.GOOD)
                }
            }
        }
        rule.onNodeWithContentDescription("Uber reading: Off").assertExists()
        rule.onNodeWithContentDescription("Demo: made-up sample screens").assertExists()
        rule.onNodeWithText("Compare ride prices").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Settings").assert(isHeading())
        rule.onNodeWithText("Ride in progress").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    // ------------------------------------------------------------------------------------- illustrations

    @Test
    fun `illustrations follow the app theme even when the phone theme differs`() {
        val day = context.withNightMode(false)
        val night = context.withNightMode(true)
        assertEquals(Configuration.UI_MODE_NIGHT_YES, night.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(0xFF0E6B57.toInt(), ContextCompat.getColor(day, R.color.rm_illo_brand))
        assertEquals(0xFF6FD3B8.toInt(), ContextCompat.getColor(night, R.color.rm_illo_brand))
        rule.setContent { RideMateTheme(darkTheme = true) { Column { Hero.entries.forEach { HeroIllustration(it) } } } }
        rule.onAllNodesWithTag(RmTags.HERO, useUnmergedTree = true).assertCountEquals(Hero.entries.size)
    }
}
