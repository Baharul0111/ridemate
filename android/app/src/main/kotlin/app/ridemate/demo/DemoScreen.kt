package app.ridemate.demo

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.gate.ComparisonList
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing
import app.ridemate.ui.components.BigPrimaryButton
import app.ridemate.ui.components.IconWell
import app.ridemate.ui.components.InfoSheet
import app.ridemate.ui.components.InlineBanner
import app.ridemate.ui.components.ProviderAvatar
import app.ridemate.ui.components.QuietButton
import app.ridemate.ui.components.QuietStyle
import app.ridemate.ui.components.RmCard
import app.ridemate.ui.components.RmIconButton
import app.ridemate.ui.components.RmListDivider
import app.ridemate.ui.components.RmPill
import app.ridemate.ui.components.RmSectionTitle
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.WhyButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Minimum touch target (goal 27). */
private val TouchTarget = RmSpacing.minTouch

/**
 * The Try the demo screen: one question at a time ("Pick a demo"), the fictional demo apps (bundled screens), the
 * demo price list (price first) and a ✓ checklist. Guardian sheets, prompts and reminders appear exactly as in a live
 * session (rendered by the session area). Every provider is labelled DEMO; every state is an icon plus words, never
 * colour alone; long explanations sit behind ⓘ.
 */
@Composable
fun DemoScreen(controller: DemoController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    // "seen at" rows turn "old" after 60 s (goal 6), so the screen re-reads the clock every second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(FRESHNESS_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    DemoScreenContent(
        state = state,
        actions = DemoScreenActions(
            start = { controller.start(it) },
            leave = controller::stop,
            openApp = controller::openApp,
            closeApp = controller::closeApp,
            readFares = { controller.readFares(it) },
            bookedHere = controller::bookedHere,
            cancelRide = controller::tapCancelRide,
            showMe = controller::showMe,
            pay = controller::pay,
            dismissNotice = controller::dismissNotice,
        ),
        modifier = modifier,
        nowMs = { now },
    )
}

