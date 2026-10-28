package app.ridemate.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ridemate.AppGraph
import app.ridemate.onboarding.PermissionIntents
import app.ridemate.ui.FeatureNavigator
import app.ridemate.ui.SystemIntents
import app.ridemate.ui.rememberScopedModel

/** Home wired to the graph: model, navigation and the system screens each fix-it card opens. */
@Composable
fun HomeRoute(graph: AppGraph, navigator: FeatureNavigator) {
    val shell = graph.appShell
    val model = rememberScopedModel("home") { scope ->
        HomeModel(
            auth = graph.authGateway.state,
            killSwitches = graph.configRepository.killSwitches,
            updates = graph.updateChecker.state,
            activeSessions = graph.db.sessionDao().observeActive(),
            api = graph.apiClient,
            gate = { shell.preSessionGate },
            scope = scope,
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    HomeScreen(
        state = state,
        actions = HomeActions(
            compare = { model.compare(navigator::tripInput) },
            demo = navigator::demo,
            openSession = { navigator.guardian(it) },
            settings = navigator::settings,
            signIn = navigator::signInOffer,
            retryAi = { model.refreshAi() },
            downloadUpdate = { SystemIntents.openHttps(context, it) },
            fix = { issue ->
                when (issue) {
                    PreSessionIssue.LISTENER_OFF -> SystemIntents.startFirst(context, *PermissionIntents.notificationAccess(context).toTypedArray())
                    PreSessionIssue.OVERLAY_OFF -> SystemIntents.startFirst(context, *PermissionIntents.overlay(context).toTypedArray())
                    PreSessionIssue.NOTIFICATIONS_OFF ->
                        SystemIntents.startFirst(context, *PermissionIntents.appNotificationSettings(context).toTypedArray())
                    PreSessionIssue.BATTERY_OPTIMISED -> SystemIntents.startFirst(context, *PermissionIntents.batteryExemption(context).toTypedArray())
                    PreSessionIssue.LOCATION_OFF -> SystemIntents.startFirst(context, *PermissionIntents.appInfo(context).toTypedArray())
                    PreSessionIssue.NO_SUPPORTED_APP -> {
                        model.dismissCompareCheck()
                        navigator.demo()
                    }
                    PreSessionIssue.OFFLINE -> model.compare(navigator::tripInput)
                }
            },
            continueAnyway = { model.continueAnyway(navigator::tripInput) },
            dismissCheck = model::dismissCompareCheck,
        ),
    )
}
