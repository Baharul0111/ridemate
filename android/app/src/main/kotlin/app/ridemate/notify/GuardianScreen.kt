package app.ridemate.notify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import app.ridemate.core.format.DistanceText
import app.ridemate.core.guardian.FeeNote
import app.ridemate.core.guardian.FeeNoteKind
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UiAction
import app.ridemate.session.GuardianSessionApi
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing
import app.ridemate.ui.components.BigPrimaryButton
import app.ridemate.ui.components.DemoTag
import app.ridemate.ui.components.HeroIllustration
import app.ridemate.ui.components.Hero
import app.ridemate.ui.components.IconWell
import app.ridemate.ui.components.InfoSheet
import app.ridemate.ui.components.InlineBanner
import app.ridemate.ui.components.ProviderAvatar
import app.ridemate.ui.components.QuietButton
import app.ridemate.ui.components.QuietStyle
import app.ridemate.ui.components.RideStateGroup
import app.ridemate.ui.components.RmCard
import app.ridemate.ui.components.RmIconButton
import app.ridemate.ui.components.RmPill
import app.ridemate.ui.components.RmSectionTitle
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.StatusPill
import app.ridemate.ui.components.TimelineLeg
import app.ridemate.ui.components.LegTimeline
import app.ridemate.ui.components.WhyButton
import app.ridemate.ui.components.colors

/** Touch targets are at least 48 dp (goal 27). */
private val MinTouch = RmSpacing.minTouch

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
    return remember(context, configuration, cancelSteps) { GuardianPresenter(GuardianText(context), cancelSteps) }
}

/**
 * One glance: a "Driver found" hero (minutes · km), the open questions as icon-led cards, a reminders pill, and every
 * booking as a timeline row (avatar → state pill → proof tick). Explanations sit behind a ⓘ tap. Stateless: [onAction]
 * receives each tap.
 */
@Composable
fun GuardianScreen(
    state: GuardianUiState,
    presenter: GuardianPresenter,
    onAction: (ActionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = presenter.text
    val items = state.items.sortedByDescending { GuardianUiState.priority(it) }
    val hero = state.legs.firstOrNull { it.role == LegRole.KEPT && it.driver != null && it.state in GuardianPresenter.RIDE_STATES }
        ?: state.legs.firstOrNull { it.driver != null && it.state in GuardianPresenter.RIDE_STATES }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(RideMateTheme.colors.canvas),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.cardGap),
        contentPadding = PaddingValues(horizontal = RmSpacing.screen, vertical = RmSpacing.l),
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
                    Text(
                        text.string(R.string.guardian_screen_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = RideMateTheme.colors.ink,
                        modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                    )
                    if (state.demo) DemoTag()
                }
                if (state.demo) RmPill(text.string(R.string.guardian_screen_demo_badge), tone = RmTone.DEMO)
            }
        }
        if (!state.hasSession && state.items.isEmpty()) {
            item(key = "empty") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RmSpacing.l)) {
                    HeroIllustration(Hero.EMPTY, height = 140.dp)
                    Text(
                        text.string(R.string.guardian_screen_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = RideMateTheme.colors.muted,
                    )
                }
            }
        }
        if (hero != null && hero.driver != null) {
            item(key = "hero") { DriverHero(hero.driver, text) }
        }
        if (items.isNotEmpty()) {
            item(key = "needs") { RmSectionTitle(text.string(R.string.guardian_screen_needs_you)) }
            items(items, key = { "card:" + it.id }) { item -> GuardianItemView(item, presenter.present(item), text, onAction) }
        }
        if (state.stopBlocked) {
            item(key = "stop-blocked") {
                InlineBanner(
                    presenter.stopBlockedLine(state),
                    tone = RmTone.WARN,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (state.hasSession && state.keepSearchingAvailable && state.legs.none { it.booked }) {
            item(key = "keep-searching") { KeepSearchingRow(state.keepSearching, text, onAction) }
        }
        if (state.legs.isNotEmpty()) {
            item(key = "bookings") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
                    Box(Modifier.weight(1f)) { RmSectionTitle(text.string(R.string.guardian_screen_bookings)) }
                    RemindersPill(state, text)
                }
            }
            item(key = "legs") { LegsTimeline(state.legs, text, onAction) }
        }
        if (state.notices.isNotEmpty()) {
            item(key = "updates") { RmSectionTitle(text.string(R.string.guardian_screen_updates)) }
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
    val loud = item is AlertItem
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = RmShapes.sheet,
        color = if (loud) RideMateTheme.colors.dangerContainer else RideMateTheme.colors.sheet,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        // Scrolls at 200 % font scale instead of clipping; never taller than most of the screen.
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RmSpacing.l, vertical = RmSpacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .padding(bottom = RmSpacing.s)
                    .size(width = 36.dp, height = 4.dp)
                    .background(RideMateTheme.colors.line, CircleShape),
            )
            GuardianItemView(item, presenter.present(item), presenter.text, onAction, flat = true)
        }
    }
}

