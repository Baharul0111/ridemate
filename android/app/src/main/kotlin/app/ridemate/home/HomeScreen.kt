package app.ridemate.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.config.KillSwitches
import app.ridemate.core.model.Provider
import app.ridemate.ui.ChipTone
import app.ridemate.ui.DemoBadge
import app.ridemate.ui.FixItCard
import app.ridemate.ui.InfoCard
import app.ridemate.ui.PrimaryButton
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.ScreenScaffold
import app.ridemate.ui.SecondaryButton
import app.ridemate.ui.StatusChip
import app.ridemate.ui.TextAction
import app.ridemate.ui.UiFormat
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

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    ScreenScaffold(
        title = stringResource(R.string.app_name),
        onBack = null,
        actions = {
            IconButton(onClick = actions.settings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings_cd))
            }
        },
    ) {
        UpdateBanner(state.update, actions.downloadUpdate)
        state.activeSession?.let { ActiveSessionCard(it, actions.openSession) }

        Text(stringResource(R.string.home_intro), style = MaterialTheme.typography.bodyLarge)
        PrimaryButton(
            text = stringResource(R.string.home_compare),
            onClick = actions.compare,
            icon = Icons.Filled.DirectionsCar,
            enabled = state.compare != CompareCheck.Checking,
        )
        CompareCheckSection(state.compare, actions)

        DemoCard(enabled = state.killSwitches.demoEnabled, onDemo = actions.demo)

        AiStatusSection(state, actions)
        Text(
            stringResource(R.string.home_status_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        KillSwitchStatusList(state.killSwitches)
        Text(
            stringResource(R.string.built_with_spark),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
    }
}

/** "Update available" with the published checksum, or the "no longer supported" notice (F5, goal 28). */
@Composable
fun UpdateBanner(update: UpdateState, onDownload: (String) -> Unit) {
    val context = LocalContext.current
    val headline = UpdateMessages.headline(context, update) ?: return
    InfoCard(
        title = headline,
        body = UpdateMessages.checksum(context, update),
        tone = if (update.forceUpdate || update.belowMinSupported) ChipTone.BAD else ChipTone.WARNING,
        icon = Icons.Filled.Download,
    ) {
        val url = update.url
        if (url != null) {
            PrimaryButton(stringResource(R.string.home_update_download), { onDownload(url) }, icon = Icons.Filled.Download)
        } else {
            Text(stringResource(R.string.home_update_no_link), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ActiveSessionCard(session: ActiveSessionSummary, onOpen: (String) -> Unit) {
    val apps = session.apps.joinToString(", ") { it.displayName }
    InfoCard(
        title = stringResource(R.string.home_session_title),
        body = stringResource(R.string.home_session_body, session.dropLabel, apps, UiFormat.timeIst(session.startedAtUtc)),
        tone = ChipTone.GOOD,
    ) {
        PrimaryButton(stringResource(R.string.home_session_open), { onOpen(session.sessionId) })
    }
}

@Composable
private fun CompareCheckSection(check: CompareCheck, actions: HomeActions) {
    when (check) {
        CompareCheck.Idle -> Unit
        CompareCheck.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.home_checking), style = MaterialTheme.typography.bodyMedium)
        }
        is CompareCheck.Blocked -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.home_blocked_heading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            check.report.blocking.forEach { IssueCard(it, ChipTone.BAD, actions.fix) }
            check.report.warnings.forEach { IssueCard(it, ChipTone.WARNING, actions.fix) }
            TextAction(stringResource(R.string.home_check_close), actions.dismissCheck)
        }
        is CompareCheck.Warnings -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.home_warnings_heading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            check.report.warnings.forEach { IssueCard(it, ChipTone.WARNING, actions.fix) }
            SecondaryButton(stringResource(R.string.home_continue_anyway), actions.continueAnyway)
        }
    }
}

/** A pre-session problem as a fix-it card: what is wrong, why it matters, one button that fixes it. */
@Composable
fun IssueCard(issue: PreSessionIssue, tone: ChipTone, onFix: (PreSessionIssue) -> Unit) {
    val copy = PreSessionCopy.of(issue)
    FixItCard(
        title = stringResource(copy.title),
        body = stringResource(copy.body),
        actionLabel = stringResource(copy.action),
        onAction = { onFix(issue) },
        tone = tone,
    )
}

/** Wording of each pre-session issue (strings_home.xml). */
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
}

@Composable
private fun DemoCard(enabled: Boolean, onDemo: () -> Unit) {
    val s = RideMateTheme.status
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = s.demo, contentColor = s.onDemo),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_demo_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                DemoBadge()
            }
            Text(
                stringResource(if (enabled) R.string.home_demo_body else R.string.home_demo_off),
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryButton(
                text = stringResource(R.string.home_try_demo),
                onClick = onDemo,
                enabled = enabled,
                icon = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.home_try_demo_cd),
            )
        }
    }
}

@Composable
private fun AiStatusSection(state: HomeState, actions: HomeActions) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusChip(
            text = AiStatusText.text(context, state.ai),
            tone = AiStatusText.tone(state.ai),
            label = stringResource(R.string.home_ai_label),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.ai !is AiStatus.On && state.ai != AiStatus.Checking) {
            Text(stringResource(R.string.home_guardian_keeps_working), style = MaterialTheme.typography.bodyMedium)
        }
        when (state.ai) {
            AiStatus.ManualMode -> TextAction(stringResource(R.string.home_sign_in), actions.signIn)
            is AiStatus.Unknown -> TextAction(stringResource(R.string.home_ai_retry), actions.retryAi)
            else -> Unit
        }
    }
}

/** Every remote kill switch as "On"/"Off" with an icon (goal 22). Shared by Home and Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KillSwitchStatusList(switches: KillSwitches, modifier: Modifier = Modifier) {
    val on = stringResource(R.string.home_switch_on)
    val off = stringResource(R.string.home_switch_off)
    val rows = buildList {
        add(stringResource(R.string.home_switch_ai) to switches.aiReaderEnabled)
        Provider.LIVE_APPS.forEach { add(stringResource(R.string.home_switch_reader, it.displayName) to switches.readerEnabledFor(it)) }
        add(stringResource(R.string.home_switch_ondc) to switches.ondcEnabled)
        add(stringResource(R.string.home_switch_demo) to switches.demoEnabled)
    }
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, enabled) ->
            StatusChip(text = if (enabled) on else off, tone = if (enabled) ChipTone.GOOD else ChipTone.BAD, label = label)
        }
    }
}
