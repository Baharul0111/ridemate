package app.ridemate.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.config.KillSwitches
import app.ridemate.core.model.Provider
import app.ridemate.ui.ChipTone
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing
import app.ridemate.ui.UiFormat
import app.ridemate.ui.components.BigPrimaryButton
import app.ridemate.ui.components.DemoTag
import app.ridemate.ui.components.IconWell
import app.ridemate.ui.components.InfoSheet
import app.ridemate.ui.components.PlaceRow
import app.ridemate.ui.components.ProviderAvatar
import app.ridemate.ui.components.QuietButton
import app.ridemate.ui.components.QuietStyle
import app.ridemate.ui.components.RmCard
import app.ridemate.ui.components.RmIconButton
import app.ridemate.ui.components.RmPill
import app.ridemate.ui.components.RmScaffold
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.WhereToBar
import app.ridemate.ui.components.WhyButton
import app.ridemate.ui.components.colors
import app.ridemate.ui.toRmTone
import app.ridemate.update.UpdateMessages
import app.ridemate.update.UpdateState

/** What Home can ask for; the route wires these to navigation and system screens. */
class HomeActions(
    val compare: () -> Unit = {},
    val demo: () -> Unit = {},
    val openSession: (String) -> Unit = {},
    val settings: () -> Unit = {},
    val signIn: () -> Unit = {},
    val retryAi: () -> Unit = {},
    val downloadUpdate: (String) -> Unit = {},
    val fix: (PreSessionIssue) -> Unit = {},
    val continueAnyway: () -> Unit = {},
    val dismissCheck: () -> Unit = {},
)

/** Stable test tags for Home's own pieces (the shared components carry RmTags). */
object HomeTags {
    const val COMPARE_TILE = "home_compare_tile"
    const val DEMO_TILE = "home_demo_tile"
    const val ACTIVE_RIDE = "home_active_ride"
    const val AI_BANNER = "home_ai_banner"
    const val ISSUE_CARD = "home_issue_card"
    const val RECENT_PLACES = "home_recent_places"
}

/** At most this many recent destinations sit under Where to? (design §4: 2–3 rows). */
const val HOME_MAX_RECENT = 3

/**
 * Home, "one glance, one tap" (docs/design/ui-redesign.md §4): Where to? on top, the active ride pinned above it,
 * Compare and Try the demo as two tiles, and status only when something is not normal. Explanations live behind
 * the ⓘ in the top bar and the "Why?" on each fix-it card.
 */
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions, recentPlaces: List<String> = emptyList()) {
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val checking = state.compare == CompareCheck.Checking
    RmScaffold(
        title = stringResource(R.string.app_name),
        onBack = null,
        actions = {
            RmIconButton(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.home_about_cd), { showAbout = true })
            RmIconButton(Icons.Rounded.Settings, stringResource(R.string.home_settings_cd), actions.settings)
        },
    ) {
        UpdateBanner(state.update, actions.downloadUpdate)
        state.activeSession?.let { ActiveRideCard(it, actions.openSession) }

        WhereToBar(onClick = { if (!checking) actions.compare() })
        RecentPlaces(recentPlaces.take(HOME_MAX_RECENT), enabled = !checking, onOpen = actions.compare)

        Row(
            horizontalArrangement = Arrangement.spacedBy(RmSpacing.cardGap),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            HomeTile(
                label = stringResource(R.string.home_compare),
                icon = Icons.AutoMirrored.Rounded.CompareArrows,
                tone = RmTone.BRAND,
                onClick = actions.compare,
                enabled = !checking,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag(HomeTags.COMPARE_TILE),
            )
            HomeTile(
                label = stringResource(R.string.home_try_demo),
                icon = Icons.Rounded.PlayArrow,
                tone = RmTone.DEMO,
                onClick = actions.demo,
                enabled = state.killSwitches.demoEnabled,
                contentDescription = stringResource(R.string.home_try_demo_cd),
                badge = { DemoTag() },
                modifier = Modifier.weight(1f).fillMaxHeight().testTag(HomeTags.DEMO_TILE),
            )
        }
        if (!state.killSwitches.demoEnabled) {
            Text(
                stringResource(R.string.home_demo_off),
                style = MaterialTheme.typography.bodyMedium,
                color = RideMateTheme.colors.muted,
            )
        }

        CompareCheckSection(state.compare, actions)
        AiBanner(state.ai, actions)
        KillSwitchStatusList(state.killSwitches, onlyOff = true)

        Text(
            stringResource(R.string.built_with_spark),
            style = MaterialTheme.typography.labelMedium,
            color = RideMateTheme.colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = RmSpacing.l, bottom = RmSpacing.l),
        )
    }
    if (showAbout) {
        InfoSheet(
            onDismiss = { showAbout = false },
            title = stringResource(R.string.home_about_title),
            body = stringResource(R.string.home_intro) + "\n\n" + stringResource(R.string.home_demo_body),
            icon = Icons.AutoMirrored.Rounded.HelpOutline,
            primaryLabel = stringResource(R.string.home_about_close),
            onPrimary = { showAbout = false },
        )
    }
}