/** One card rendered generically (title, lines, buttons). Kept for callers that only have a [GuardianCard]. */
@Composable
fun GuardianCardView(card: GuardianCard, onAction: (ActionTarget) -> Unit, modifier: Modifier = Modifier) {
    ItemShell(card, tone = card.toneOf(), icon = card.iconOf(), modifier = modifier, details = emptyList()) {
        card.steps.forEachIndexed { index, step -> StepCard(index + 1, step, null) }
        card.lines.drop(card.steps.size).forEach { BodyLine(it) }
        Actions(card.actions, onAction, destructive = card.style == CardStyle.LOUD)
    }
}

/** An open item as an icon-led card: a layout per kind, with explanations behind ⓘ. */
@Composable
fun GuardianItemView(
    item: UiItem,
    card: GuardianCard,
    text: GuardianText,
    onAction: (ActionTarget) -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
) {
    when (item) {
        is DriverSheetItem -> ItemShell(card, RmTone.GOOD, Icons.Rounded.DirectionsCar, modifier, details = card.lines, flat = flat) {
            item.drivers.forEach { DriverNumbers(it, text) }
            item.others.forEach { other -> FeeRow(other.provider, other.fee, text) }
            Actions(card.actions, onAction)
        }
        is BetterRideItem -> ItemShell(card, RmTone.BRAND, Icons.Rounded.TravelExplore, modifier, details = card.lines, flat = flat) {
            Row(horizontalArrangement = Arrangement.spacedBy(RmSpacing.s), modifier = Modifier.fillMaxWidth()) {
                SideBySide(text.string(R.string.guardian_better_now), item.current, item.currentFee, text, Modifier.weight(1f))
                SideBySide(text.string(R.string.guardian_better_new), item.candidate, item.candidateFee, text, Modifier.weight(1f))
            }
            Actions(card.actions, onAction)
        }
        is CancelCardItem -> ItemShell(card, RmTone.WARN, null, modifier, details = card.lines, flat = flat, provider = item) {
            Row(horizontalArrangement = Arrangement.spacedBy(RmSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(item.providerState, text = text.state(item.providerState, item.provider))
                FeePill(item.fee, text)
            }
            val showMe = card.actions.firstOrNull { (it.target as? GuardianTap)?.action == UiAction.SHOW_ME }
            if (card.steps.isEmpty()) {
                StepCard(1, null, showMe, onAction, plain = card.lines.firstOrNull())
            } else {
                card.steps.forEachIndexed { index, step -> StepCard(index + 1, step, if (index == 0) showMe else null, onAction) }
            }
            Actions(card.actions.filter { it !== showMe }, onAction)
        }
        is ReminderItem -> ItemShell(card, RmTone.WARN, Icons.Rounded.NotificationsActive, modifier, details = card.lines, flat = flat) {
            StatusPill(
                RideStateGroup.CANCELLING,
                text = text.string(R.string.guardian_reminders_open, item.legs.size),
                pulsing = true,
            )
            item.legs.forEach { leg ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
                    ProviderAvatar(leg.provider, name = text.provider(leg.provider), size = 32.dp)
                    StatusPill(leg.state, text = text.state(leg.state, leg.provider))
                }
            }
            Actions(card.actions, onAction)
        }
        is AlertItem -> ItemShell(card, RmTone.DANGER, Icons.Rounded.Warning, modifier, details = emptyList(), flat = flat) {
            card.lines.forEach { BodyLine(it) }
            Actions(card.actions, onAction, destructive = true)
        }
        is PayItem -> ItemShell(card, RmTone.BRAND, Icons.Rounded.Payments, modifier, details = card.lines, flat = flat) {
            Actions(card.actions, onAction)
        }
        else -> ItemShell(card, card.toneOf(), card.iconOf(), modifier, details = emptyList(), flat = flat) {
            card.lines.forEach { BodyLine(it) }
            Actions(card.actions, onAction, destructive = card.style == CardStyle.LOUD)
        }
    }
}

