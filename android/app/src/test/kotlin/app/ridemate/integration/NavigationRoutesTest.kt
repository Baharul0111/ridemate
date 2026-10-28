package app.ridemate.integration

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.config.AssetBundledConfig
import app.ridemate.notify.GuardianIntents
import app.ridemate.testing.TestRideMateApp
import app.ridemate.ui.DeepLink
import app.ridemate.ui.DeepLinks
import app.ridemate.ui.NavFeatureNavigator
import app.ridemate.ui.RideMateRoot
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivity's navigation (RideMateRoot, its whole content) reaches every route with the real screens bound in
 * AppGraph.featureBindings, and the guardian's notification intents (RideMate's launch intent with a destination)
 * route to the guardian and to the comparison.
 */
@RunWith(AndroidJUnit4::class)
class NavigationRoutesTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp

    @Before
    fun setUp() {
        app.fakes.bundled = AssetBundledConfig(context.assets).load()
        app.graph.appShell.onboardingStore.markCompleted()
    }

    @Test
    fun `every route is reachable and renders its real screen`() {
        lateinit var nav: NavHostController
        val deepLinks = MutableStateFlow<DeepLink?>(null)
        rule.setContent {
            nav = rememberNavController()
            RideMateTheme { RideMateRoot(app.graph, deepLinks, onDeepLinkHandled = { deepLinks.value = null }, navController = nav) }
        }
        rule.waitForIdle()
        assertEquals(Routes.HOME, nav.currentDestination?.route)
        val navigator = NavFeatureNavigator(nav) { true }

        // Feature screens (other areas), each with its own title in the top bar.
        val feature = listOf(
            Triple(Routes.TRIP, "Trip") { navigator.tripInput() },
            Triple(Routes.COMPARE, "Compare prices") { navigator.comparison("s1") },
            Triple(Routes.GUARDIAN, "Ride guardian") { navigator.guardian("s1", "first_driver") },
            Triple(Routes.DEMO, "Try the demo") { navigator.demo() },
        )
        for ((route, title, go) in feature) {
            rule.runOnIdle { go() }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(route, nav.currentDestination?.route) }
            rule.onAllNodesWithText(title).onFirst().assertIsDisplayed()
            rule.runOnIdle { navigator.home() }
        }

        // The shell's own screens.
        val shell = listOf(
            Routes.SETTINGS to { navigator.settings() },
            Routes.SIGN_IN to { navigator.signInOffer() },
            Routes.PRIVACY to { nav.navigate(Routes.PRIVACY) },
            Routes.MY_DATA to { nav.navigate(Routes.MY_DATA) },
            Routes.ABOUT to { nav.navigate(Routes.ABOUT) },
            Routes.LICENCES to { nav.navigate(Routes.LICENCES) },
            Routes.DEV to { nav.navigate(Routes.DEV) },
        )
        for ((route, go) in shell) {
            rule.runOnIdle { go() }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(route, nav.currentDestination?.route) }
            rule.runOnIdle { navigator.home() }
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(Routes.HOME, nav.currentDestination?.route) }

        // Notification taps: the guardian and the comparison (Compare again).
        deepLinks.value = DeepLinks.parse(GuardianIntents.rideMateIntent(context, GuardianIntents.DESTINATION_GUARDIAN))
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(Routes.GUARDIAN, nav.currentDestination?.route) }
        deepLinks.value = DeepLinks.parse(GuardianIntents.rideMateIntent(context, GuardianIntents.DESTINATION_COMPARE, "s2"))
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(Routes.COMPARE, nav.currentDestination?.route)
            assertEquals("s2", nav.currentBackStackEntry?.arguments?.getString(Routes.ARG_SESSION))
        }
    }

    @Test
    fun `notification intents parse to their destinations, anything else opens the app normally`() {
        assertEquals(DeepLink.Guardian(null, null), DeepLinks.parse(GuardianIntents.rideMateIntent(context, GuardianIntents.DESTINATION_GUARDIAN)))
        assertEquals(DeepLink.Comparison("s9"), DeepLinks.parse(GuardianIntents.rideMateIntent(context, GuardianIntents.DESTINATION_COMPARE, "s9")))
        assertEquals(DeepLink.Guardian("s1", "sheet-3"), DeepLinks.parse(DeepLinks.guardian(context, "s1", "sheet-3")))
        assertEquals(null, DeepLinks.parse(Intent(Intent.ACTION_MAIN)))
        // Extras are validated: an unsafe session id is dropped, the destination still opens.
        val hostile = GuardianIntents.rideMateIntent(context, GuardianIntents.DESTINATION_COMPARE)!!
            .putExtra(GuardianIntents.EXTRA_SESSION_ID, "../../etc")
        assertEquals(DeepLink.Comparison(null), DeepLinks.parse(hostile))
    }
}
