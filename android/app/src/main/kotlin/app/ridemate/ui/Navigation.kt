package app.ridemate.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ridemate.AppGraph
import app.ridemate.R
import app.ridemate.dev.DevRoute
import app.ridemate.home.HomeRoute
import app.ridemate.home.UpdateBanner
import app.ridemate.onboarding.OnboardingRoute
import app.ridemate.onboarding.SignInOfferRoute
import app.ridemate.settings.AboutRoute
import app.ridemate.settings.LicencesScreen
import app.ridemate.settings.MyDataRoute
import app.ridemate.settings.PrivacyNoticeScreen
import app.ridemate.settings.SettingsNavigation
import app.ridemate.settings.SettingsRoute
import app.ridemate.settings.contactPrivacy
import app.ridemate.update.UpdateState
import kotlinx.coroutines.flow.StateFlow

/** Navigation routes of the app. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val TRIP = "trip"
    const val COMPARE = "compare?sessionId={sessionId}"
    const val GUARDIAN = "guardian?sessionId={sessionId}&sheet={sheet}"
    const val DEMO = "demo"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val MY_DATA = "mydata"
    const val ABOUT = "about"
    const val LICENCES = "licences"
    const val DEV = "dev"
    const val SIGN_IN = "signin"

    const val ARG_SESSION = "sessionId"
    const val ARG_SHEET = "sheet"

    /** Screens without the guardian's sheet overlay. */
    val NO_SESSION_SHEETS: Set<String> = setOf(ONBOARDING, GUARDIAN)

    /** Screens still reachable while a forced update blocks the rest (privacy rights never wait for an update). */
    val ALLOWED_DURING_FORCED_UPDATE: Set<String> = setOf(SETTINGS, PRIVACY, MY_DATA, ABOUT, LICENCES)

    fun compare(sessionId: String?): String = if (sessionId == null) "compare" else "compare?sessionId=${Uri.encode(sessionId)}"

    fun guardian(sessionId: String?, sheet: String?): String = buildString {
        append("guardian")
        val params = listOfNotNull(sessionId?.let { "sessionId=${Uri.encode(it)}" }, sheet?.let { "sheet=${Uri.encode(it)}" })
        if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
    }
}

/**
 * [FeatureNavigator] over a [NavHostController]. [isOnboarded] guards Home: until onboarding (18+ check, privacy
 * notice) is completed, "home" means onboarding, so no screen can lead past it.
 */