/** Card frame: icon (or the app's avatar) + title + ⓘ for the full explanation, then the body. */
@Composable
private fun ItemShell(
    card: GuardianCard,
    tone: RmTone,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    modifier: Modifier = Modifier,
    details: List<String>,
    flat: Boolean = false,
    provider: CancelCardItem? = null,
    body: @Composable () -> Unit,
) {
    var why by rememberSaveable(card.id) { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
                if (provider != null) {
                    ProviderAvatar(provider.provider, contentDescription = null)
                } else if (icon != null) {
                    IconWell(icon, tone = tone)
                }
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = RideMateTheme.colors.ink,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (details.isNotEmpty()) {
                    WhyButton(onClick = { why = true }, contentDescription = card.title + ". " + whyLabel())
                }
            }
            body()
        }
    }
    if (flat) {
        Box(modifier.fillMaxWidth()) { content() }
    } else {
        RmCard(modifier = modifier.fillMaxWidth(), tone = if (tone == RmTone.DANGER) RmTone.DANGER else RmTone.NEUTRAL) { content() }
    }
    if (why) {
        InfoSheet(
            onDismiss = { why = false },
            title = card.title,
            body = details.joinToString("\n"),
            tone = tone,
        )
    }
}

@Composable
private fun whyLabel(): String = androidx.compose.ui.res.stringResource(R.string.guardian_why_description)

@Composable
private fun BodyLine(line: String) {
    Text(line, style = MaterialTheme.typography.bodyLarge, color = RideMateTheme.colors.ink)
}

/** Buttons: the first is the big primary (thumb zone), the rest quiet, all ≥ 48 dp with their spoken label. */
@Composable
private fun Actions(actions: List<CardAction>, onAction: (ActionTarget) -> Unit, destructive: Boolean = false) {
    if (actions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.s), modifier = Modifier.fillMaxWidth()) {
        actions.forEachIndexed { index, action ->
            if (index == 0) {
                BigPrimaryButton(
                    text = action.label,
                    onClick = { onAction(action.target) },
                    destructive = destructive,
                    contentDescription = action.description,
                )
            } else {
                QuietButton(
                    text = action.label,
                    onClick = { onAction(action.target) },
                    fillWidth = true,
                    contentDescription = action.description,
                    modifier = Modifier.heightIn(min = MinTouch),
                )
            }
        }
    }
}

/** "4 min · 1.2 km" as the biggest text, avatar beside it, and where the numbers came from (I2). */
@Composable
private fun DriverNumbers(driver: DriverUi, text: GuardianText) {
    val numbers = heroNumbers(driver, text)
    val source = driverSource(driver, text)
    val spoken = text.driverLine(driver)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RmSpacing.m),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
    ) {
        ProviderAvatar(driver.provider, name = text.provider(driver.provider), contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(numbers, style = RideMateTheme.type.price, color = RideMateTheme.colors.ink)
            source?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = RideMateTheme.colors.muted) }
        }
    }
}

/** The top hero on the guardian screen: "Driver found" + big minutes · km + the app's avatar. */
@Composable
private fun DriverHero(driver: DriverUi, text: GuardianText) {
    val title = text.string(R.string.guardian_hero_found)
    val numbers = heroNumbers(driver, text)
    val spoken = title + ". " + text.driverLine(driver)
    RmCard(tone = RmTone.GOOD, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.l)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RmSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = RmTone.GOOD.colors().content)
                Text(numbers, style = RideMateTheme.type.priceLarge, color = RideMateTheme.colors.ink)
                driverSource(driver, text)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = RideMateTheme.colors.muted)
                }
            }
            ProviderAvatar(driver.provider, name = text.provider(driver.provider), size = 56.dp, contentDescription = null)
        }
    }
}

