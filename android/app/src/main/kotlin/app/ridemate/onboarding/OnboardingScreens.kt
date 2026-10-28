package app.ridemate.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.ui.ChipTone
import app.ridemate.ui.FixItCard
import app.ridemate.ui.InfoCard
import app.ridemate.ui.PrimaryButton
import app.ridemate.ui.SecondaryButton
import app.ridemate.ui.StatusChip
import app.ridemate.ui.TextAction

/** Everything the onboarding screens can ask for; the route wires these to the controller and system screens. */
class OnboardingActions(
    val confirmAdult: () -> Unit = {},
    val declareUnderAge: () -> Unit = {},
    val closeApp: () -> Unit = {},
    val acceptNotice: () -> Unit = {},
    val signInWithGoogle: () -> Unit = {},
    val continueWithoutGoogle: () -> Unit = {},
    /** The step's main action: the runtime prompt, or its system settings screen. */
    val request: (OnboardingStep) -> Unit = {},
    /** "Not now" on a permission step / "Continue" on a guidance step. */
    val finish: (OnboardingStep) -> Unit = {},
    val openAppInfo: () -> Unit = {},
    val openOemSettings: () -> Unit = {},
    val runHealthCheck: () -> Unit = {},
    val skipHealthCheck: () -> Unit = {},
    val start: () -> Unit = {},
)

