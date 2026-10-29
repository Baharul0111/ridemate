package app.ridemate.notify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ridemate.R
import app.ridemate.core.config.CancelStep
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.model.RideState
import app.ridemate.session.GuardianSessionApi

/** Touch targets are at least 48 dp (goal 27). */
private val MinTouch = 48.dp

/** The guardian screen wired to the running session. The UI shell shows it for [GuardianIntents.DESTINATION_GUARDIAN]. */
@Composable
fun GuardianRoute(api: GuardianSessionApi, cancelSteps: CancelStepsSource, modifier: Modifier = Modifier) {
    val state by api.uiState.collectAsStateWithLifecycle()
    val presenter = rememberGuardianPresenter(cancelSteps)
    GuardianScreen(state, presenter, onAction = api::perform, modifier = modifier)
}

/** The active sheet only, for showing over any other screen while a session runs. */
@Composable
fun GuardianSheetRoute(api: GuardianSessionApi, cancelSteps: CancelStepsSource, modifier: Modifier = Modifier) {
    val state by api.uiState.collectAsStateWithLifecycle()
    GuardianSheetHost(state, rememberGuardianPresenter(cancelSteps), api::perform, modifier)
}

@Composable
fun rememberGuardianPresenter(cancelSteps: CancelStepsSource): GuardianPresenter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, cancelSteps) {
        // The mock ONDC seller is named as such on the guardian screen too (goal 17).
        val ondcDemo = { runCatching { app.ridemate.RideMateApp.graph(context).ondcClient.view.value.demoSeller }.getOrDefault(false) }
        GuardianPresenter(GuardianText(context, ondcDemo), cancelSteps)
    }
}

/**
 * Every leg's state, its evidence rung and driver, the reminder schedule, the open sheets and questions with their
 * buttons, and recent updates. Stateless: [onAction] receives each tap.
 */