/**
 * Recent destinations (phone only, 24 h) as icon-led rows under Where to?. A tap starts the same trip flow as
 * Where to? (the trip screen lists them again with their saved pin). Nothing shows when there are none.
 */
@Composable
private fun RecentPlaces(places: List<String>, enabled: Boolean, onOpen: () -> Unit) {
    if (places.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag(HomeTags.RECENT_PLACES)) {
        places.forEach { label ->
            PlaceRow(name = label, area = stringResource(R.string.home_recent_area), onClick = { if (enabled) onOpen() }, icon = Icons.Rounded.History)
        }
    }
}

/** A big square action tile: icon well + 1–3 word label (+ an optional corner badge). ≥ 48 dp, disabled when off. */
@Composable
private fun HomeTile(
    label: String,
    icon: ImageVector,
    tone: RmTone,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    val c = RideMateTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RmShapes.tile,
        color = c.sheet,
        contentColor = if (enabled) c.ink else c.muted,
        border = if (!c.isDark) BorderStroke(1.dp, c.line) else null,
        modifier = modifier
            .heightIn(min = 112.dp)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        Column(Modifier.padding(RmSpacing.card), verticalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            Row(verticalAlignment = Alignment.Top) {
                IconWell(icon, tone = if (enabled) tone else RmTone.NEUTRAL, size = 44.dp)
                Spacer(Modifier.weight(1f))
                badge?.invoke()
            }
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "Update available" with the published checksum, or the "no longer supported" notice (F5, goal 28). */
@Composable
fun UpdateBanner(update: UpdateState, onDownload: (String) -> Unit) {
    val context = LocalContext.current
    val headline = UpdateMessages.headline(context, update) ?: return
    val tone = if (update.forceUpdate || update.belowMinSupported) RmTone.DANGER else RmTone.WARN
    RmCard(tone = tone) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            IconWell(Icons.Rounded.Download, tone = tone)
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        UpdateMessages.checksum(context, update)?.let {
            Text(it, style = RideMateTheme.mono.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize))
        }
        val url = update.url
        if (url != null) {
            BigPrimaryButton(stringResource(R.string.home_update_download), { onDownload(url) }, icon = Icons.Rounded.Download)
        } else {
            Text(stringResource(R.string.home_update_no_link), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The ride in progress, pinned above everything: status, where to, which apps, one button to the guardian. */
@Composable
private fun ActiveRideCard(session: ActiveSessionSummary, onOpen: (String) -> Unit) {
    RmCard(tone = RmTone.GOOD, modifier = Modifier.testTag(HomeTags.ACTIVE_RIDE)) {
        RmPill(
            text = stringResource(R.string.home_session_title),
            tone = RmTone.GOOD,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.home_session_to, session.dropLabel),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
            session.apps.forEach { ProviderAvatar(it, size = 32.dp) }
            Text(
                stringResource(R.string.home_session_started, UiFormat.timeIst(session.startedAtUtc)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(start = RmSpacing.xs),
            )
        }
        BigPrimaryButton(stringResource(R.string.home_session_open), { onOpen(session.sessionId) })
    }
}

@Composable
private fun CompareCheckSection(check: CompareCheck, actions: HomeActions) {
    when (check) {
        CompareCheck.Idle -> Unit
        CompareCheck.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = RmSpacing.minTouch).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.size(RmSpacing.m))
            Text(stringResource(R.string.home_checking), style = MaterialTheme.typography.bodyMedium)
        }
        is CompareCheck.Blocked -> Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
            CheckHeading(stringResource(R.string.home_blocked_heading))
            check.report.blocking.forEach { IssueCard(it, ChipTone.BAD, actions.fix) }
            check.report.warnings.forEach { IssueCard(it, ChipTone.WARNING, actions.fix) }
            QuietButton(stringResource(R.string.home_check_close), actions.dismissCheck, style = QuietStyle.Text)
        }
        is CompareCheck.Warnings -> Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
            CheckHeading(stringResource(R.string.home_warnings_heading))
            check.report.warnings.forEach { IssueCard(it, ChipTone.WARNING, actions.fix) }
            QuietButton(stringResource(R.string.home_continue_anyway), actions.continueAnyway, fillWidth = true, style = QuietStyle.Outline)
        }
    }
}