/** The screen for [state]'s current step. Stateless: everything comes from [state] and goes to [actions]. */
@Composable
fun OnboardingScreen(state: OnboardingUiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.position?.let {
                Text(
                    stringResource(R.string.onb_step_of, it, state.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state.step) {
                OnboardingStep.AGE_GATE -> AgeGate(actions)
                OnboardingStep.UNDER_AGE_BLOCKED -> UnderAgeBlocked(actions)
                OnboardingStep.PRIVACY_NOTICE -> NoticeStep(actions)
                OnboardingStep.SIGN_IN -> SignInStep(state.signIn, actions.signInWithGoogle, actions.continueWithoutGoogle)
                OnboardingStep.NOTIFICATIONS -> PermissionStep(state, OnboardingStep.NOTIFICATIONS, actions)
                OnboardingStep.NOTIFICATION_ACCESS -> PermissionStep(state, OnboardingStep.NOTIFICATION_ACCESS, actions)
                OnboardingStep.OVERLAY -> PermissionStep(state, OnboardingStep.OVERLAY, actions)
                OnboardingStep.RESTRICTED_SETTINGS -> RestrictedSettingsStep(state, actions)
                OnboardingStep.LOCATION -> PermissionStep(state, OnboardingStep.LOCATION, actions)
                OnboardingStep.BATTERY -> BatteryStep(state, actions)
                OnboardingStep.HEALTH_CHECK -> HealthCheckStep(state, actions)
                OnboardingStep.DONE -> DoneStep(state, actions)
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Why(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun AgeGate(actions: OnboardingActions) {
    StepTitle(stringResource(R.string.onb_age_title))
    Why(stringResource(R.string.onb_age_why))
    Spacer(Modifier.height(8.dp))
    PrimaryButton(stringResource(R.string.onb_age_yes), actions.confirmAdult)
    SecondaryButton(stringResource(R.string.onb_age_no), actions.declareUnderAge)
}

@Composable
private fun UnderAgeBlocked(actions: OnboardingActions) {
    StepTitle(stringResource(R.string.onb_blocked_title))
    InfoCard(
        title = stringResource(R.string.onb_blocked_card_title),
        body = stringResource(R.string.onb_blocked_body),
        tone = ChipTone.BAD,
    )
    PrimaryButton(stringResource(R.string.onb_blocked_close), actions.closeApp)
}

@Composable
private fun NoticeStep(actions: OnboardingActions) {
    StepTitle(stringResource(R.string.onb_notice_title))
    Why(stringResource(R.string.onb_notice_why))
    PrivacyNoticeContent()
    PrimaryButton(stringResource(R.string.onb_notice_accept), actions.acceptNotice)
    TextAction(stringResource(R.string.onb_notice_decline), actions.closeApp)
}

/** Google sign-in, skippable to manual mode. Also the standalone "sign in" offer at the first Read fares. */
@Composable
fun SignInStep(status: SignInStatus, onGoogle: () -> Unit, onManual: () -> Unit) {
    StepTitle(stringResource(R.string.onb_signin_title))
    Why(stringResource(R.string.onb_signin_why))
    Text(stringResource(R.string.onb_signin_manual_note), style = MaterialTheme.typography.bodyMedium)
    if (status is SignInStatus.Problem) {
        InfoCard(
            title = stringResource(R.string.onb_signin_problem_title),
            body = stringResource(signInProblemRes(status.problem)),
            tone = ChipTone.WARNING,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val busy = status == SignInStatus.InProgress
    if (busy) BusyLine(stringResource(R.string.onb_signin_busy))
    PrimaryButton(stringResource(R.string.onb_signin_google), onGoogle, enabled = !busy)
    SecondaryButton(stringResource(R.string.onb_signin_skip), onManual, enabled = !busy)
}

fun signInProblemRes(problem: SignInProblem): Int = when (problem) {
    SignInProblem.CANCELLED -> R.string.onb_signin_cancelled
    SignInProblem.NO_GOOGLE_ACCOUNT -> R.string.onb_signin_no_account
    SignInProblem.OFFLINE -> R.string.onb_signin_offline
    SignInProblem.FAILED -> R.string.onb_signin_failed
}

@Composable
private fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Allowed" / "Not allowed yet" with an icon, for [permission]. */
@Composable
fun PermissionStatusChip(permission: AppPermission, granted: Boolean, modifier: Modifier = Modifier) {
    StatusChip(
        text = stringResource(if (granted) R.string.onb_status_allowed else R.string.onb_status_not_allowed),
        tone = if (granted) ChipTone.GOOD else ChipTone.WARNING,
        label = stringResource(permissionNameRes(permission)),
        modifier = modifier,
    )
}

fun permissionNameRes(permission: AppPermission): Int = when (permission) {
    AppPermission.POST_NOTIFICATIONS -> R.string.onb_perm_notifications
    AppPermission.NOTIFICATION_ACCESS -> R.string.onb_perm_notification_access
    AppPermission.OVERLAY -> R.string.onb_perm_overlay
    AppPermission.LOCATION -> R.string.onb_perm_location
    AppPermission.BATTERY_EXEMPTION -> R.string.onb_perm_battery
}

private data class PermissionCopy(val title: Int, val why: Int, val action: Int, val notNow: Int, val hint: Int?)

private fun copyFor(step: OnboardingStep): PermissionCopy = when (step) {
    OnboardingStep.NOTIFICATIONS -> PermissionCopy(
        R.string.onb_notif_title, R.string.onb_notif_why, R.string.onb_notif_action, R.string.onb_not_now, null,
    )
    OnboardingStep.NOTIFICATION_ACCESS -> PermissionCopy(
        R.string.onb_access_title, R.string.onb_access_why, R.string.onb_access_action, R.string.onb_not_now, R.string.onb_access_hint,
    )
    OnboardingStep.OVERLAY -> PermissionCopy(
        R.string.onb_overlay_title, R.string.onb_overlay_why, R.string.onb_overlay_action, R.string.onb_not_now, R.string.onb_overlay_hint,
    )
    OnboardingStep.LOCATION -> PermissionCopy(
        R.string.onb_location_title, R.string.onb_location_why, R.string.onb_location_action, R.string.onb_location_not_now, null,
    )
    else -> error("not a permission step: $step")
}

@Composable
private fun PermissionStep(state: OnboardingUiState, step: OnboardingStep, actions: OnboardingActions) {
    val permission = OnboardingFlow.permissionOf(step) ?: return
    val copy = copyFor(step)
    val granted = state.isGranted(permission)
    StepTitle(stringResource(copy.title))
    Why(stringResource(copy.why))
    PermissionStatusChip(permission, granted)
    copy.hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    val blockedHere = state.device.restrictedSettingsPossible && step in state.answers.attempted && !granted &&
        (step == OnboardingStep.NOTIFICATION_ACCESS || step == OnboardingStep.OVERLAY)
    if (blockedHere) {
        InfoCard(
            title = stringResource(R.string.onb_greyed_title),
            body = stringResource(R.string.onb_greyed_body),
            tone = ChipTone.WARNING,
        ) {
            SecondaryButton(stringResource(R.string.onb_greyed_action), { actions.finish(step) })
        }
    }
    PrimaryButton(stringResource(copy.action), { actions.request(step) })
    TextAction(stringResource(copy.notNow), { actions.finish(step) })
}

@Composable
private fun RestrictedSettingsStep(state: OnboardingUiState, actions: OnboardingActions) {
    StepTitle(stringResource(R.string.onb_restricted_title))
    Why(stringResource(R.string.onb_restricted_why))
    InfoCard(title = stringResource(R.string.onb_restricted_steps_title), body = stringResource(R.string.onb_restricted_steps))
    PrimaryButton(stringResource(R.string.onb_restricted_open_app_info), actions.openAppInfo)
    listOf(OnboardingStep.NOTIFICATION_ACCESS, OnboardingStep.OVERLAY).forEach { step ->
        val permission = OnboardingFlow.permissionOf(step)!!
        val granted = state.isGranted(permission)
        PermissionStatusChip(permission, granted)
        if (!granted) SecondaryButton(stringResource(copyFor(step).action), { actions.request(step) })
    }
    TextAction(stringResource(R.string.onb_continue), { actions.finish(OnboardingStep.RESTRICTED_SETTINGS) })
}

@Composable
private fun BatteryStep(state: OnboardingUiState, actions: OnboardingActions) {
    val granted = state.isGranted(AppPermission.BATTERY_EXEMPTION)
    StepTitle(stringResource(R.string.onb_battery_title))
    Why(stringResource(R.string.onb_battery_why))
    PermissionStatusChip(AppPermission.BATTERY_EXEMPTION, granted)
    if (!granted) PrimaryButton(stringResource(R.string.onb_battery_action), { actions.request(OnboardingStep.BATTERY) })
    OemGuidance.stepsRes(state.oemFamily)?.let { steps ->
        InfoCard(title = stringResource(R.string.onb_oem_title), body = stringResource(steps)) {
            SecondaryButton(stringResource(R.string.onb_oem_open), actions.openOemSettings)
        }
    }
    if (granted) {
        PrimaryButton(stringResource(R.string.onb_continue), { actions.finish(OnboardingStep.BATTERY) })
    } else {
        TextAction(stringResource(R.string.onb_not_now), { actions.finish(OnboardingStep.BATTERY) })
    }
}

@Composable
private fun HealthCheckStep(state: OnboardingUiState, actions: OnboardingActions) {
    StepTitle(stringResource(R.string.onb_health_title))
    Why(stringResource(R.string.onb_health_why))
    when (val health = state.health) {
        HealthStatus.Idle -> PrimaryButton(stringResource(R.string.onb_health_run), actions.runHealthCheck)
        HealthStatus.Running -> BusyLine(stringResource(R.string.onb_health_running))
        is HealthStatus.Finished -> when (health.result) {
            HealthResult.SEEN -> InfoCard(title = stringResource(R.string.onb_health_ok), body = null, tone = ChipTone.GOOD)
            HealthResult.NOT_SEEN -> FixItCard(
                title = stringResource(R.string.onb_health_not_seen_title),
                body = stringResource(R.string.onb_health_not_seen_body),
                actionLabel = stringResource(R.string.onb_access_action),
                onAction = { actions.request(OnboardingStep.NOTIFICATION_ACCESS) },
                secondaryLabel = stringResource(R.string.onb_health_retry),
                onSecondary = actions.runHealthCheck,
            )
            HealthResult.NOTIFICATIONS_BLOCKED -> FixItCard(
                title = stringResource(R.string.onb_health_notif_blocked_title),
                body = stringResource(R.string.onb_health_notif_blocked_body),
                actionLabel = stringResource(R.string.onb_notif_action),
                onAction = { actions.request(OnboardingStep.NOTIFICATIONS) },
                secondaryLabel = stringResource(R.string.onb_health_retry),
                onSecondary = actions.runHealthCheck,
            )
            HealthResult.ACCESS_MISSING -> FixItCard(
                title = stringResource(R.string.onb_health_access_missing_title),
                body = stringResource(R.string.onb_health_access_missing_body),
                actionLabel = stringResource(R.string.onb_access_action),
                onAction = { actions.request(OnboardingStep.NOTIFICATION_ACCESS) },
                secondaryLabel = stringResource(R.string.onb_health_retry),
                onSecondary = actions.runHealthCheck,
            )
        }
    }
    val failed = (state.health as? HealthStatus.Finished)?.result?.let { it != HealthResult.SEEN } ?: false
    if (failed) TextAction(stringResource(R.string.onb_health_skip), actions.skipHealthCheck)
}

@Composable
private fun DoneStep(state: OnboardingUiState, actions: OnboardingActions) {
    StepTitle(stringResource(R.string.onb_done_title))
    Why(stringResource(R.string.onb_done_body))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppPermission.entries.forEach { PermissionStatusChip(it, state.isGranted(it)) }
    }
    if (state.answers.healthCheck == HealthCheckOutcome.SKIPPED) {
        InfoCard(
            title = stringResource(R.string.onb_done_health_skipped_title),
            body = stringResource(R.string.onb_done_health_skipped_body),
            tone = ChipTone.WARNING,
        )
    }
    PrimaryButton(stringResource(R.string.onb_done_start), actions.start)
}
