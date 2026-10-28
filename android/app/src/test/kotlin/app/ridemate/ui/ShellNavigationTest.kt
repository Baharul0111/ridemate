package app.ridemate.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.MainActivity
import app.ridemate.testing.TestRideMateApp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp

    @Test
    fun `guardian deep links round-trip and reject anything else`() {
        val intent = DeepLinks.guardian(context, "session-1", "first_driver")
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(DeepLink.Guardian("session-1", "first_driver"), DeepLinks.parse(intent))
        assertEquals(DeepLink.Guardian(null, null), DeepLinks.parse(DeepLinks.guardian(context, null)))
        // Distinct per session and sheet, so PendingIntents don't overwrite each other.
        assertEquals(false, intent.filterEquals(DeepLinks.guardian(context, "session-2", "first_driver")))

        assertNull(DeepLinks.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(DeepLinks.parse(null))
        val hostile = Intent(DeepLinks.ACTION_OPEN_GUARDIAN)
            .putExtra(DeepLinks.EXTRA_SESSION_ID, "../../etc/passwd?x=<script>")
            .putExtra(DeepLinks.EXTRA_SHEET, "a".repeat(500))
        assertEquals(DeepLink.Guardian(null, null), DeepLinks.parse(hostile))
    }

    @Test
    fun `routes encode their arguments`() {
        assertEquals("guardian", Routes.guardian(null, null))
        assertEquals("guardian?sessionId=s%201&sheet=x", Routes.guardian("s 1", "x"))
        assertEquals("guardian?sheet=x", Routes.guardian(null, "x"))
        assertEquals("compare", Routes.compare(null))
        assertEquals("compare?sessionId=s1", Routes.compare("s1"))
    }

    @Test
    fun `money and times use Indian grouping and IST 12-hour`() {
        assertEquals("₹1,23,456", UiFormat.money(123_456))
        assertEquals("₹110–130", UiFormat.moneyRange(130, 110))
        assertEquals("₹120", UiFormat.moneyRange(120, 120))
        // 2026-10-28T08:00:00Z is 1:30 PM in India, whatever the phone's zone.
        val t = java.time.Instant.parse("2026-10-28T08:00:00Z").toEpochMilli()
        assertEquals("1:30 PM", UiFormat.timeIst(t))
        assertEquals("1:30:00 PM", UiFormat.timeIstWithSeconds(t))
        assertEquals("1:30 PM", UiFormat.resumeAtIst("13:30"))
        assertNull(UiFormat.resumeAtIst("25:99"))
        assertEquals("28 Oct 2026, 1:30 PM", UiFormat.dateTimeIst(t, java.util.Locale.ENGLISH))
        assertEquals("$1,234.50", UiFormat.usd(1234.5))
        assertEquals(50, UiFormat.percent(1.0, 2.0))
        assertNull(UiFormat.percent(1.0, 0.0))
    }

    @Test
    fun `fresh install starts at onboarding and a notification link cannot skip it`() {
        val deepLinks = MutableStateFlow<DeepLink?>(null)
        rule.setContent { RideMateTheme { RideMateRoot(app.graph, deepLinks, onDeepLinkHandled = { deepLinks.value = null }) } }
        rule.onNodeWithText("Are you 18 or older?").assertIsDisplayed()

        // MainActivity is exported: a link sent before onboarding is consumed but never opens anything.
        deepLinks.value = DeepLink.Guardian("s1", "first_driver")
        rule.waitForIdle()
        assertNull("the deep link is consumed once", deepLinks.value)
        rule.onNodeWithText("Are you 18 or older?").assertIsDisplayed()
        rule.onAllNodesWithText("Ride guardian").assertCountEquals(0)
    }

    @Test
    fun `under 18 stays blocked whatever a link or the navigator asks`() {
        app.graph.appShell.onboardingStore.declareUnderAge()
        val deepLinks = MutableStateFlow<DeepLink?>(null)
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            RideMateTheme { RideMateRoot(app.graph, deepLinks, onDeepLinkHandled = { deepLinks.value = null }, navController = nav) }
        }
        rule.onNodeWithText("RideMate is for adults only").assertIsDisplayed()
        deepLinks.value = DeepLink.Guardian("s1", null)
        rule.waitForIdle()
        rule.runOnIdle { NavFeatureNavigator(nav) { app.graph.appShell.onboardingStore.answers.value.completed }.home() }
        rule.waitForIdle()
        rule.onNodeWithText("RideMate is for adults only").assertIsDisplayed()
        rule.onAllNodesWithText("Compare ride prices").assertCountEquals(0)
        rule.runOnIdle { assertEquals(Routes.ONBOARDING, nav.currentDestination?.route) }
    }

    @Test
    fun `after onboarding a notification opens the guardian, and home clears the back stack`() {
        app.graph.appShell.onboardingStore.markCompleted()
        val deepLinks = MutableStateFlow<DeepLink?>(null)
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            RideMateTheme { RideMateRoot(app.graph, deepLinks, onDeepLinkHandled = { deepLinks.value = null }, navController = nav) }
        }
        rule.onNodeWithText("Compare ride prices").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Privacy notice").assertIsDisplayed()

        // A notification tap opens the guardian (a placeholder until the integration binds A3's screen).
        deepLinks.value = DeepLink.Guardian("s1", "first_driver")
        rule.waitForIdle()
        assertNull("the deep link is consumed once", deepLinks.value)
        rule.onNodeWithText("Ride guardian").assertIsDisplayed()
        rule.onNodeWithText("This part isn't in this build yet").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(Routes.GUARDIAN, nav.currentDestination?.route)
            assertEquals("s1", nav.currentBackStackEntry?.arguments?.getString(Routes.ARG_SESSION))
            assertEquals("first_driver", nav.currentBackStackEntry?.arguments?.getString(Routes.ARG_SHEET))
        }

        rule.onNodeWithText("Go to the home screen").performScrollTo().performClick()
        rule.onNodeWithText("Compare ride prices").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(Routes.HOME, nav.currentDestination?.route)
            assertNull("Home is the only screen left, so Back leaves the app", nav.previousBackStackEntry)
        }
    }

    @Test
    fun `after onboarding the app opens at home and reaches settings and about`() {
        app.graph.appShell.onboardingStore.markCompleted()
        rule.setContent { RideMateTheme { RideMateRoot(app.graph, MutableStateFlow(null), onDeepLinkHandled = {}) } }
        rule.onNodeWithText("Compare ride prices").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Privacy notice").assertIsDisplayed()
        rule.onNodeWithText("About RideMate").performScrollTo().performClick()
        rule.onNodeWithText("1.0.0-test (1)").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Privacy notice").performScrollTo().performClick()
        rule.onNodeWithText("AI processing happens on Modal's servers in the USA.").assertIsDisplayed()
    }
}