/** The screen's callbacks (separate from [DemoController] so the UI can be tested with a plain state). */
data class DemoScreenActions(
    val start: (DemoTrackChoice) -> Unit = {},
    val leave: () -> Unit = {},
    val openApp: (Provider) -> Unit = {},
    val closeApp: () -> Unit = {},
    val readFares: (Provider) -> Unit = {},
    val bookedHere: (Provider) -> Unit = {},
    val cancelRide: (Provider) -> Unit = {},
    val showMe: (Provider) -> Unit = {},
    val pay: (Provider) -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

@Composable
fun DemoScreenContent(
    state: DemoUiState,
    actions: DemoScreenActions,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    Surface(modifier = modifier.fillMaxSize(), color = RideMateTheme.colors.canvas) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RmSpacing.screen, vertical = RmSpacing.l),
            verticalArrangement = Arrangement.spacedBy(RmSpacing.cardGap),
        ) {
            Header()
            state.notice?.let { NoticeCard(it, actions.dismissNotice) }
            if (!state.running) {
                StartPanel(state, actions)
                return@Column
            }
            StepHero(state)
            val app = state.app
            if (app != null) {
                AppPanel(state, app, actions)
            } else {
                Overview(state, actions)
            }
            state.providers.forEach { view -> view.read?.let { ReadRows(it, booked = view.booked, nowMs = nowMs) } }
            Progress(state)
            QuietButton(
                text = stringResource(R.string.demo_leave),
                onClick = actions.leave,
                icon = Icons.AutoMirrored.Rounded.Logout,
                fillWidth = true,
                style = QuietStyle.Outline,
                contentDescription = stringResource(R.string.demo_leave_description),
            )
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
        Text(
            stringResource(R.string.demo_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = RideMateTheme.colors.ink,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        DemoBadge()
    }
}

/** The DEMO badge: a flask-style DEMO pill with the word, so it never relies on colour. */
@Composable
private fun DemoBadge() {
    RmPill(
        text = stringResource(R.string.demo_badge),
        tone = RmTone.DEMO,
        contentDescription = stringResource(R.string.demo_badge_description),
    )
}

@Composable
private fun NoticeCard(notice: DemoNotice, dismiss: () -> Unit) {
    RmCard(tone = RmTone.BRAND, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            IconWell(Icons.Rounded.Info, tone = RmTone.BRAND, size = 36.dp)
            Text(
                stringResource(DemoText.notice(notice)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
        QuietButton(text = stringResource(R.string.demo_action_ok), onClick = dismiss, fillWidth = true)
    }
}

/** One question: "Pick a demo". Each story is an icon-led card; its full description (and fair-play note) sits behind ⓘ. */
@Composable
private fun StartPanel(state: DemoUiState, actions: DemoScreenActions) {
    var about by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
        Text(
            stringResource(R.string.demo_pick_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = RideMateTheme.colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        WhyButton(onClick = { about = true }, contentDescription = stringResource(R.string.demo_about))
    }
    if (about) {
        InfoSheet(
            onDismiss = { about = false },
            title = stringResource(R.string.demo_about),
            body = stringResource(R.string.demo_intro),
            tone = RmTone.DEMO,
        )
    }
    state.unavailable?.let { reason ->
        InlineBanner(
            stringResource(DemoText.unavailable(reason)),
            tone = RmTone.WARN,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    DemoTrackChoice.entries.forEach { choice -> TrackCard(choice, actions) }
}

@Composable
private fun TrackCard(choice: DemoTrackChoice, actions: DemoScreenActions) {
    var why by rememberSaveable(choice.name) { mutableStateOf(false) }
    val title = stringResource(DemoText.trackTitle(choice))
    val startDescription = stringResource(R.string.demo_start_description, title)
    RmCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            IconWell(trackIcon(choice), tone = RmTone.BRAND)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = RideMateTheme.colors.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            WhyButton(onClick = { why = true }, contentDescription = stringResource(R.string.demo_track_why, title))
        }
        BigPrimaryButton(
            text = stringResource(R.string.demo_start),
            onClick = { actions.start(choice) },
            icon = Icons.Rounded.PlayArrow,
            contentDescription = startDescription,
        )
    }
    if (why) {
        // The track's full text, verbatim (the Keep searching track carries the fair-play warning).
        InfoSheet(
            onDismiss = { why = false },
            title = title,
            body = stringResource(DemoText.trackBody(choice)),
            icon = trackIcon(choice),
            tone = if (choice.keepSearching) RmTone.WARN else RmTone.BRAND,
            primaryLabel = stringResource(R.string.demo_start),
            onPrimary = {
                why = false
                actions.start(choice)
            },
        )
    }
}

private fun trackIcon(choice: DemoTrackChoice): ImageVector = when (choice) {
    DemoTrackChoice.MAIN -> Icons.Rounded.Layers
    DemoTrackChoice.KEEP_SEARCHING -> Icons.Rounded.TravelExplore
    else -> Icons.Rounded.Autorenew
}

/** Where the demo is now, as an icon-led hero (announced politely when it changes). */
@Composable
private fun StepHero(state: DemoUiState) {
    val context = LocalContext.current
    val (text, icon) = when (state.phase) {
        DemoPhase.BOOKING -> {
            val next = state.nextToBook
            val index = state.providers.indexOf(next) + 1
            next?.let {
                stringResource(R.string.demo_step_book, index, state.providers.size, DemoText.providerName(context, it.provider)) to
                    Icons.Rounded.DocumentScanner
            } ?: (null to Icons.Rounded.Info)
        }
        DemoPhase.WATCHING -> stringResource(R.string.demo_step_watching) to Icons.Rounded.Visibility
        DemoPhase.FINISHED -> stringResource(R.string.demo_step_finished) to Icons.Rounded.Flag
        else -> null to Icons.Rounded.Info
    }
    if (text == null) return
    val tone = if (state.phase == DemoPhase.FINISHED) RmTone.GOOD else RmTone.BRAND
    RmCard(tone = tone) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            IconWell(icon, tone = tone)
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun AppPanel(state: DemoUiState, app: DemoAppView, actions: DemoScreenActions) {
    val context = LocalContext.current
    val view = state.provider(app.provider) ?: return
    val name = DemoText.providerName(context, app.provider)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
        ProviderAvatar(app.provider, name = name, contentDescription = null)
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = RideMateTheme.colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        StatusPill(view)
    }
    when (app.purpose) {
        DemoAppPurpose.CANCEL -> InlineBanner(
            when {
                view.cancelProven -> stringResource(R.string.demo_cancel_proven, app.provider.displayName)
                view.cancelTapped -> stringResource(R.string.demo_cancel_waiting, app.provider.displayName)
                else -> stringResource(R.string.demo_app_cancel_hint, app.provider.displayName)
            },
            tone = when {
                view.cancelProven -> RmTone.GOOD
                view.cancelTapped -> RmTone.WARN
                else -> RmTone.BRAND
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        DemoAppPurpose.PAY -> InlineBanner(
            stringResource(R.string.demo_app_pay_hint, app.provider.displayName),
            tone = RmTone.BRAND,
            icon = Icons.Rounded.Payments,
        )
        DemoAppPurpose.BOOK, DemoAppPurpose.VIEW -> Unit
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ScreenImage(app, name) }
    Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.s), modifier = Modifier.fillMaxWidth()) {
        when (app.purpose) {
            DemoAppPurpose.BOOK -> {
                BigPrimaryButton(
                    text = stringResource(if (view.reading) R.string.demo_action_reading else R.string.demo_action_read_fares),
                    onClick = { actions.readFares(app.provider) },
                    icon = Icons.Rounded.DocumentScanner,
                    loading = view.reading,
                    contentDescription = stringResource(R.string.demo_action_read_fares_description, app.provider.displayName),
                )
                QuietButton(
                    text = stringResource(R.string.demo_action_booked_here),
                    onClick = { actions.bookedHere(app.provider) },
                    enabled = !view.booked,
                    icon = Icons.Rounded.CheckCircle,
                    fillWidth = true,
                    contentDescription = stringResource(R.string.demo_action_booked_here_description, app.provider.displayName),
                )
            }
            DemoAppPurpose.CANCEL -> if (!view.cancelTapped && !view.cancelProven) {
                BigPrimaryButton(
                    text = stringResource(R.string.demo_action_cancel_ride),
                    onClick = { actions.cancelRide(app.provider) },
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.demo_action_cancel_ride_description, app.provider.displayName),
                )
                QuietButton(
                    text = stringResource(R.string.demo_action_show_me),
                    onClick = { actions.showMe(app.provider) },
                    icon = Icons.Rounded.TouchApp,
                    fillWidth = true,
                    contentDescription = stringResource(R.string.demo_action_show_me_description, app.provider.displayName),
                )
            }
            DemoAppPurpose.PAY, DemoAppPurpose.VIEW -> Unit
        }
        if (state.phase != DemoPhase.BOOKING) {
            QuietButton(
                text = stringResource(R.string.demo_action_back),
                onClick = actions.closeApp,
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                fillWidth = true,
                style = QuietStyle.Text,
                contentDescription = stringResource(R.string.demo_action_back_description),
            )
        }
    }
}

@Composable
private fun ScreenImage(app: DemoAppView, providerName: String) {
    val context = LocalContext.current
    val description = stringResource(R.string.demo_screen_description, providerName, stringResource(DemoText.screenName(app.screen)))
    val image by produceState<ImageBitmap?>(initialValue = null, app.assetPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.assets.open(app.assetPath).use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val pointerDescription = stringResource(R.string.demo_pointer_description)
    val color = RideMateTheme.colors.danger
    Box(
        modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(SCREEN_ASPECT)
            .clip(RmShapes.card)
            .background(RideMateTheme.colors.sheet)
            .border(1.dp, RideMateTheme.colors.line, RmShapes.card)
            .semantics { contentDescription = description },
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        app.pointer?.let { box ->
            // Show me: an arrow drawn beside the target (left of its box), never covering it (§4.2 PointerOverlay).
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = pointerDescription }) {
                val y = size.height * ((box[1] + box[3]) / 2f) / 1000f
                val tipX = size.width * box[0] / 1000f - 4.dp.toPx()
                val tailX = (tipX - 48.dp.toPx()).coerceAtLeast(0f)
                val path = Path().apply {
                    moveTo(tailX, y)
                    lineTo(tipX, y)
                    moveTo(tipX - 12.dp.toPx(), y - 10.dp.toPx())
                    lineTo(tipX, y)
                    lineTo(tipX - 12.dp.toPx(), y + 10.dp.toPx())
                }
                drawPath(path, color.copy(alpha = 0.85f), style = Stroke(width = 4.dp.toPx()))
                drawCircle(color.copy(alpha = 0.85f), radius = 3.dp.toPx(), center = Offset(tailX, y))
            }
        }
    }
}

/** A demo app's state as a pill: icon + words, never colour alone. */
@Composable
private fun StatusPill(view: DemoProviderView) {
    val res = DemoText.status(view)
    val (tone, icon) = when (res) {
        R.string.demo_status_cancelled -> RmTone.GOOD to Icons.Rounded.CheckCircle
        R.string.demo_status_cancelling -> RmTone.WARN to Icons.Rounded.HourglassTop
        R.string.demo_status_driver -> RmTone.GOOD to Icons.Rounded.DirectionsCar
        R.string.demo_status_searching -> RmTone.BRAND to Icons.Rounded.HourglassTop
        R.string.demo_status_booked -> RmTone.BRAND to Icons.Rounded.CheckCircle
        else -> RmTone.NEUTRAL to Icons.Rounded.RadioButtonUnchecked
    }
    RmPill(stringResource(res), tone = tone, icon = icon)
}

/** Each demo app as an icon-led row: avatar, name, state pill, and an open button. */
@Composable
private fun Overview(state: DemoUiState, actions: DemoScreenActions) {
    val context = LocalContext.current
    RmSectionTitle(stringResource(R.string.demo_apps_title))
    RmCard(padding = RmSpacing.m) {
        state.providers.forEachIndexed { index, view ->
            if (index > 0) RmListDivider()
            val name = DemoText.providerName(context, view.provider)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = RmSpacing.minTouch),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RmSpacing.m),
            ) {
                ProviderAvatar(view.provider, name = name, contentDescription = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RmSpacing.xs)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = RideMateTheme.colors.ink)
                    StatusPill(view)
                }
                RmIconButton(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = stringResource(R.string.demo_action_open_app_description, name),
                    onClick = { actions.openApp(view.provider) },
                    tonal = true,
                )
            }
        }
    }
    if (state.progress.payOffered && !state.progress.paymentHandedOff) {
        state.progress.payProvider?.let { state.provider(it) }?.let { kept ->
            BigPrimaryButton(
                text = stringResource(R.string.demo_action_pay, kept.provider.displayName),
                onClick = { actions.pay(kept.provider) },
                icon = Icons.Rounded.Payments,
                contentDescription = stringResource(R.string.demo_action_pay_description, kept.provider.displayName),
            )
        }
    }
}