@Composable
private fun CheckHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = RmSpacing.s).semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        },
    )
}

/**
 * A pre-session problem as a compact fix-it card: icon + short title + one button that fixes it. Why it matters
 * sits behind the ⓘ (InfoSheet), never as a paragraph on Home.
 */
@Composable
fun IssueCard(issue: PreSessionIssue, tone: ChipTone, onFix: (PreSessionIssue) -> Unit) {
    val copy = PreSessionCopy.of(issue)
    val title = stringResource(copy.title)
    val rmTone = tone.toRmTone()
    var showWhy by rememberSaveable(issue) { mutableStateOf(false) }
    RmCard(modifier = Modifier.testTag(HomeTags.ISSUE_CARD), padding = RmSpacing.m) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            IconWell(PreSessionCopy.icon(issue), tone = rmTone)
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            WhyButton({ showWhy = true }, contentDescription = stringResource(R.string.home_why_cd, title))
        }
        QuietButton(stringResource(copy.action), { onFix(issue) }, fillWidth = true)
    }
    if (showWhy) {
        InfoSheet(
            onDismiss = { showWhy = false },
            title = title,
            body = stringResource(copy.body),
            icon = PreSessionCopy.icon(issue),
            tone = rmTone,
            primaryLabel = stringResource(copy.action),
            onPrimary = {
                showWhy = false
                onFix(issue)
            },
        )
    }
}

/** Wording and icon of each pre-session issue (strings_home.xml). */
object PreSessionCopy {
    data class Copy(val title: Int, val body: Int, val action: Int)

    fun of(issue: PreSessionIssue): Copy = when (issue) {
        PreSessionIssue.LISTENER_OFF -> Copy(R.string.home_issue_listener_title, R.string.home_issue_listener_body, R.string.home_issue_listener_action)
        PreSessionIssue.OVERLAY_OFF -> Copy(R.string.home_issue_overlay_title, R.string.home_issue_overlay_body, R.string.home_issue_overlay_action)
        PreSessionIssue.NOTIFICATIONS_OFF -> Copy(R.string.home_issue_notif_title, R.string.home_issue_notif_body, R.string.home_issue_notif_action)
        PreSessionIssue.NO_SUPPORTED_APP -> Copy(R.string.home_issue_no_app_title, R.string.home_issue_no_app_body, R.string.home_try_demo)
        PreSessionIssue.BATTERY_OPTIMISED -> Copy(R.string.home_issue_battery_title, R.string.home_issue_battery_body, R.string.home_issue_battery_action)
        PreSessionIssue.LOCATION_OFF -> Copy(R.string.home_issue_location_title, R.string.home_issue_location_body, R.string.home_issue_location_action)
        PreSessionIssue.OFFLINE -> Copy(R.string.home_issue_offline_title, R.string.home_issue_offline_body, R.string.home_issue_offline_action)
    }

