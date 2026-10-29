package app.ridemate.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.gate.ComparisonEntry
import app.ridemate.core.gate.ComparisonList
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmSpacing
import app.ridemate.ui.components.BigPrimaryButton
import app.ridemate.ui.components.FareCard
import app.ridemate.ui.components.FareQuote
import app.ridemate.ui.components.Hero
import app.ridemate.ui.components.HeroIllustration
import app.ridemate.ui.components.InfoSheet
import app.ridemate.ui.components.InlineBanner
import app.ridemate.ui.components.ProviderChoiceTile
import app.ridemate.ui.components.QuietButton
import app.ridemate.ui.components.RmListDivider
import app.ridemate.ui.components.RmListGroup
import app.ridemate.ui.components.RmListRow
import app.ridemate.ui.components.RmScaffold
import app.ridemate.ui.components.RmSectionTitle
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.VehicleChoiceTile
import app.ridemate.ui.components.VehicleIcon
import app.ridemate.ui.components.WhyButton
import app.ridemate.util.Clock
import kotlinx.coroutines.delay

/** What the manual price form shows: why AI isn't reading, for which app, and the classes seen on its screen. */
data class ManualPrompt(
    val message: String?,
    val provider: Provider?,
    val suggestedClasses: List<VehicleClass> = emptyList(),
)

/** The list's callbacks. Every one is a user tap. */
data class ComparisonCallbacks(
    /** "Book in <app>": opens the app (the user books there themselves). */
    val onBook: (Provider) -> Unit,
    /** "Booked in <app>"; null hides the button (no guardian bound yet). */
    val onBookedHere: ((Provider) -> Unit)?,
    /** A stale row ("old, tap to refresh"): opens the app so the user can tap Read fares again. */
    val onRefresh: (Provider) -> Unit,
    /** Saves a typed price; [done] gets null on success, else the message to show. */
    val onSubmitManual: (provider: Provider, vehicleClass: VehicleClass, text: String, done: (String?) -> Unit) -> Unit,
)

/** Test tags (stable; used by UI tests). */
object ComparisonTags {
    const val LIST = "comparison_list"
    const val MANUAL_FORM = "comparison_manual_form"
    const val PRICE_FIELD = "comparison_manual_price"
    const val PRIMARY = "comparison_primary"
    const val BOOKED = "comparison_booked"
    const val TYPE_PRICE = "comparison_type_price"
    const val MANUAL_BANNER = "comparison_manual_banner"
    fun row(provider: Provider, vehicleClass: VehicleClass) = "quote_${provider.wireId}_${vehicleClass.wireId}"
}

/** The current wall time, ticking every [periodMs] so rows grey out at 60 s without any other change. */
@Composable
fun rememberWallClock(clock: Clock, periodMs: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(clock.wallMs()) }
    LaunchedEffect(clock, periodMs) {
        while (true) {
            now = clock.wallMs()
            delay(periodMs)
        }
    }
    return now
}

/**
 * Compare, the hero screen (goals 5–8), price first: sections per vehicle type (glyph + word), [FareCard]s with only
 * VERIFIED rows sorted and marked cheapest (icon + word); unreadable cards say "Check in app" with no number; stale
 * cards grey out with a clock and a refresh button; typed and network cards carry their tags. Tapping a card opens
 * the app. The bottom bar holds one big action ("Open <next app>" or "Book in <cheapest>") plus "I booked" and
 * "Type price", which open bottom sheets. Explanations live behind the ⓘ button.
 */
