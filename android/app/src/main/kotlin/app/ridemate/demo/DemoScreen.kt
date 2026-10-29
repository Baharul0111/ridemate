package app.ridemate.demo

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.gate.ComparisonList
import app.ridemate.core.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Minimum touch target (goal 27). */
private val TouchTarget = 48.dp

/**
 * The Try the demo screen: walk-through choice, the fictional demo apps (bundled screens), the demo price list and a
 * progress checklist. Guardian sheets, prompts and reminders appear exactly as in a live session (rendered by the
 * session area). Every provider is labelled DEMO; every state is stated in words, never by colour alone.
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
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header()
            state.notice?.let { NoticeCard(it, actions.dismissNotice) }
            if (!state.running) {
                StartPanel(state, actions)
                return@Column
            }
            StepText(state)
            val app = state.app
            if (app != null) {
                AppPanel(state, app, actions)
            } else {
                Overview(state, actions)
            }
            state.providers.forEach { view -> view.read?.let { ReadRows(it, booked = view.booked, nowMs = nowMs) } }
            Progress(state)
            OutlinedButton(
                onClick = actions.leave,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).semanticsDescription(R.string.demo_leave_description),
            ) { Text(stringResource(R.string.demo_leave)) }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.demo_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        DemoBadge()
    }
}

/** The DEMO badge: text plus an outline, so it never relies on colour. */
@Composable
private fun DemoBadge() {
    val description = stringResource(R.string.demo_badge_description)
    Text(
        stringResource(R.string.demo_badge),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(2.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun NoticeCard(notice: DemoNotice, dismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(DemoText.notice(notice)))
            TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = TouchTarget).widthIn(min = TouchTarget)) {
                Text(stringResource(R.string.demo_action_ok))
            }
        }
    }
}

@Composable
private fun StartPanel(state: DemoUiState, actions: DemoScreenActions) {
    Text(stringResource(R.string.demo_intro))
    state.unavailable?.let { reason ->
        Text(
            stringResource(DemoText.unavailable(reason)),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    DemoTrackChoice.entries.forEach { choice ->
        val title = stringResource(DemoText.trackTitle(choice))
        val startDescription = stringResource(R.string.demo_start_description, title)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(stringResource(DemoText.trackBody(choice)))
                Button(
                    onClick = { actions.start(choice) },
                    modifier = Modifier.heightIn(min = TouchTarget).semantics { contentDescription = startDescription },
                ) { Text(stringResource(R.string.demo_start)) }
            }
        }
    }
}

