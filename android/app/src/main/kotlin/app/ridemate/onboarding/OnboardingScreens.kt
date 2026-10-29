package app.ridemate.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmSpacing
import app.ridemate.ui.components.BigPrimaryButton
import app.ridemate.ui.components.Hero
import app.ridemate.ui.components.HeroIllustration
import app.ridemate.ui.components.IconWell
import app.ridemate.ui.components.InfoSheet
import app.ridemate.ui.components.InlineBanner
import app.ridemate.ui.components.QuietButton
import app.ridemate.ui.components.QuietStyle
import app.ridemate.ui.components.RmCard
import app.ridemate.ui.components.RmListDivider
import app.ridemate.ui.components.RmListGroup
import app.ridemate.ui.components.RmListRow
import app.ridemate.ui.components.RmPill
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.StepDots
import app.ridemate.ui.components.WhyButton

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

/** "Step 3 of 10" for the progress row; null hides it (the block, the end, the standalone sign-in offer). */
private data class Progress(val position: Int, val total: Int)

/**
 * The screen for [state]'s current step (docs/design/ui-redesign.md §4 Onboarding): step dots, a hero
 * illustration, one short title and subtitle, and the big action in the thumb zone. Explanations and legal text
 * sit one tap away in "Why?" sheets. Stateless: everything comes from [state] and goes to [actions].
 */
@Composable
fun OnboardingScreen(state: OnboardingUiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    val progress = state.position?.let { Progress(it, state.total) }
    Box(modifier.fillMaxSize()) {
        // Keyed by step: each step opens scrolled to the top with its sheets closed.
        key(state.step) { StepPage(progress, state, actions) }
    }
}

@Composable
private fun StepPage(progress: Progress?, state: OnboardingUiState, actions: OnboardingActions) {
    when (state.step) {
        OnboardingStep.AGE_GATE -> AgeGate(progress, actions)
        OnboardingStep.UNDER_AGE_BLOCKED -> UnderAgeBlocked(actions)
        OnboardingStep.PRIVACY_NOTICE -> NoticeStep(progress, actions)
        OnboardingStep.SIGN_IN -> SignInPage(progress, state.signIn, actions.signInWithGoogle, actions.continueWithoutGoogle)
        OnboardingStep.NOTIFICATIONS,
        OnboardingStep.NOTIFICATION_ACCESS,
        OnboardingStep.OVERLAY,
        OnboardingStep.LOCATION,
        -> PermissionStep(progress, state, state.step, actions)
        OnboardingStep.RESTRICTED_SETTINGS -> RestrictedSettingsStep(progress, state, actions)
        OnboardingStep.BATTERY -> BatteryStep(progress, state, actions)
        OnboardingStep.HEALTH_CHECK -> HealthCheckStep(progress, state, actions)
        OnboardingStep.DONE -> DoneStep(state, actions)
    }
}

/**
 * One onboarding page: progress, hero, title (+ ⓘ), subtitle, optional [content], and [buttons] pinned at the
 * bottom. The body scrolls, so nothing clips at 200 % font size.
 */
@Composable
private fun OnbPage(
    progress: Progress?,
    hero: Hero,
    title: String,
    subtitle: String?,
    onWhy: (() -> Unit)?,
    buttons: @Composable ColumnScope.() -> Unit,
    heroHeight: Dp = 160.dp,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = RideMateTheme.colors
    Surface(Modifier.fillMaxSize(), color = colors.canvas, contentColor = colors.ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = RmSpacing.screen, vertical = RmSpacing.l),
                verticalArrangement = Arrangement.spacedBy(RmSpacing.m),
            ) {
                if (progress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The dots are visual; the words below are what TalkBack reads (once).
                        Box(Modifier.clearAndSetSemantics {}) { StepDots(progress.total, progress.position - 1) }
                        Spacer(Modifier.width(RmSpacing.m))
                        Text(
                            stringResource(R.string.onb_step_of, progress.position, progress.total),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.muted,
                        )
                    }
                }
                HeroIllustration(hero, Modifier.align(Alignment.CenterHorizontally), height = heroHeight)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (onWhy != null) {
                        WhyButton(onWhy, contentDescription = stringResource(R.string.onb_why_about, title))
                    }
                }
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                }
                content()
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = RmSpacing.screen, vertical = RmSpacing.m),
                verticalArrangement = Arrangement.spacedBy(RmSpacing.s),
                content = buttons,
            )
        }
    }
}