class NavFeatureNavigator(
    private val nav: NavHostController,
    private val isOnboarded: () -> Boolean = { true },
) : FeatureNavigator {
    override fun back() {
        if (!nav.popBackStack()) home()
    }

    /** Home as the only screen on the back stack (Back from Home leaves the app). */
    override fun home() {
        if (!isOnboarded()) {
            restartOnboarding()
            return
        }
        nav.navigate(Routes.HOME) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    override fun tripInput() = nav.navigate(Routes.TRIP)

    override fun comparison(sessionId: String?) = nav.navigate(Routes.compare(sessionId))

    override fun guardian(sessionId: String?, sheet: String?) = nav.navigate(Routes.guardian(sessionId, sheet)) { launchSingleTop = true }

    override fun demo() = nav.navigate(Routes.DEMO) { launchSingleTop = true }

    override fun settings() = nav.navigate(Routes.SETTINGS) { launchSingleTop = true }

    override fun signInOffer() = nav.navigate(Routes.SIGN_IN) { launchSingleTop = true }

    fun open(link: DeepLink) = when (link) {
        is DeepLink.Guardian -> guardian(link.sessionId, link.sheet)
        is DeepLink.Comparison -> comparison(link.sessionId)
    }

    /** Onboarding again from the start (after Erase wiped its answers). */
    fun restartOnboarding() {
        nav.navigate(Routes.ONBOARDING) {
            popUpTo(nav.graph.id) { inclusive = true }
        }
    }
}

/**
 * The whole app: onboarding until it is completed, then Home; other areas' screens through
 * [AppGraph.featureBindings]; deep links from notifications ([deepLinks], cleared with [onDeepLinkHandled]; dropped
 * until onboarding is completed); and the blocking "please update" screen when this build is below
 * `min_supported_version` and no session is active. [navController] is a parameter only so tests can inspect it.
 */
@Composable
fun RideMateRoot(
    graph: AppGraph,
    deepLinks: StateFlow<DeepLink?>,
    onDeepLinkHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val shell = graph.appShell
    val screens = graph.featureBindings.screens
    val isOnboarded: () -> Boolean = { shell.onboardingStore.answers.value.completed }
    val navigator = remember(navController) { NavFeatureNavigator(navController, isOnboarded) }
    val start = remember { if (shell.onboardingStore.answers.value.completed) Routes.HOME else Routes.ONBOARDING }
    val update by graph.updateChecker.state.collectAsStateWithLifecycle()
    val entry by navController.currentBackStackEntryAsState()
    val blocked = update.forceUpdate && entry?.destination?.route !in Routes.ALLOWED_DURING_FORCED_UPDATE

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (blocked) Modifier.clearAndSetSemantics { } else Modifier)) {
            NavHost(navController = navController, startDestination = start) {
                composable(Routes.ONBOARDING) {
                    OnboardingRoute(graph) {
                        navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    }
                }
                composable(Routes.HOME) { HomeRoute(graph, navigator) }
                composable(Routes.TRIP) { screens.TripInput(navigator) }
                composable(
                    Routes.COMPARE,
                    arguments = listOf(navArgument(Routes.ARG_SESSION) { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { screens.Comparison(it.arguments?.getString(Routes.ARG_SESSION), navigator) }
                composable(
                    Routes.GUARDIAN,
                    arguments = listOf(
                        navArgument(Routes.ARG_SESSION) { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument(Routes.ARG_SHEET) { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { screens.Guardian(it.arguments?.getString(Routes.ARG_SESSION), it.arguments?.getString(Routes.ARG_SHEET), navigator) }
                composable(Routes.DEMO) { screens.Demo(navigator) }
                composable(Routes.SIGN_IN) { SignInOfferRoute(graph) { navigator.back() } }
                composable(Routes.SETTINGS) {
                    SettingsRoute(
                        graph,
                        SettingsNavigation(
                            back = navigator::back,
                            privacyNotice = { navController.navigate(Routes.PRIVACY) },
                            myData = { navController.navigate(Routes.MY_DATA) },
                            about = { navController.navigate(Routes.ABOUT) },
                            afterErase = navigator::restartOnboarding,
                        ),
                    )
                }
                composable(Routes.PRIVACY) {
                    val context = LocalContext.current
                    PrivacyNoticeScreen(onBack = navigator::back, onContact = { contactPrivacy(context) })
                }
                composable(Routes.MY_DATA) { MyDataRoute(graph, onBack = navigator::back, afterErase = navigator::restartOnboarding) }
                composable(Routes.ABOUT) {
                    AboutRoute(
                        graph,
                        onBack = navigator::back,
                        onLicences = { navController.navigate(Routes.LICENCES) },
                        onDeveloper = { navController.navigate(Routes.DEV) { launchSingleTop = true } },
                    )
                }
                composable(Routes.LICENCES) { LicencesScreen(onBack = navigator::back) }
                composable(Routes.DEV) { DevRoute(graph, onBack = navigator::back) }
            }
            // The guardian's open sheet (driver found, "Cancel the others?", questions) over any screen: not over
            // onboarding (no ride can exist before it is completed) nor the guardian screen (it lists them all).
            if (entry?.destination?.route !in Routes.NO_SESSION_SHEETS && !blocked) screens.SessionSheets(navigator)
        }
        if (blocked) ForceUpdateScreen(update, onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } })
    }

    val pending by deepLinks.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let {
            // MainActivity is exported: a link must never lead past onboarding (18+ check, privacy notice). Before
            // onboarding is completed there is no ride to guard, so the link is dropped.
            if (isOnboarded()) navigator.open(it)
            onDeepLinkHandled()
        }
    }
}

/** This build is below `min_supported_version`: only updating (or Settings, for privacy rights) is possible. */
@Composable
fun ForceUpdateScreen(update: UpdateState, onSettings: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ScreenScaffold(title = stringResource(R.string.ui_update_required_title), onBack = null) {
            Text(stringResource(R.string.ui_update_required_body), style = MaterialTheme.typography.bodyLarge)
            UpdateBanner(update) { SystemIntents.openHttps(context, it) }
            SecondaryButton(stringResource(R.string.ui_open_settings), onSettings)
        }
    }
}