    fun icon(issue: PreSessionIssue): ImageVector = when (issue) {
        PreSessionIssue.LISTENER_OFF -> Icons.Rounded.VisibilityOff
        PreSessionIssue.OVERLAY_OFF -> Icons.Rounded.Layers
        PreSessionIssue.NOTIFICATIONS_OFF -> Icons.Rounded.NotificationsOff
        PreSessionIssue.NO_SUPPORTED_APP -> Icons.Rounded.Apps
        PreSessionIssue.BATTERY_OPTIMISED -> Icons.Rounded.BatteryAlert
        PreSessionIssue.LOCATION_OFF -> Icons.Rounded.LocationOff
        PreSessionIssue.OFFLINE -> Icons.Rounded.WifiOff
    }
}

/**
 * AI reading status as a small banner, only when it is not normal (paused, manual mode, switched off, unknown).
 * The status text comes from strings only (I5) and always says what to do meanwhile; TalkBack hears "AI reading: …".
 */
@Composable
private fun AiBanner(ai: AiStatus, actions: HomeActions) {
    if (ai is AiStatus.On || ai == AiStatus.Checking) return
    val context = LocalContext.current
    val text = AiStatusText.text(context, ai)
    val label = stringResource(R.string.home_ai_label)
    val colors = AiStatusText.tone(ai).toRmTone().colors()
    Surface(
        color = colors.container,
        contentColor = colors.content,
        shape = RmShapes.banner,
        modifier = Modifier.fillMaxWidth().testTag(HomeTags.AI_BANNER),
    ) {
        Column(Modifier.padding(horizontal = RmSpacing.l, vertical = RmSpacing.m), verticalArrangement = Arrangement.spacedBy(RmSpacing.xs)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "$label: $text"
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(RmSpacing.m))
                Column(Modifier.weight(1f)) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.home_guardian_keeps_working), style = MaterialTheme.typography.bodySmall)
                }
            }
            when (ai) {
                AiStatus.ManualMode -> QuietButton(stringResource(R.string.home_sign_in), actions.signIn, style = QuietStyle.Text)
                is AiStatus.Unknown -> QuietButton(stringResource(R.string.home_ai_retry), actions.retryAi, style = QuietStyle.Text)
                else -> Unit
            }
        }
    }
}

/**
 * Every remote kill switch as "On"/"Off" pills with an icon (goal 22), never colour alone. Settings shows them all;
 * Home passes [onlyOff] so only what the owner switched off appears (nothing when everything is on).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KillSwitchStatusList(switches: KillSwitches, modifier: Modifier = Modifier, onlyOff: Boolean = false) {
    val on = stringResource(R.string.home_switch_on)
    val off = stringResource(R.string.home_switch_off)
    val rows = buildList {
        add(stringResource(R.string.home_switch_ai) to switches.aiReaderEnabled)
        Provider.LIVE_APPS.forEach { add(stringResource(R.string.home_switch_reader, it.displayName) to switches.readerEnabledFor(it)) }
        add(stringResource(R.string.home_switch_ondc) to switches.ondcEnabled)
        add(stringResource(R.string.home_switch_demo) to switches.demoEnabled)
        add(stringResource(R.string.home_switch_better_ride) to switches.betterRideEnabled)
    }.filter { !onlyOff || !it.second }
    if (rows.isEmpty()) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RmSpacing.s), verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
        rows.forEach { (label, enabled) ->
            val text = "$label: ${if (enabled) on else off}"
            RmPill(text = text, tone = if (enabled) RmTone.GOOD else RmTone.WARN)
        }
    }
}