@Composable
fun GuardianScreen(
    state: GuardianUiState,
    presenter: GuardianPresenter,
    onAction: (ActionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = presenter.text
    val cards = state.items.sortedByDescending { GuardianUiState.priority(it) }.map(presenter::present)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item(key = "title") {
            Column {
                Text(
                    text.string(R.string.guardian_screen_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                if (state.demo) {
                    Text(
                        text.string(R.string.guardian_screen_demo_badge),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        if (!state.hasSession && state.items.isEmpty()) {
            item(key = "empty") { Text(text.string(R.string.guardian_screen_empty), style = MaterialTheme.typography.bodyLarge) }
        }
        if (cards.isNotEmpty()) {
            item(key = "needs") { SectionHeader(text.string(R.string.guardian_screen_needs_you)) }
            items(cards, key = { "card:" + it.id }) { card -> GuardianCardView(card, onAction) }
        }
        if (state.stopBlocked) {
            item(key = "stop-blocked") {
                Text(
                    presenter.stopBlockedLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (state.hasSession && state.keepSearchingAvailable && state.legs.none { it.booked }) {
            item(key = "keep-searching") { KeepSearchingRow(state.keepSearching, text, onAction) }
        }
        if (state.legs.isNotEmpty()) {
            item(key = "bookings") { SectionHeader(text.string(R.string.guardian_screen_bookings)) }
            items(state.legs, key = { "leg:" + it.legId }) { leg -> LegRow(leg, text, onAction) }
            item(key = "reminders") {
                val next = state.nextReminderAtWallMs
                Text(
                    if (next != null) {
                        text.string(R.string.guardian_screen_reminders, state.remindersSent, text.time(next))
                    } else {
                        text.string(R.string.guardian_screen_reminders_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.notices.isNotEmpty()) {
            item(key = "updates") { SectionHeader(text.string(R.string.guardian_screen_updates)) }
            items(state.notices, key = { it.id }) { notice -> NoticeRow(presenter.notice(notice), text, onAction) }
        }
    }
}

/** The most urgent open item as a sheet anchored to the bottom of the screen. Nothing when there is none. */
@Composable
fun GuardianSheetHost(
    state: GuardianUiState,
    presenter: GuardianPresenter,
    onAction: (ActionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.activeItem ?: return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        GuardianCardView(presenter.present(item), onAction, Modifier.padding(8.dp))
    }
}

/** One card: icon + title (never colour alone), its lines or cancel steps, and every button at 48 dp or more. */
@Composable
fun GuardianCardView(card: GuardianCard, onAction: (ActionTarget) -> Unit, modifier: Modifier = Modifier) {
    val loud = card.style == CardStyle.LOUD
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (loud) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (card.style) {
                        CardStyle.LOUD -> Icons.Filled.Warning
                        CardStyle.HEADS_UP -> Icons.Filled.Notifications
                        CardStyle.QUIET -> Icons.Filled.Info
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(card.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            card.steps.forEachIndexed { index, step -> StepText(index + 1, step) }
            card.lines.drop(card.steps.size).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            card.actions.forEachIndexed { index, action ->
                val buttonModifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch)
                    .semantics { contentDescription = action.description }
                if (index == 0) {
                    Button(onClick = { onAction(action.target) }, modifier = buttonModifier) { Text(action.label) }
                } else {
                    OutlinedButton(onClick = { onAction(action.target) }, modifier = buttonModifier) { Text(action.label) }
                }
            }
        }
    }
}

@Composable
private fun StepText(number: Int, step: CancelStep) {
    val annotated = buildAnnotatedString {
        append("$number. ")
        step.segments.forEach { segment ->
            if (segment.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) } else append(segment.text)
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SectionHeader(title: String) {
    Column {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
    }
}

@Composable
private fun LegRow(leg: LegUi, text: GuardianText, onAction: (ActionTarget) -> Unit) {
    val name = text.provider(leg.provider)
    val status = text.state(leg.state, leg.provider, leg.followUpPending)
    val proven = leg.state == RideState.CANCEL_CONFIRMED
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            if (proven) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                text.role(leg.role, leg.dismissed)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                leg.evidence?.let { Text(text.evidence(it, leg.provider), style = MaterialTheme.typography.bodySmall) }
                leg.driver?.let { Text(text.driverDetails(it), style = MaterialTheme.typography.bodySmall) }
                if (leg.generation > 0) Text(text.generation(leg.generation), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (leg.role == LegRole.EXTRA && !leg.dismissed && !leg.state.isTerminal) {
            OutlinedButton(
                onClick = { onAction(GuardianTap(TapScope.LEG, leg.legId, UiAction.DISMISS)) },
                modifier = Modifier
                    .heightIn(min = MinTouch)
                    .semantics { contentDescription = text.actionDescription(UiAction.DISMISS, leg.provider) },
            ) { Text(text.action(UiAction.DISMISS, leg.provider)) }
        }
    }
}

/**
 * The opt-in "Keep searching" (off by default), with its fee and driver-harm warning. Turning it on asks first, with
 * the same "Play fair with drivers" confirmation as Settings (goal 16); turning it off needs none.
 */
@Composable
private fun KeepSearchingRow(on: Boolean, text: GuardianText, onAction: (ActionTarget) -> Unit) {
    var confirming by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (confirming) {
        app.ridemate.settings.KeepSearchingWarning(
            onConfirm = {
                confirming = false
                onAction(SessionOp(SessionOpKind.TOGGLE_KEEP_SEARCHING))
            },
            onDismiss = { confirming = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = text.sessionAction(SessionOpKind.TOGGLE_KEEP_SEARCHING, keepSearchingOn = on)
        OutlinedButton(
            onClick = { if (on) onAction(SessionOp(SessionOpKind.TOGGLE_KEEP_SEARCHING)) else confirming = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch)
                .semantics { contentDescription = label },
        ) { Text(label) }
        Text(text.string(R.string.guardian_keep_searching_warning), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NoticeRow(card: GuardianCard, text: GuardianText, onAction: (ActionTarget) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(card.title, style = MaterialTheme.typography.bodyLarge)
            card.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        val description = text.sessionAction(SessionOpKind.DISMISS_NOTICE)
        IconButton(
            onClick = { onAction(SessionOp(SessionOpKind.DISMISS_NOTICE, card.id)) },
            modifier = Modifier.size(MinTouch).semantics { contentDescription = description },
        ) { Icon(Icons.Filled.Close, contentDescription = null) }
    }
}
