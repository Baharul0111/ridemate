package app.ridemate.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ridemate.AppGraph
import app.ridemate.auth.AuthState
import app.ridemate.ui.SystemIntents
import app.ridemate.ui.findActivity
import app.ridemate.ui.rememberScopedModel

/**
 * Onboarding wired to Android: runtime prompts through activity-result launchers, settings screens by intent, and a
 * permission re-check every time the user comes back (so a granted step advances by itself).
 */
@Composable
fun OnboardingRoute(graph: AppGraph, onFinished: () -> Unit) {
    val shell = graph.appShell
    val controller = rememberScopedModel("onboarding") { scope -> shell.onboardingController(scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(controller) {
        controller.refresh()
        onPauseOrDispose { }
    }

    val notificationPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refresh() }
    val locationPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { controller.refresh() }

    fun open(intents: List<android.content.Intent>) {
        SystemIntents.startFirst(context, *intents.toTypedArray())
    }

    val actions = OnboardingActions(
        confirmAdult = controller::confirmAdult,
        declareUnderAge = controller::declareUnderAge,
        closeApp = { context.findActivity()?.finishAndRemoveTask() },
        acceptNotice = controller::acceptNotice,
        signInWithGoogle = { context.findActivity()?.let { controller.signInWithGoogle(it) } },
        continueWithoutGoogle = controller::continueWithoutGoogle,
        request = { step ->
            val snapshot = controller.snapshot()
            val firstTime = step !in snapshot.answers.attempted
            controller.opened(step)
            when (step) {
                OnboardingStep.NOTIFICATIONS ->
                    if (snapshot.device.notificationsAreRuntimePermission && firstTime) {
                        notificationPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        open(PermissionIntents.appNotificationSettings(context))
                    }
                OnboardingStep.NOTIFICATION_ACCESS -> open(PermissionIntents.notificationAccess(context))
                OnboardingStep.OVERLAY -> open(PermissionIntents.overlay(context))
                OnboardingStep.LOCATION ->
                    if (firstTime) {
                        locationPrompt.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        open(PermissionIntents.appInfo(context))
                    }
                OnboardingStep.BATTERY -> open(PermissionIntents.batteryExemption(context))
                else -> Unit
            }
        },
        finish = controller::finish,
        openAppInfo = { open(PermissionIntents.appInfo(context)) },
        openOemSettings = { open(OemGuidance.settingsIntents(context, state.oemFamily)) },
        runHealthCheck = { controller.runHealthCheck() },
        skipHealthCheck = controller::skipHealthCheck,
        start = {
            controller.complete()
            onFinished()
        },
    )
    OnboardingScreen(state, actions)
}

/**
 * The one-time Google sign-in offer at the first "Read fares" (route `signin`). Either answer marks it offered and
 * returns; a successful sign-in also returns.
 */
@Composable
fun SignInOfferRoute(graph: AppGraph, onDone: () -> Unit) {
    val shell = graph.appShell
    val controller = rememberScopedModel("signin-offer") { scope -> shell.onboardingController(scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val auth by graph.authGateway.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(auth) {
        if (auth is AuthState.Google) {
            shell.signInReoffer.markOffered()
            onDone()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SignInStep(
                status = state.signIn,
                onGoogle = { context.findActivity()?.let { controller.signInWithGoogle(it) } },
                onManual = {
                    shell.signInReoffer.markOffered()
                    onDone()
                },
            )
        }
    }
}
