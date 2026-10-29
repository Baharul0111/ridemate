package app.ridemate.home

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.config.KillSwitches
import app.ridemate.core.model.Provider
import app.ridemate.testing.A11y
import app.ridemate.ui.components.RmTags
import app.ridemate.ui.RideMateTheme
import app.ridemate.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val base = HomeState(
        ai = AiStatus.PausedUntil("13:30"),
        killSwitches = KillSwitches(readerEnabled = mapOf(Provider.UBER to false, Provider.OLA to true, Provider.RAPIDO to true)),
        update = UpdateState(),
        activeSession = null,
        compare = CompareCheck.Idle,
        signedInWithGoogle = true,
    )

    private fun show(state: HomeState, actions: HomeActions = HomeActions(), recent: List<String> = emptyList()) =
        rule.setContent { RideMateTheme { HomeScreen(state, actions, recent) } }

    @Test
    fun `home is accessible and states are never colour-only`() {
        val clicks = mutableListOf<String>()
        show(base, HomeActions(compare = { clicks += "compare" }, demo = { clicks += "demo" }, settings = { clicks += "settings" }))
        A11y.assertClickablesAccessible(rule)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Compare ride prices").assertHeightIsAtLeast(48.dp).performClick()
        // The demo is labelled DEMO for screen readers too.
        rule.onNodeWithContentDescription("Demo: made-up sample screens").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Try the demo with made-up sample screens").performScrollTo().performClick()
        assertEquals(listOf("settings", "compare", "demo"), clicks)
        // AI status and kill switches read as words.
        rule.onNodeWithContentDescription("AI reading: Paused. AI reading resumes at 1:30 PM (IST). Type prices meanwhile.")
            .performScrollTo().assertIsDisplayed()
        // Home shows only what the owner switched off (Settings lists every switch, see below).
        rule.onNodeWithContentDescription("Uber reading: Off").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Ola reading: On").assertDoesNotExist()
        rule.onNodeWithText("Built with Muse Spark Open").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a blocked compare shows fix-it cards that say what to do`() {
        val fixed = mutableListOf<PreSessionIssue>()
        val report = PreSessionReport(listOf(PreSessionIssue.LISTENER_OFF, PreSessionIssue.NO_SUPPORTED_APP), listOf(PreSessionIssue.OFFLINE))
        show(base.copy(compare = CompareCheck.Blocked(report)), HomeActions(fix = { fixed += it }))
        rule.onNodeWithText("Fix these to compare prices").assertIsDisplayed()
        rule.onNodeWithText("RideMate can't see ride-app notifications").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn on notification access").performScrollTo().performClick()
        rule.onNodeWithText("No ride app installed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("You're offline").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Check again").performScrollTo().performClick()
        assertEquals(listOf(PreSessionIssue.LISTENER_OFF, PreSessionIssue.OFFLINE), fixed)
        A11y.assertClickablesAccessible(rule)
    }

    @Test
    fun `update banner, active session and the demo kill switch`() {
        val opened = mutableListOf<String>()
        show(
            base.copy(
                update = UpdateState(available = true, versionCode = 2, versionName = "1.1.0", url = "https://example.com/r.apk", sha256 = "a".repeat(64)),
                activeSession = ActiveSessionSummary("s1", "LGBI Airport", listOf(Provider.OLA, Provider.RAPIDO), 1_793_000_000_000L),
                killSwitches = KillSwitches(demoEnabled = false),
                ai = AiStatus.ManualMode,
            ),
            HomeActions(downloadUpdate = { opened += it }, openSession = { opened += it }),
        )
        rule.onNodeWithText("Update available: version 1.1.0").assertIsDisplayed()
        rule.onNodeWithText("SHA-256: ${"a".repeat(64)}").assertIsDisplayed()
        rule.onNodeWithText("Download update").performClick()
        rule.onNodeWithText("Ride in progress").assertIsDisplayed()
        rule.onNodeWithText("Open ride guardian").performScrollTo().performClick()
        assertEquals(listOf("https://example.com/r.apk", "s1"), opened)
        rule.onNodeWithText("The demo is switched off for now. Try again later.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Try the demo with made-up sample screens").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Sign in with Google").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `home is where-to first, with compare and demo tiles and no status when all is normal`() {
        val clicks = mutableListOf<String>()
        show(
            base.copy(ai = AiStatus.On(readsLeft = 5), killSwitches = KillSwitches()),
            HomeActions(compare = { clicks += "compare" }, demo = { clicks += "demo" }),
        )
        rule.onNodeWithTag(RmTags.WHERE_TO).assertIsDisplayed()
        rule.onNodeWithContentDescription("Search where to go").assertIsDisplayed().performClick()
        rule.onNodeWithTag(HomeTags.COMPARE_TILE).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(HomeTags.DEMO_TILE).performClick()
        assertEquals(listOf("compare", "compare", "demo"), clicks)
        // AI on and every switch on: no banner, no status pills, no intro paragraph.
        rule.onNodeWithTag(HomeTags.AI_BANNER).assertDoesNotExist()
        rule.onAllNodesWithTag(RmTags.PILL).assertCountEquals(0)
        rule.onNodeWithText("Tell RideMate where you're going once", substring = true).assertDoesNotExist()
        A11y.assertClickablesAccessible(rule)
    }

    @Test
    fun `where-to and compare do nothing while the pre-session check runs`() {
        val clicks = mutableListOf<String>()
        show(base.copy(compare = CompareCheck.Checking), HomeActions(compare = { clicks += "compare" }))
        rule.onNodeWithText("Checking RideMate's permissions…").assertIsDisplayed()
        rule.onNodeWithTag(RmTags.WHERE_TO).performClick()
        rule.onNodeWithTag(HomeTags.COMPARE_TILE).assertIsNotEnabled()
        assertEquals(emptyList<String>(), clicks)
    }

    @Test
    fun `fix-it cards are compact - the why sits behind an info button`() {
        val report = PreSessionReport(emptyList(), listOf(PreSessionIssue.BATTERY_OPTIMISED))
        var continued = false
        show(base.copy(compare = CompareCheck.Warnings(report)), HomeActions(continueAnyway = { continued = true }))
        rule.onNodeWithText("Before you compare").assertIsDisplayed()
        rule.onNodeWithText("Battery saving may delay reminders").assertIsDisplayed()
        rule.onNodeWithText("Allow RideMate to run in the background so reminders arrive on time.").assertDoesNotExist()
        rule.onNodeWithContentDescription("Why: Battery saving may delay reminders").assertIsDisplayed()
        rule.onNodeWithText("Continue anyway").performScrollTo().performClick()
        assertEquals(true, continued)
        A11y.assertClickablesAccessible(rule)
    }

    @Test
    fun `recent places sit under where-to as rows that start the trip`() {
        val clicks = mutableListOf<String>()
        show(base, HomeActions(compare = { clicks += "compare" }), recent = listOf("LGBI Airport", "Office", "Home", "Gym"))
        rule.onNodeWithTag(HomeTags.RECENT_PLACES).assertIsDisplayed()
        rule.onAllNodesWithTag(RmTags.PLACE_ROW).assertCountEquals(HOME_MAX_RECENT)
        rule.onNodeWithContentDescription("LGBI Airport, Recent").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("Gym, Recent").assertDoesNotExist()
        assertEquals(listOf("compare"), clicks)
        A11y.assertClickablesAccessible(rule)
    }

    @Test
    fun `no recent places means no recent rows`() {
        show(base)
        rule.onNodeWithTag(HomeTags.RECENT_PLACES).assertDoesNotExist()
    }

    @Test
    fun `settings still lists every kill switch as words`() {
        rule.setContent {
            RideMateTheme {
                KillSwitchStatusList(KillSwitches(readerEnabled = mapOf(Provider.UBER to false, Provider.OLA to true, Provider.RAPIDO to true)))
            }
        }
        rule.onNodeWithContentDescription("Uber reading: Off").assertIsDisplayed()
        rule.onNodeWithContentDescription("Ola reading: On").assertIsDisplayed()
        rule.onNodeWithContentDescription("Demo: On").assertIsDisplayed()
    }
}