@Composable
fun ComparisonScreen(
    data: ComparisonData,
    nowMs: Long,
    manual: ManualPrompt?,
    callbacks: ComparisonCallbacks,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val list = remember(data.rows, nowMs) { ComparisonList.build(data.rows, nowMs) }
    // The price sheet opens by itself when a manual-mode tap brought the user here; a card's "Type price" or the
    // bottom bar opens it for a chosen app.
    var sheetPrompt by remember(manual) { mutableStateOf(manual) }
    var manualOpen by rememberSaveable(manual) { mutableStateOf(manual != null) }
    var bookedOpen by rememberSaveable { mutableStateOf(false) }
    var whyOpen by rememberSaveable { mutableStateOf(false) }

    val readApps = remember(data.rows) { data.rows.map { it.provider }.toSet() }
    val nextApp = data.rideApps.firstOrNull { it !in readApps }
    val best = list.groups.flatMap { it.entries }
        .filter { it.cheapest && !it.stale && it.row.status == QuoteStatus.VERIFIED && !it.row.provider.isNetwork && it.priceText != null }
        .minByOrNull { it.row.price?.last ?: Int.MAX_VALUE }

    RmScaffold(
        modifier = modifier,
        title = stringResource(R.string.compare_title),
        actions = { WhyButton(onClick = { whyOpen = true }, contentDescription = stringResource(R.string.compare_why_cd)) },
        bottomBar = if (data.rideApps.isEmpty() && list.groups.isEmpty()) null else {
            {
                BottomActions(
                    nextApp = nextApp,
                    best = best,
                    showBooked = callbacks.onBookedHere != null && data.rideApps.isNotEmpty(),
                    onOpen = callbacks.onBook,
                    onBooked = { bookedOpen = true },
                    onTypePrice = { sheetPrompt = manual ?: ManualPrompt(null, null); manualOpen = true },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(ComparisonTags.LIST),
            verticalArrangement = Arrangement.spacedBy(RmSpacing.cardGap),
        ) {
            if (manual != null && !manualOpen) {
                InlineBanner(
                    text = manual.message ?: stringResource(R.string.compare_manual_title),
                    tone = RmTone.WARN,
                    icon = Icons.Rounded.Edit,
                    actionLabel = stringResource(R.string.rm_type_price),
                    onAction = { sheetPrompt = manual; manualOpen = true },
                    modifier = Modifier.testTag(ComparisonTags.MANUAL_BANNER),
                )
            }
            if (list.groups.isEmpty()) EmptyState()
            list.groups.forEach { group ->
                GroupHeader(group.vehicleClass)
                group.entries.forEach { entry ->
                    QuoteCard(
                        entry = entry,
                        nowMs = nowMs,
                        callbacks = callbacks,
                        onTypePrice = { p, c ->
                            sheetPrompt = ManualPrompt(manual?.message?.takeIf { manual.provider == p }, p, listOf(c))
                            manualOpen = true
                        },
                    )
                }
            }
        }
    }

    if (manualOpen) {
        ManualPriceSheet(
            prompt = sheetPrompt ?: ManualPrompt(null, null),
            apps = data.rideApps,
            onSubmit = callbacks.onSubmitManual,
            onDismiss = { manualOpen = false },
        )
    }
    val bookedHere = callbacks.onBookedHere
    if (bookedOpen && bookedHere != null) {
        InfoSheet(
            onDismiss = { bookedOpen = false },
            title = stringResource(R.string.compare_booked_heading),
            icon = Icons.Rounded.CheckCircle,
            tone = RmTone.GOOD,
        ) {
            RmListGroup {
                data.rideApps.forEachIndexed { i, provider ->
                    if (i > 0) RmListDivider()
                    RmListRow(
                        title = stringResource(R.string.compare_booked_here, FareReadingMessages.name(context, provider)),
                        icon = Icons.Rounded.CheckCircle,
                        iconTone = RmTone.GOOD,
                        onClick = { bookedOpen = false; bookedHere(provider) },
                    )
                }
            }
        }
    }
    if (whyOpen) {
        InfoSheet(
            onDismiss = { whyOpen = false },
            title = stringResource(R.string.compare_why_title),
            body = stringResource(R.string.compare_why_body),
        )
    }
}

@Composable
private fun GroupHeader(vehicleClass: VehicleClass) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = RmSpacing.s),
    ) {
        VehicleIcon(vehicleClass, size = 24.dp, tint = RideMateTheme.colors.brand, contentDescription = null)
        Spacer(Modifier.width(RmSpacing.s))
        Text(
            stringResource(FareReadingMessages.vehicleClassRes(vehicleClass)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RmSpacing.s),
        modifier = Modifier.fillMaxWidth().padding(top = RmSpacing.xxl),
    ) {
        HeroIllustration(Hero.EMPTY, contentDescription = null)
        Text(stringResource(R.string.compare_empty), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.compare_empty_hint), style = MaterialTheme.typography.bodyMedium, color = RideMateTheme.colors.muted)
    }
}

@Composable
private fun QuoteCard(
    entry: ComparisonEntry,
    nowMs: Long,
    callbacks: ComparisonCallbacks,
    onTypePrice: (Provider, VehicleClass) -> Unit,
) {
    val context = LocalContext.current
    val row = entry.row
    val app = FareReadingMessages.name(context, row.provider)
    // OCR text or the ONDC provider name (protocol data); otherwise the localised class name. Never model text (I5).
    val label = if (row.labelFromOcr || row.status == QuoteStatus.NETWORK) row.displayLabel else stringResource(FareReadingMessages.vehicleClassRes(row.vehicleClass))
    val etaText = if (row.eta == null) entry.etaTextKey?.let { stringResource(FareReadingMessages.gateTextRes(it)) } else null
    val staleText = stringResource(R.string.quote_stale)
    val freshText = stringResource(R.string.quote_seen_at, app, entry.seenAtText)
    val quote = FareQuote(
        provider = row.provider,
        vehicleClass = row.vehicleClass,
        label = label,
        status = row.status,
        priceText = entry.priceText,
        seenAtText = entry.seenAtText,
        providerName = app,
        etaMinutes = row.eta,
        etaText = etaText,
        cheapest = entry.cheapest,
        stale = entry.stale,
        ageMinutes = ((nowMs - row.seenAtMs) / 60_000L).toInt().coerceAtLeast(1),
    )
    val refresh = { callbacks.onRefresh(row.provider) }
    FareCard(
        quote = quote,
        modifier = Modifier
            .testTag(ComparisonTags.row(row.provider, row.vehicleClass))
            // §4.3.6: the state is spoken too (never colour alone).
            .semantics { stateDescription = if (entry.stale) staleText else freshText },
        onClick = when {
            entry.stale -> refresh
            row.provider.isNetwork -> null // ONDC rows are booked inside RideMate
            else -> ({ callbacks.onBook(row.provider) })
        },
        onRefresh = if (entry.stale) refresh else null,
        onTypePrice = if (row.status == QuoteStatus.UNREADABLE && !row.provider.isNetwork) ({ onTypePrice(row.provider, row.vehicleClass) }) else null,
    )
}