/** The prices read from one demo app: price first and largest, ETA small, "cheapest" only on verified rows. */
@Composable
private fun ReadRows(read: DemoReadResult, booked: Boolean, nowMs: () -> Long) {
    val context = LocalContext.current
    val name = DemoText.providerName(context, read.provider)
    val colors = RideMateTheme.colors
    RmCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            ProviderAvatar(read.provider, name = name, size = 32.dp, contentDescription = null)
            Text(
                stringResource(R.string.demo_rows_label, read.provider.displayName, stringResource(DemoText.source(read.source))),
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        DemoText.issue(read)?.let { issue ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                Text(stringResource(issue), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.weight(1f))
            }
        }
        // As in the live list, "Other" (parcel, rentals…) is never a vehicle type to compare.
        val list = ComparisonList.build(app.ridemate.compare.ComparisonData.chosen(read.rows, emptyList()), nowMs())
        list.groups.flatMap { it.entries }.forEachIndexed { index, entry ->
            if (index > 0) RmListDivider()
            val description = context.getString(R.string.demo_row_description_with_app, DemoText.rowDescription(context, entry, booked), name)
            val verified = entry.row.status == QuoteStatus.VERIFIED
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                    .alpha(if (entry.stale) STALE_ALPHA else 1f)
                    .semantics(mergeDescendants = true) { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RmSpacing.s),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RmSpacing.xxs)) {
                    Text(DemoText.rowLabel(context, entry), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = colors.ink)
                    Text(DemoText.rowSeenAt(context, entry), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    // Stale rows say so in words with a clock icon, and are dimmed as well (never colour alone).
                    if (entry.stale) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.xs)) {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
                            Text(DemoText.staleText(context, booked), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(RmSpacing.xxs)) {
                    Text(
                        DemoText.rowPrice(context, entry),
                        style = if (verified) RideMateTheme.type.price else MaterialTheme.typography.bodyMedium,
                        color = if (verified) colors.ink else colors.muted,
                    )
                    DemoText.rowEta(context, entry)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                    if (entry.cheapest) {
                        // Plain text in an accent chip (merged into the row, so TalkBack reads one node per row).
                        Text(
                            stringResource(R.string.demo_row_cheapest),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.ink,
                            modifier = Modifier
                                .background(colors.accentContainer, RmShapes.pill)
                                .padding(horizontal = RmSpacing.s, vertical = RmSpacing.xxs),
                        )
                    }
                }
            }
        }
    }
}

