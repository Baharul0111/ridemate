package app.ridemate.home

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.config.KillSwitches
import app.ridemate.core.model.Provider
import app.ridemate.testing.A11y
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

    private fun show(state: HomeState, actions: HomeActions = HomeActions()) =
        rule.setContent { RideMateTheme { HomeScreen(state, actions) } }

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
        rule.onNodeWithContentDescription("Uber reading: Off").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Ola reading: On").performScrollTo().assertIsDisplayed()
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
}