/** A plain-text "Why?" sheet built from string resources. */
@Composable
private fun WhySheet(title: String, onDismiss: () -> Unit, @StringRes vararg bodies: Int, icon: ImageVector = Icons.Rounded.Shield) {
    InfoSheet(
        onDismiss = onDismiss,
        title = title,
        body = bodies.map { stringResource(it) }.joinToString("\n\n"),
        icon = icon,
    )
}

@Composable
private fun AgeGate(progress: Progress?, actions: OnboardingActions) {
    var why by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.onb_age_title)
    OnbPage(
        progress = progress,
        hero = Hero.WELCOME,
        title = title,
        subtitle = stringResource(R.string.onb_age_sub),
        onWhy = { why = true },
        buttons = {
            BigPrimaryButton(stringResource(R.string.onb_age_yes), actions.confirmAdult)
            QuietButton(stringResource(R.string.onb_age_no), actions.declareUnderAge, fillWidth = true)
        },
    )
    // The full 18+ notice, verbatim.
    if (why) WhySheet(title, { why = false }, R.string.onb_age_why)
}

@Composable
private fun UnderAgeBlocked(actions: OnboardingActions) {
    OnbPage(
        progress = null,
        hero = Hero.EMPTY,
        title = stringResource(R.string.onb_blocked_title),
        subtitle = null,
        onWhy = null,
        buttons = { BigPrimaryButton(stringResource(R.string.onb_blocked_close), actions.closeApp) },
    ) {
        RmCard(tone = RmTone.DANGER) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(Icons.Rounded.Block, tone = RmTone.DANGER)
                Spacer(Modifier.width(RmSpacing.m))
                Text(
                    stringResource(R.string.onb_blocked_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(stringResource(R.string.onb_blocked_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NoticeStep(progress: Progress?, actions: OnboardingActions) {
    var full by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.onb_privacy_title)
    OnbPage(
        progress = progress,
        hero = Hero.PRIVACY,
        title = title,
        subtitle = null,
        onWhy = null,
        heroHeight = 140.dp,
        buttons = {
            BigPrimaryButton(stringResource(R.string.onb_notice_accept), actions.acceptNotice)
            QuietButton(stringResource(R.string.onb_notice_decline), actions.closeApp, fillWidth = true, style = QuietStyle.Text)
        },
    ) {
        RmListGroup {
            RmListRow(stringResource(R.string.onb_privacy_blur), icon = Icons.Rounded.VisibilityOff, iconTone = RmTone.BRAND)
            RmListDivider()
            RmListRow(stringResource(R.string.onb_notice_usa_callout), icon = Icons.Rounded.Cloud, iconTone = RmTone.WARN)
            RmListDivider()
            RmListRow(stringResource(R.string.onb_privacy_erase), icon = Icons.Rounded.Delete, iconTone = RmTone.BRAND)
        }
        QuietButton(stringResource(R.string.onb_privacy_read_full), { full = true }, icon = Icons.Rounded.Shield, fillWidth = true)
    }
    if (full) {
        // Every DPDP line, in full (the same text as Settings → Privacy notice).
        InfoSheet(
            onDismiss = { full = false },
            title = stringResource(R.string.onb_notice_title),
            body = stringResource(R.string.onb_notice_why),
            icon = Icons.Rounded.Shield,
            primaryLabel = stringResource(R.string.onb_notice_accept),
            onPrimary = {
                full = false
                actions.acceptNotice()
            },
        ) {
            PrivacyNoticeContent()
        }
    }
}

/** Google sign-in, skippable to manual mode. Also the standalone "sign in" offer at the first Read fares. */
@Composable
fun SignInStep(status: SignInStatus, onGoogle: () -> Unit, onManual: () -> Unit) {
    SignInPage(null, status, onGoogle, onManual)
}

@Composable
private fun SignInPage(progress: Progress?, status: SignInStatus, onGoogle: () -> Unit, onManual: () -> Unit) {
    var why by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.onb_signin_title)
    val busy = status == SignInStatus.InProgress
    OnbPage(
        progress = progress,
        hero = Hero.SIGN_IN,
        title = title,
        subtitle = stringResource(R.string.onb_signin_manual_note),
        onWhy = { why = true },
        buttons = {
            BigPrimaryButton(
                if (busy) stringResource(R.string.onb_signin_busy) else stringResource(R.string.onb_signin_google),
                onGoogle,
                icon = Icons.Rounded.AccountCircle,
                loading = busy,
            )
            QuietButton(stringResource(R.string.onb_signin_skip), onManual, enabled = !busy, fillWidth = true)
        },
    ) {
        if (status is SignInStatus.Problem) {
            InlineBanner(
                text = stringResource(R.string.onb_signin_problem_title) + " · " + stringResource(signInProblemRes(status.problem)),
                tone = RmTone.WARN,
            )
        }
    }
    if (why) WhySheet(title, { why = false }, R.string.onb_signin_why, R.string.onb_signin_manual_full, icon = Icons.Rounded.AccountCircle)
}

fun signInProblemRes(problem: SignInProblem): Int = when (problem) {
    SignInProblem.CANCELLED -> R.string.onb_signin_cancelled
    SignInProblem.NO_GOOGLE_ACCOUNT -> R.string.onb_signin_no_account
    SignInProblem.OFFLINE -> R.string.onb_signin_offline
    SignInProblem.FAILED -> R.string.onb_signin_failed
}

/** "Allowed" / "Not allowed yet" as an icon + word pill; TalkBack reads "Notifications: Allowed". */
@Composable
fun PermissionStatusChip(permission: AppPermission, granted: Boolean, modifier: Modifier = Modifier) {
    val status = stringResource(if (granted) R.string.onb_status_allowed else R.string.onb_status_not_allowed)
    RmPill(
        text = status,
        tone = if (granted) RmTone.GOOD else RmTone.WARN,
        contentDescription = stringResource(permissionNameRes(permission)) + ": " + status,
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

private fun permissionIcon(permission: AppPermission): ImageVector = when (permission) {
    AppPermission.POST_NOTIFICATIONS -> Icons.Rounded.Notifications
    AppPermission.NOTIFICATION_ACCESS -> Icons.Rounded.NotificationsActive
    AppPermission.OVERLAY -> Icons.Rounded.PictureInPicture
    AppPermission.LOCATION -> Icons.Rounded.LocationOn
    AppPermission.BATTERY_EXEMPTION -> Icons.Rounded.BatteryChargingFull
}

/** The full reason (and where-to-tap hint) behind each permission, for its "Why?" sheet. */
private fun permissionWhy(permission: AppPermission): List<Int> = when (permission) {
    AppPermission.POST_NOTIFICATIONS -> listOf(R.string.onb_notif_why)
    AppPermission.NOTIFICATION_ACCESS -> listOf(R.string.onb_access_why, R.string.onb_access_hint)
    AppPermission.OVERLAY -> listOf(R.string.onb_overlay_why, R.string.onb_overlay_hint)
    AppPermission.LOCATION -> listOf(R.string.onb_location_why)
    AppPermission.BATTERY_EXEMPTION -> listOf(R.string.onb_battery_why)
}

private data class PermissionCopy(val title: Int, val subtitle: Int, val action: Int, val notNow: Int)

private fun copyFor(step: OnboardingStep): PermissionCopy = when (step) {
    OnboardingStep.NOTIFICATIONS -> PermissionCopy(R.string.onb_notif_title, R.string.onb_notif_sub, R.string.onb_notif_action, R.string.onb_not_now)
    OnboardingStep.NOTIFICATION_ACCESS -> PermissionCopy(R.string.onb_access_title, R.string.onb_access_sub, R.string.onb_access_action, R.string.onb_not_now)
    OnboardingStep.OVERLAY -> PermissionCopy(R.string.onb_overlay_title, R.string.onb_overlay_sub, R.string.onb_overlay_action, R.string.onb_not_now)
    OnboardingStep.LOCATION -> PermissionCopy(R.string.onb_location_title, R.string.onb_location_sub, R.string.onb_location_action, R.string.onb_location_not_now)
    OnboardingStep.BATTERY -> PermissionCopy(R.string.onb_battery_title, R.string.onb_battery_sub, R.string.onb_battery_action, R.string.onb_not_now)
    else -> error("not a permission step: $step")
}

/**
 * The permissions as one checklist (icon, name, ✓ / not-yet pill, ⓘ why-sheet). [current] is highlighted;
 * [only] limits the rows (the restricted-settings guide shows just the two blocked switches).
 */
@Composable
private fun PermissionChecklist(
    state: OnboardingUiState,
    current: AppPermission?,
    modifier: Modifier = Modifier,
    only: List<AppPermission> = AppPermission.entries,
    rowAction: (@Composable (AppPermission) -> Unit)? = null,
) {
    var why by rememberSaveable { mutableStateOf<AppPermission?>(null) }
    RmListGroup(modifier) {
        only.forEachIndexed { index, permission ->
            if (index > 0) RmListDivider()
            val granted = state.isGranted(permission)
            val name = stringResource(permissionNameRes(permission))
            RmListRow(
                title = name,
                icon = permissionIcon(permission),
                iconTone = when {
                    granted -> RmTone.GOOD
                    permission == current -> RmTone.BRAND
                    else -> RmTone.NEUTRAL
                },
                trailing = {
                    PermissionStatusChip(permission, granted)
                    WhyButton({ why = permission }, contentDescription = stringResource(R.string.onb_why_about, name))
                },
            )
            if (!granted) rowAction?.invoke(permission)
        }
    }
    why?.let { permission ->
        InfoSheet(
            onDismiss = { why = null },
            title = stringResource(permissionNameRes(permission)),
            body = permissionWhy(permission).map { stringResource(it) }.joinToString("\n\n"),
            icon = permissionIcon(permission),
        )
    }
}

@Composable
private fun PermissionStep(progress: Progress?, state: OnboardingUiState, step: OnboardingStep, actions: OnboardingActions) {
    val permission = OnboardingFlow.permissionOf(step) ?: return
    val copy = copyFor(step)
    var greyedWhy by rememberSaveable { mutableStateOf(false) }
    val blockedHere = state.device.restrictedSettingsPossible && step in state.answers.attempted && !state.isGranted(permission) &&
        (step == OnboardingStep.NOTIFICATION_ACCESS || step == OnboardingStep.OVERLAY)
    OnbPage(
        progress = progress,
        hero = Hero.PERMISSIONS,
        heroHeight = 120.dp,
        title = stringResource(copy.title),
        subtitle = stringResource(copy.subtitle),
        onWhy = null,
        buttons = {
            BigPrimaryButton(stringResource(copy.action), { actions.request(step) }, icon = permissionIcon(permission))
            QuietButton(stringResource(copy.notNow), { actions.finish(step) }, fillWidth = true, style = QuietStyle.Text)
        },
    ) {
        if (blockedHere) {
            InlineBanner(
                text = stringResource(R.string.onb_greyed_title),
                tone = RmTone.WARN,
                actionLabel = stringResource(R.string.onb_greyed_action),
                onAction = { greyedWhy = true },
            )
        }
        PermissionChecklist(state, current = permission)
    }
    if (greyedWhy) {
        InfoSheet(
            onDismiss = { greyedWhy = false },
            title = stringResource(R.string.onb_greyed_title),
            body = stringResource(R.string.onb_greyed_body),
            icon = Icons.Rounded.LockOpen,
            tone = RmTone.WARN,
            primaryLabel = stringResource(R.string.onb_greyed_action),
            onPrimary = {
                greyedWhy = false
                actions.finish(step)
            },
        )
    }
}

/** "Allow restricted settings" (sideloaded Android 13+, only when a switch stayed blocked): an illustrated guide. */
@Composable
private fun RestrictedSettingsStep(progress: Progress?, state: OnboardingUiState, actions: OnboardingActions) {
    var why by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.onb_restricted_title)
    OnbPage(
        progress = progress,
        hero = Hero.PERMISSIONS,
        heroHeight = 120.dp,
        title = title,
        subtitle = stringResource(R.string.onb_restricted_sub),
        onWhy = { why = true },
        buttons = {
            BigPrimaryButton(stringResource(R.string.onb_restricted_open_app_info), actions.openAppInfo, icon = Icons.Rounded.LockOpen)
            QuietButton(
                stringResource(R.string.onb_continue),
                { actions.finish(OnboardingStep.RESTRICTED_SETTINGS) },
                fillWidth = true,
                style = QuietStyle.Text,
            )
        },
    ) {
        RmCard {
            Text(
                stringResource(R.string.onb_restricted_steps_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            stringResource(R.string.onb_restricted_steps).lines().filter { it.isNotBlank() }.forEach { line ->
                Text(line.trim(), style = MaterialTheme.typography.bodyLarge)
            }
        }
        val switches = listOf(OnboardingStep.NOTIFICATION_ACCESS, OnboardingStep.OVERLAY)
        PermissionChecklist(
            state,
            current = null,
            only = switches.mapNotNull { OnboardingFlow.permissionOf(it) },
            rowAction = { permission ->
                val step = switches.first { OnboardingFlow.permissionOf(it) == permission }
                QuietButton(
                    stringResource(copyFor(step).action),
                    { actions.request(step) },
                    fillWidth = true,
                    modifier = Modifier.padding(horizontal = RmSpacing.l, vertical = RmSpacing.xs),
                )
            },
        )
    }
    if (why) WhySheet(title, { why = false }, R.string.onb_restricted_why, icon = Icons.Rounded.LockOpen)
}

@Composable
private fun BatteryStep(progress: Progress?, state: OnboardingUiState, actions: OnboardingActions) {
    val granted = state.isGranted(AppPermission.BATTERY_EXEMPTION)
    var oem by rememberSaveable { mutableStateOf(false) }
    val oemSteps = OemGuidance.stepsRes(state.oemFamily)
    OnbPage(
        progress = progress,
        hero = Hero.PERMISSIONS,
        heroHeight = 120.dp,
        title = stringResource(R.string.onb_battery_title),
        subtitle = stringResource(R.string.onb_battery_sub),
        onWhy = null,
        buttons = {
            if (!granted) {
                BigPrimaryButton(
                    stringResource(R.string.onb_battery_action),
                    { actions.request(OnboardingStep.BATTERY) },
                    icon = Icons.Rounded.BatteryChargingFull,
                )
                QuietButton(stringResource(R.string.onb_not_now), { actions.finish(OnboardingStep.BATTERY) }, fillWidth = true, style = QuietStyle.Text)
            } else {
                BigPrimaryButton(stringResource(R.string.onb_continue), { actions.finish(OnboardingStep.BATTERY) })
            }
        },
    ) {
        if (oemSteps != null) {
            InlineBanner(
                text = stringResource(R.string.onb_oem_title),
                tone = RmTone.WARN,
                icon = Icons.Rounded.PhoneAndroid,
                actionLabel = stringResource(R.string.onb_oem_show),
                onAction = { oem = true },
            )
        }
        PermissionChecklist(state, current = AppPermission.BATTERY_EXEMPTION)
    }
    if (oem && oemSteps != null) {
        InfoSheet(
            onDismiss = { oem = false },
            title = stringResource(R.string.onb_oem_title),
            body = stringResource(oemSteps),
            icon = Icons.Rounded.PhoneAndroid,
            tone = RmTone.WARN,
            primaryLabel = stringResource(R.string.onb_oem_open),
            onPrimary = {
                oem = false
                actions.openOemSettings()
            },
        )
    }
}

private data class HealthFix(val title: Int, val body: Int, val action: Int, val step: OnboardingStep)

private fun fixFor(result: HealthResult): HealthFix? = when (result) {
    HealthResult.SEEN -> null
    HealthResult.NOT_SEEN -> HealthFix(
        R.string.onb_health_not_seen_title, R.string.onb_health_not_seen_body, R.string.onb_access_action, OnboardingStep.NOTIFICATION_ACCESS,
    )
    HealthResult.NOTIFICATIONS_BLOCKED -> HealthFix(
        R.string.onb_health_notif_blocked_title, R.string.onb_health_notif_blocked_body, R.string.onb_notif_action, OnboardingStep.NOTIFICATIONS,
    )
    HealthResult.ACCESS_MISSING -> HealthFix(
        R.string.onb_health_access_missing_title, R.string.onb_health_access_missing_body, R.string.onb_access_action,
        OnboardingStep.NOTIFICATION_ACCESS,
    )
}

@Composable
private fun HealthCheckStep(progress: Progress?, state: OnboardingUiState, actions: OnboardingActions) {
    var why by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.onb_health_title)
    val health = state.health
    val result = (health as? HealthStatus.Finished)?.result
    val fix = result?.let { fixFor(it) }
    OnbPage(
        progress = progress,
        hero = Hero.ALL_SET,
        // Once there is a result, the hero shrinks so the result sits above the fold.
        heroHeight = if (result == null) 140.dp else 72.dp,
        title = title,
        subtitle = stringResource(R.string.onb_health_sub),
        onWhy = { why = true },
        buttons = {
            when {
                health == HealthStatus.Idle ->
                    BigPrimaryButton(stringResource(R.string.onb_health_run), actions.runHealthCheck, icon = Icons.Rounded.CheckCircle)
                health == HealthStatus.Running ->
                    BigPrimaryButton(stringResource(R.string.onb_health_running), {}, loading = true)
                fix != null -> {
                    BigPrimaryButton(stringResource(fix.action), { actions.request(fix.step) })
                    QuietButton(stringResource(R.string.onb_health_retry), actions.runHealthCheck, fillWidth = true)
                    QuietButton(stringResource(R.string.onb_health_skip), actions.skipHealthCheck, fillWidth = true, style = QuietStyle.Text)
                }
            }
        },
    ) {
        when {
            result == HealthResult.SEEN ->
                RmPill(stringResource(R.string.onb_health_ok), tone = RmTone.GOOD)
            fix != null -> RmCard(
                tone = RmTone.WARN,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconWell(Icons.Rounded.Warning, tone = RmTone.WARN)
                    Spacer(Modifier.width(RmSpacing.m))
                    Text(
                        stringResource(fix.title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                Text(stringResource(fix.body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (why) WhySheet(title, { why = false }, R.string.onb_health_why, icon = Icons.Rounded.CheckCircle)
}

@Composable
private fun DoneStep(state: OnboardingUiState, actions: OnboardingActions) {
    var why by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.onb_done_title)
    val skipped = state.answers.healthCheck == HealthCheckOutcome.SKIPPED
    OnbPage(
        progress = null,
        hero = Hero.ALL_SET,
        heroHeight = 140.dp,
        title = title,
        subtitle = null,
        onWhy = { why = true },
        buttons = { BigPrimaryButton(stringResource(R.string.onb_done_start), actions.start) },
    ) {
        when (state.answers.healthCheck) {
            HealthCheckOutcome.PASSED -> RmPill(stringResource(R.string.onb_done_seen), tone = RmTone.GOOD)
            HealthCheckOutcome.SKIPPED -> InlineBanner(
                text = stringResource(R.string.onb_done_health_skipped_title),
                tone = RmTone.WARN,
                actionLabel = stringResource(R.string.onb_why_short),
                onAction = { why = true },
            )
            null -> Unit
        }
        PermissionChecklist(state, current = null)
    }
    if (why) {
        if (skipped) {
            WhySheet(title, { why = false }, R.string.onb_done_body, R.string.onb_done_health_skipped_body, icon = Icons.Rounded.CheckCircle)
        } else {
            WhySheet(title, { why = false }, R.string.onb_done_body, icon = Icons.Rounded.CheckCircle)
        }
    }
}