private fun heroNumbers(driver: DriverUi, text: GuardianText): String {
    val parts = listOfNotNull(
        driver.etaMin?.let { text.string(R.string.guardian_hero_eta, it) },
        driver.distanceMeters?.let { DistanceText.format(it) },
    )
    return if (parts.isEmpty()) text.string(R.string.guardian_see_in_app, text.provider(driver.provider)) else parts.joinToString(" · ")
}

private fun driverSource(driver: DriverUi, text: GuardianText): String? = when {
    driver.etaMin != null -> text.source(driver.etaSource, driver.provider)
    driver.distanceMeters != null -> text.source(driver.distanceSource, driver.provider)
    else -> null
}

/** Old vs new driver, side by side: minutes · km and what cancelling would cost. */
@Composable
private fun SideBySide(label: String, driver: DriverUi, fee: FeeNote, text: GuardianText, modifier: Modifier) {
    val spoken = label + ": " + text.driverLine(driver) + ". " + text.fee(fee)
    RmCard(modifier = modifier.clearAndSetSemantics { contentDescription = spoken }, padding = RmSpacing.m) {
        Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.xs)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = RideMateTheme.colors.muted)
            ProviderAvatar(driver.provider, name = text.provider(driver.provider), contentDescription = null)
            Text(heroNumbers(driver, text), style = RideMateTheme.type.price, color = RideMateTheme.colors.ink)
            FeePill(fee, text)
        }
    }
}

/** Another booking: avatar + app name + fee pill ("Free" / "May cost ≤ ₹50"). */
@Composable
private fun FeeRow(provider: app.ridemate.core.model.Provider, fee: FeeNote, text: GuardianText) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RmSpacing.m),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).semantics(mergeDescendants = true) {},
    ) {
        ProviderAvatar(provider, name = text.provider(provider), size = 32.dp, contentDescription = null)
        Text(text.provider(provider), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        FeePill(fee, text)
    }
}

@Composable
private fun FeePill(fee: FeeNote, text: GuardianText) {
    val free = fee.kind == FeeNoteKind.FREE_WHILE_SEARCHING || fee.kind == FeeNoteKind.FREE_WINDOW || fee.kind == FeeNoteKind.FREE_DRIVER_LATE
    RmPill(text.fee(fee), tone = if (free) RmTone.GOOD else RmTone.WARN)
}

/** A numbered cancel step: number badge, the step with bold control names, and "Show me" on the first step. */
@Composable
private fun StepCard(
    number: Int,
    step: CancelStep?,
    showMe: CardAction?,
    onAction: (ActionTarget) -> Unit = {},
    plain: String? = null,
) {
    val annotated = buildAnnotatedString {
        if (step != null) {
            step.segments.forEach { segment ->
                if (segment.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) } else append(segment.text)
            }
        } else {
            append(plain.orEmpty())
        }
    }
    val tone = RmTone.NEUTRAL.colors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RmSpacing.m),
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.container, RmShapes.chip)
            .padding(horizontal = RmSpacing.m, vertical = RmSpacing.s)
            .heightIn(min = MinTouch),
    ) {
        Box(
            Modifier.size(32.dp).background(RideMateTheme.colors.brand, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.titleSmall,
                color = RideMateTheme.colors.onBrand,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Text(
            annotated,
            style = MaterialTheme.typography.bodyLarge,
            color = RideMateTheme.colors.ink,
            modifier = Modifier.weight(1f).semantics { contentDescription = "$number. " + annotated.text },
        )
        if (showMe != null) {
            RmIconButton(
                icon = Icons.Rounded.TouchApp,
                contentDescription = showMe.description,
                onClick = { onAction(showMe.target) },
                tonal = true,
            )
        }
    }
}

/** Every booking as a timeline row: avatar → state pill → proof tick; extra bookings can be dismissed. */
@Composable
private fun LegsTimeline(legs: List<LegUi>, text: GuardianText, onAction: (ActionTarget) -> Unit) {
    val rows = legs.map { leg ->
        val detail = listOfNotNull(
            text.role(leg.role, leg.dismissed),
            leg.driver?.let { text.driverDetails(it) },
            if (leg.generation > 0) text.generation(leg.generation) else null,
        ).joinToString(" · ").ifEmpty { null }
        leg to TimelineLeg(
            provider = leg.provider,
            state = leg.state,
            providerName = text.provider(leg.provider),
            stateText = text.state(leg.state, leg.provider),
            detail = detail,
            proven = leg.state == app.ridemate.core.model.RideState.CANCEL_CONFIRMED,
            evidenceText = leg.evidence?.let { text.evidence(it, leg.provider) },
        )
    }
    val byRow = rows.associate { (leg, row) -> row to leg }
    RmCard(modifier = Modifier.fillMaxWidth()) {
        LegTimeline(
            legs = rows.map { it.second },
            trailing = { row ->
                val leg = byRow[row]
                if (leg != null && leg.role == LegRole.EXTRA && !leg.dismissed && !leg.state.isTerminal) {
                    RmIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = text.actionDescription(UiAction.DISMISS, leg.provider),
                        onClick = { onAction(GuardianTap(TapScope.LEG, leg.legId, UiAction.DISMISS)) },
                    )
                }
            },
        )
    }
}