/** What the demo has shown, as a ✓ checklist (icon + words). */
@Composable
private fun Progress(state: DemoUiState) {
    val p = state.progress
    val items = buildList {
        add(stringResource(R.string.demo_progress_prices) to (p.pricesRead > 0))
        add(stringResource(R.string.demo_progress_booked, p.booked, p.toBook) to (p.toBook > 0 && p.booked == p.toBook))
        add(stringResource(R.string.demo_progress_driver) to p.driverFound)
        if (state.track != DemoTrackChoice.REMATCH) {
            add(stringResource(R.string.demo_progress_cancel_guide) to p.cancelGuideShown)
            add(stringResource(R.string.demo_progress_proof, p.cancelsProven) to (p.cancelsProven > 0))
            add(stringResource(R.string.demo_progress_reminders, p.reminders) to (p.reminders > 0))
        }
        if (state.track == DemoTrackChoice.KEEP_SEARCHING) add(stringResource(R.string.demo_progress_better_ride) to p.betterRideOffered)
        add(stringResource(R.string.demo_progress_pay) to p.paymentHandedOff)
    }
    val done = stringResource(R.string.demo_progress_done)
    val notYet = stringResource(R.string.demo_progress_not_yet)
    val colors = RideMateTheme.colors
    RmCard {
        Text(
            stringResource(R.string.demo_progress_title),
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        items.forEach { (label, isDone) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
                Icon(
                    if (isDone) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isDone) colors.good else colors.muted,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.demo_progress_item, label, if (isDone) done else notYet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDone) colors.ink else colors.muted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The bundled screens are 1080×2400. */
private const val SCREEN_ASPECT = 1080f / 2400f

private const val FRESHNESS_TICK_MS = 1_000L
private const val STALE_ALPHA = 0.6f
