package app.ridemate.compare

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.gate.ComparisonEntry
import app.ridemate.core.gate.ComparisonList
import app.ridemate.core.gate.GateText
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
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
    /**
     * A stale row ("old, tap to refresh"): opens the app and arms the bubble's "Refresh <app> price" so the next tap
     * reads it; for an open-network row ([Provider.ONDC]) it searches the network again.
     */
    val onRefresh: (Provider) -> Unit,
    /** Saves a typed price; [done] gets null on success, else the message to show. */
    val onSubmitManual: (provider: Provider, vehicleClass: VehicleClass, text: String, done: (String?) -> Unit) -> Unit,
    /** "Book" on an open-network row ([GatedRow.rowId] of the network quote); null hides it (booked inside RideMate). */
    val onBookNetwork: ((rowId: String) -> Unit)? = null,
)

/** Test tags (stable; used by UI tests). */
object ComparisonTags {
    const val LIST = "comparison_list"
    const val MANUAL_FORM = "comparison_manual_form"
    const val OPEN_APPS = "comparison_open_apps"
    fun openApp(provider: Provider) = "open_${provider.wireId}"
    const val PRICE_FIELD = "comparison_manual_price"
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
 * The comparison list (goals 5–8): rows grouped by vehicle type; only VERIFIED rows are sorted and marked cheapest
 * (icon + word, not colour); unreadable rows say "Couldn't read · check in app" with no number; every row says when
 * it was seen and, after 60 s, greys out and adds "old, tap to refresh"; ONDC rows say "network quote" and typed rows
 * "entered by you". Each row has Book (opens the app), and each app has "Booked in <app>".
 *
 * Goal 3: every app of the trip that is still before booking has "Open <app>" (the route ready, the leg marked
 * opened so Read fares reads that app), also before it has any price row, so each selected app can be compared.
 */
@Composable
fun ComparisonScreen(
    data: ComparisonData,
    nowMs: Long,
    manual: ManualPrompt?,
    callbacks: ComparisonCallbacks,
    modifier: Modifier = Modifier,
) {
    val list = remember(data.rows, nowMs) { ComparisonList.build(data.rows, nowMs) }
    var manualOpen by rememberSaveable(manual) { mutableStateOf(manual != null) }
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(ComparisonTags.LIST),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.compare_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (data.openableApps.isNotEmpty()) {
            item(key = "open_apps") { OpenApps(data.openableApps, callbacks.onBook) }
        }
        if (manualOpen || manual != null) {
            item {
                ManualPriceForm(
                    prompt = manual ?: ManualPrompt(null, null),
                    apps = data.rideApps,
                    classes = data.formClasses,
                    onSubmit = callbacks.onSubmitManual,
                )
            }
        }
        if (list.groups.isEmpty()) {
            item { Text(stringResource(R.string.compare_empty), style = MaterialTheme.typography.bodyLarge) }
        }
        list.groups.forEach { group ->
            item(key = "h_${group.vehicleClass.name}") {
                Text(
                    stringResource(FareReadingMessages.vehicleClassRes(group.vehicleClass)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
            }
            itemsIndexed(
                group.entries,
                key = { i, entry -> entry.row.rowId ?: "${group.vehicleClass.name}_${entry.row.provider.name}_$i" },
            ) { _, entry ->
                QuoteRow(entry, callbacks, demoSeller = data.networkDemoSeller)
            }
        }
        if (data.rideApps.isNotEmpty()) {
            item { AppActions(data.rideApps, callbacks, onTypePrice = { manualOpen = true }) }
        }
    }
}

/** "Open <app>" for each app still to compare: opens it with the route ready (the user taps there themselves). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenApps(apps: List<Provider>, onOpen: (Provider) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(ComparisonTags.OPEN_APPS)) {
        Text(stringResource(R.string.compare_open_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            apps.forEach { provider ->
                val app = FareReadingMessages.name(context, provider)
                val description = stringResource(R.string.compare_open_app_description, app)
                OutlinedButton(
                    onClick = { onOpen(provider) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(ComparisonTags.openApp(provider))
                        .semantics { contentDescription = description },
                ) { Text(stringResource(R.string.compare_open_app, app)) }
            }
        }
    }
}

@Composable
private fun QuoteRow(entry: ComparisonEntry, callbacks: ComparisonCallbacks, demoSeller: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val row = entry.row
    val app = FareReadingMessages.name(context, row.provider)
    // OCR text or the ONDC provider name (protocol data); otherwise the localised class name. Never model text (I5).
    val label = if (row.labelFromOcr || row.status == QuoteStatus.NETWORK) row.displayLabel else stringResource(FareReadingMessages.vehicleClassRes(row.vehicleClass))
    val refreshLabel = stringResource(R.string.compare_refresh, app)
    val bookDescription = stringResource(R.string.compare_book_description, app)
    // §4.3.6: every row keeps "seen in <app> at …"; after 60 s it also says "old, tap to refresh" (never colour alone).
    val seenAt = stringResource(R.string.quote_seen_at, app, entry.seenAtText)
    val staleText = stringResource(R.string.quote_stale)
    val freshness = if (entry.stale) staleText else seenAt
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ComparisonTags.row(row.provider, row.vehicleClass))
            .alpha(if (entry.stale) STALE_ALPHA else 1f)
            .then(
                if (entry.stale) Modifier.clickable(onClickLabel = refreshLabel, role = Role.Button) { callbacks.onRefresh(row.provider) } else Modifier,
            )
            .semantics { stateDescription = freshness },
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app, style = MaterialTheme.typography.labelLarge)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
                if (entry.priceText != null) {
                    Text(entry.priceText!!, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        stringResource(R.string.quote_unreadable),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(140.dp),
                    )
                }
            }
            if (entry.cheapest) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.quote_cheapest), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            entry.statusTextKey?.takeIf { it != GateText.UNREADABLE }?.let { key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (key == GateText.ENTERED_BY_YOU) Icons.Filled.Edit else Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    val status = stringResource(FareReadingMessages.gateTextRes(key))
                    // The hackathon's mock seller is always labelled as such (goal 17).
                    val text = if (row.status == QuoteStatus.NETWORK && demoSeller) {
                        stringResource(R.string.ondc_row_label, status, stringResource(R.string.ondc_label_demo_seller))
                    } else {
                        status
                    }
                    Text(text, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (row.eta != null) {
                Text(pluralStringResource(R.plurals.compare_eta_minutes, row.eta!!, row.eta!!), style = MaterialTheme.typography.bodyMedium)
            } else if (entry.etaTextKey != null) {
                Text(stringResource(FareReadingMessages.gateTextRes(entry.etaTextKey!!)), style = MaterialTheme.typography.bodyMedium)
            }
            if (entry.stale) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(staleText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(seenAt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                val bookNetwork = callbacks.onBookNetwork
                val rowId = row.rowId
                if (!row.provider.isNetwork) {
                    OutlinedButton(
                        onClick = { callbacks.onBook(row.provider) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = bookDescription },
                    ) { Text(stringResource(R.string.compare_book, app)) }
                } else if (bookNetwork != null && rowId != null) {
                    val className = stringResource(FareReadingMessages.vehicleClassRes(row.vehicleClass))
                    val networkDescription = stringResource(R.string.ondc_action_book_description, label.ifBlank { app }, entry.priceText ?: className)
                    OutlinedButton(
                        onClick = { bookNetwork(rowId) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = networkDescription },
                    ) { Text(stringResource(R.string.ondc_action_book)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppActions(apps: List<Provider>, callbacks: ComparisonCallbacks, onTypePrice: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.compare_booked_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val bookedHere = callbacks.onBookedHere
            if (bookedHere != null) {
                apps.forEach { provider ->
                    val app = FareReadingMessages.name(context, provider)
                    Button(onClick = { bookedHere(provider) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.compare_booked_here, app))
                    }
                }
            }
            OutlinedButton(onClick = onTypePrice, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.compare_type_price))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualPriceForm(
    prompt: ManualPrompt,
    apps: List<Provider>,
    classes: List<VehicleClass>,
    onSubmit: (Provider, VehicleClass, String, (String?) -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var provider by remember(prompt, apps) { mutableStateOf(prompt.provider ?: apps.firstOrNull()) }
    // Only the vehicle types chosen for this trip: a typed price of another type would not be listed.
    val offered = remember(prompt, classes) { (prompt.suggestedClasses.filter { it in classes } + classes).distinct() }
    var vehicleClass by remember(prompt, classes) { mutableStateOf(prompt.suggestedClasses.firstOrNull { it in classes } ?: classes.singleOrNull()) }
    var text by rememberSaveable(prompt) { mutableStateOf("") }
    var feedback by remember(prompt) { mutableStateOf<String?>(null) }
    val savedText = stringResource(R.string.compare_manual_saved)
    val chooseFirst = stringResource(R.string.compare_manual_choose)
    Card(
        modifier = Modifier.fillMaxWidth().testTag(ComparisonTags.MANUAL_FORM),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.compare_manual_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            prompt.message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (apps.size > 1 || prompt.provider == null) {
                Text(stringResource(R.string.compare_manual_app), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    apps.forEach { p ->
                        FilterChip(
                            selected = provider == p,
                            onClick = { provider = p },
                            label = { Text(FareReadingMessages.name(context, p)) },
                        )
                    }
                }
            }
            Text(stringResource(R.string.compare_manual_vehicle), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offered.forEach { c ->
                    FilterChip(
                        selected = vehicleClass == c,
                        onClick = { vehicleClass = c },
                        label = { Text(stringResource(FareReadingMessages.vehicleClassRes(c))) },
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; feedback = null },
                label = { Text(stringResource(R.string.compare_manual_price_label)) },
                supportingText = { Text(stringResource(R.string.compare_manual_price_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ComparisonTags.PRICE_FIELD),
            )
            feedback?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Button(
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
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.compare_manual_save)) }
        }
    }
}

private const val STALE_ALPHA = 0.55f