@Composable
private fun StepText(state: DemoUiState) {
    val context = LocalContext.current
    val text = when (state.phase) {
        DemoPhase.BOOKING -> {
            val next = state.nextToBook
            val index = state.providers.indexOf(next) + 1
            next?.let { stringResource(R.string.demo_step_book, index, state.providers.size, DemoText.providerName(context, it.provider)) }
        }
        DemoPhase.WATCHING -> stringResource(R.string.demo_step_watching)
        DemoPhase.FINISHED -> stringResource(R.string.demo_step_finished)
        else -> null
    } ?: return
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun AppPanel(state: DemoUiState, app: DemoAppView, actions: DemoScreenActions) {
    val context = LocalContext.current
    val view = state.provider(app.provider) ?: return
    val name = DemoText.providerName(context, app.provider)
    Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    when (app.purpose) {
        DemoAppPurpose.CANCEL -> Text(
            when {
                view.cancelProven -> stringResource(R.string.demo_cancel_proven, app.provider.displayName)
                view.cancelTapped -> stringResource(R.string.demo_cancel_waiting, app.provider.displayName)
                else -> stringResource(R.string.demo_app_cancel_hint, app.provider.displayName)
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        DemoAppPurpose.PAY -> Text(stringResource(R.string.demo_app_pay_hint, app.provider.displayName))
        DemoAppPurpose.BOOK, DemoAppPurpose.VIEW -> Unit
    }
    ScreenImage(app, name)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (app.purpose) {
            DemoAppPurpose.BOOK -> {
                val readDescription = stringResource(R.string.demo_action_read_fares_description, app.provider.displayName)
                Button(
                    onClick = { actions.readFares(app.provider) },
                    enabled = !view.reading,
                    modifier = Modifier.heightIn(min = TouchTarget).semantics { contentDescription = readDescription },
                ) { Text(stringResource(if (view.reading) R.string.demo_action_reading else R.string.demo_action_read_fares)) }
                if (view.reading) CircularProgressIndicator(Modifier.padding(4.dp))
                val bookDescription = stringResource(R.string.demo_action_booked_here_description, app.provider.displayName)
                OutlinedButton(
                    onClick = { actions.bookedHere(app.provider) },
                    enabled = !view.booked,
                    modifier = Modifier.heightIn(min = TouchTarget).semantics { contentDescription = bookDescription },
                ) { Text(stringResource(R.string.demo_action_booked_here)) }
            }
            DemoAppPurpose.CANCEL -> if (!view.cancelTapped && !view.cancelProven) {
                val cancelDescription = stringResource(R.string.demo_action_cancel_ride_description, app.provider.displayName)
                Button(
                    onClick = { actions.cancelRide(app.provider) },
                    modifier = Modifier.heightIn(min = TouchTarget).semantics { contentDescription = cancelDescription },
                ) { Text(stringResource(R.string.demo_action_cancel_ride)) }
                val showMeDescription = stringResource(R.string.demo_action_show_me_description, app.provider.displayName)
                OutlinedButton(
                    onClick = { actions.showMe(app.provider) },
                    modifier = Modifier.heightIn(min = TouchTarget).semantics { contentDescription = showMeDescription },
                ) { Text(stringResource(R.string.demo_action_show_me)) }
            }
            DemoAppPurpose.PAY, DemoAppPurpose.VIEW -> Unit
        }
    }
    if (state.phase != DemoPhase.BOOKING) {
        val backDescription = stringResource(R.string.demo_action_back_description)
        TextButton(
            onClick = actions.closeApp,
            modifier = Modifier.heightIn(min = TouchTarget).semantics { contentDescription = backDescription },
        ) { Text(stringResource(R.string.demo_action_back)) }
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
    Box(
        modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(SCREEN_ASPECT)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .semantics { contentDescription = description },
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        app.pointer?.let { box ->
            val color = MaterialTheme.colorScheme.error
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
                drawPath(path, color.copy(alpha = 0.8f), style = Stroke(width = 4.dp.toPx()))
                drawCircle(color.copy(alpha = 0.8f), radius = 3.dp.toPx(), center = Offset(tailX, y))
            }
        }
    }
}

@Composable
private fun Overview(state: DemoUiState, actions: DemoScreenActions) {
    val context = LocalContext.current
    state.providers.forEach { view ->
        val name = DemoText.providerName(context, view.provider)
        val openDescription = stringResource(R.string.demo_action_open_app_description, name)
        val status = stringResource(DemoText.status(view))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(status)
            }
            OutlinedButton(
                onClick = { actions.openApp(view.provider) },
                modifier = Modifier.heightIn(min = TouchTarget).semantics { contentDescription = openDescription },
            ) { Text(stringResource(R.string.demo_action_open_app, view.provider.displayName)) }
        }
    }
    if (state.progress.payOffered && !state.progress.paymentHandedOff) {
        state.progress.payProvider?.let { state.provider(it) }?.let { kept ->
            val payDescription = stringResource(R.string.demo_action_pay_description, kept.provider.displayName)
            Button(
                onClick = { actions.pay(kept.provider) },
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).semantics { contentDescription = payDescription },
            ) { Text(stringResource(R.string.demo_action_pay, kept.provider.displayName)) }
        }
    }
}

@Composable
private fun ReadRows(read: DemoReadResult, booked: Boolean, nowMs: () -> Long) {
    val context = LocalContext.current
    val name = DemoText.providerName(context, read.provider)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.demo_rows_label, read.provider.displayName, stringResource(DemoText.source(read.source))),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            DemoText.issue(read)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            // As in the live list, "Other" (parcel, rentals…) is never a vehicle type to compare.
            val list = ComparisonList.build(app.ridemate.compare.ComparisonData.chosen(read.rows, emptyList()), nowMs())
            list.groups.flatMap { it.entries }.forEach { entry ->
                val description = context.getString(R.string.demo_row_description_with_app, DemoText.rowDescription(context, entry, booked), name)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                        .alpha(if (entry.stale) STALE_ALPHA else 1f)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(DemoText.rowLabel(context, entry), fontWeight = FontWeight.Medium)
                        Text(DemoText.rowSeenAt(context, entry), style = MaterialTheme.typography.bodySmall)
                        // Stale rows say so in words, and are dimmed as well (never colour alone).
                        if (entry.stale) Text(DemoText.staleText(context, booked), style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(DemoText.rowPrice(context, entry), fontWeight = if (entry.cheapest) FontWeight.Bold else FontWeight.Normal)
                        DemoText.rowEta(context, entry)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (entry.cheapest) Text(stringResource(R.string.demo_row_cheapest), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.demo_progress_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        items.forEach { (label, isDone) ->
            Text(stringResource(R.string.demo_progress_item, label, if (isDone) done else notYet))
        }
    }
}

@Composable
private fun Modifier.semanticsDescription(res: Int): Modifier {
    val text = stringResource(res)
    return this.semantics { contentDescription = text }
}

/** The bundled screens are 1080×2400. */
private const val SCREEN_ASPECT = 1080f / 2400f

private const val FRESHNESS_TICK_MS = 1_000L
private const val STALE_ALPHA = 0.6f