@Composable
private fun BottomActions(
    nextApp: Provider?,
    best: ComparisonEntry?,
    showBooked: Boolean,
    onOpen: (Provider) -> Unit,
    onBooked: () -> Unit,
    onTypePrice: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
        when {
            nextApp != null -> {
                val app = FareReadingMessages.name(context, nextApp)
                BigPrimaryButton(
                    text = stringResource(R.string.rm_open_app, app),
                    onClick = { onOpen(nextApp) },
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = stringResource(R.string.compare_open_next_description, app),
                    modifier = Modifier.testTag(ComparisonTags.PRIMARY),
                )
            }
            best != null -> {
                val app = FareReadingMessages.name(context, best.row.provider)
                BigPrimaryButton(
                    text = stringResource(R.string.compare_book_cheapest, app, best.priceText.orEmpty()),
                    onClick = { onOpen(best.row.provider) },
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = stringResource(R.string.compare_book_description, app),
                    modifier = Modifier.testTag(ComparisonTags.PRIMARY),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(RmSpacing.s), modifier = Modifier.fillMaxWidth()) {
            if (showBooked) {
                QuietButton(
                    text = stringResource(R.string.compare_booked_button),
                    onClick = onBooked,
                    icon = Icons.Rounded.CheckCircle,
                    fillWidth = true,
                    modifier = Modifier.weight(1f).testTag(ComparisonTags.BOOKED),
                )
            }
            QuietButton(
                text = stringResource(R.string.rm_type_price),
                onClick = onTypePrice,
                icon = Icons.Rounded.Edit,
                fillWidth = true,
                modifier = Modifier.weight(1f).testTag(ComparisonTags.TYPE_PRICE),
            )
        }
    }
}

/** The "type the price you see" bottom sheet: app tiles, vehicle tiles, the price field and Save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualPriceSheet(
    prompt: ManualPrompt,
    apps: List<Provider>,
    onSubmit: (Provider, VehicleClass, String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var provider by remember(prompt, apps) { mutableStateOf(prompt.provider ?: apps.firstOrNull()) }
    var vehicleClass by remember(prompt) { mutableStateOf(prompt.suggestedClasses.firstOrNull()) }
    var text by rememberSaveable(prompt) { mutableStateOf("") }
    var feedback by remember(prompt) { mutableStateOf<String?>(null) }
    val savedText = stringResource(R.string.compare_manual_saved)
    val chooseFirst = stringResource(R.string.compare_manual_choose)
    InfoSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.compare_manual_title),
        icon = Icons.Rounded.Edit,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(ComparisonTags.MANUAL_FORM),
            verticalArrangement = Arrangement.spacedBy(RmSpacing.m),
        ) {
            prompt.message?.let { InlineBanner(text = it, tone = RmTone.WARN, icon = Icons.Rounded.Info) }
            if (apps.size > 1 || prompt.provider == null) {
                RmSectionTitle(stringResource(R.string.compare_manual_app))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(RmSpacing.s), verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
                    apps.forEach { p ->
                        ProviderChoiceTile(
                            provider = p,
                            selected = provider == p,
                            onClick = { provider = p },
                            name = FareReadingMessages.name(context, p),
                            modifier = Modifier.widthIn(min = 96.dp),
                        )
                    }
                }
            }
            RmSectionTitle(stringResource(R.string.compare_manual_vehicle))
            val classes = (prompt.suggestedClasses + VehicleClass.entries).distinct()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RmSpacing.s), verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
                classes.forEach { c ->
                    VehicleChoiceTile(
                        vehicleClass = c,
                        selected = vehicleClass == c,
                        onClick = { vehicleClass = c },
                        label = stringResource(FareReadingMessages.vehicleClassRes(c)),
                        modifier = Modifier.widthIn(min = 96.dp),
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; feedback = null },
                label = { Text(stringResource(R.string.compare_manual_price_label)) },
                supportingText = { Text(stringResource(R.string.compare_manual_price_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag(ComparisonTags.PRICE_FIELD),
            )
            feedback?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            BigPrimaryButton(
                text = stringResource(R.string.compare_manual_save),
                onClick = {
                    val p = provider
                    val c = vehicleClass
                    if (p == null || c == null) {
                        feedback = chooseFirst
                    } else {
                        onSubmit(p, c, text) { error ->
                            feedback = error ?: savedText
                            if (error == null) text = ""
                        }
                    }
                },
            )
        }
    }
}