/** Reminders as a pulsing pill ("2 still open") with the next time beside it. */
@Composable
private fun RemindersPill(state: GuardianUiState, text: GuardianText) {
    val open = state.legs.count { it.role == LegRole.EXTRA && !it.dismissed && !it.state.isTerminal }
    val next = state.nextReminderAtWallMs
    if (open == 0 && next == null) return
    val label = buildString {
        append(text.string(R.string.guardian_reminders_open, open))
        if (next != null) append(" · ").append(text.string(R.string.guardian_reminders_next, text.time(next)))
    }
    val spoken = if (next != null) {
        text.string(R.string.guardian_screen_reminders, state.remindersSent, text.time(next))
    } else {
        text.string(R.string.guardian_screen_reminders_none)
    }
    StatusPill(RideStateGroup.CANCELLING, text = label, contentDescription = "$label. $spoken", pulsing = open > 0)
}

/** The opt-in "Keep searching" (off by default), with its fee and driver-harm warning in full. */
@Composable
private fun KeepSearchingRow(on: Boolean, text: GuardianText, onAction: (ActionTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
        val label = text.sessionAction(SessionOpKind.TOGGLE_KEEP_SEARCHING, keepSearchingOn = on)
        QuietButton(
            text = label,
            onClick = { onAction(SessionOp(SessionOpKind.TOGGLE_KEEP_SEARCHING)) },
            icon = Icons.Rounded.TravelExplore,
            fillWidth = true,
            style = QuietStyle.Outline,
            contentDescription = label,
            modifier = Modifier.heightIn(min = MinTouch),
        )
        InlineBanner(text.string(R.string.guardian_keep_searching_warning), tone = RmTone.WARN)
    }
}

@Composable
private fun NoticeRow(card: GuardianCard, text: GuardianText, onAction: (ActionTarget) -> Unit) {
    RmCard(modifier = Modifier.fillMaxWidth(), padding = RmSpacing.m) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            IconWell(Icons.Rounded.Info, tone = RmTone.NEUTRAL, size = 32.dp)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(card.title, style = MaterialTheme.typography.bodyLarge, color = RideMateTheme.colors.ink)
                card.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = RideMateTheme.colors.muted) }
            }
            RmIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = text.sessionAction(SessionOpKind.DISMISS_NOTICE),
                onClick = { onAction(SessionOp(SessionOpKind.DISMISS_NOTICE, card.id)) },
            )
        }
    }
}

private fun GuardianCard.toneOf(): RmTone = when (style) {
    CardStyle.LOUD -> RmTone.DANGER
    CardStyle.HEADS_UP -> RmTone.BRAND
    CardStyle.QUIET -> RmTone.NEUTRAL
}

private fun GuardianCard.iconOf() = when (style) {
    CardStyle.LOUD -> Icons.Rounded.Warning
    CardStyle.HEADS_UP -> Icons.Rounded.NotificationsActive
    CardStyle.QUIET -> Icons.Rounded.Info
}
