package app.ridemate.dev

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import app.ridemate.data.RecorderPostEntity
import app.ridemate.net.ApiMessages
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.ui.ChipTone
import app.ridemate.ui.InfoCard
import app.ridemate.ui.KeyValueRow
import app.ridemate.ui.MinTouchTarget
import app.ridemate.ui.PrimaryButton
import app.ridemate.ui.RadioRow
import app.ridemate.ui.ScreenScaffold
import app.ridemate.ui.SecondaryButton
import app.ridemate.ui.SectionHeader
import app.ridemate.ui.StatusChip
import app.ridemate.ui.TextAction
import app.ridemate.ui.ToggleRow
import app.ridemate.ui.UiFormat
import java.io.File

class DevActions(
    val back: () -> Unit = {},
    val setRecorderOn: (Boolean) -> Unit = {},
    val exportRecorder: () -> Unit = {},
    val clearRecorder: () -> Unit = {},
    val runCaptureTest: () -> Unit = {},
    val captureGolden: () -> Unit = {},
    val saveGolden: (GoldenScreen) -> Unit = {},
    val removeGolden: (String) -> Unit = {},
    val exportGolden: () -> Unit = {},
    val loadBudget: () -> Unit = {},
    val setOwnerAlerts: (Boolean) -> Unit = {},
    val disable: () -> Unit = {},
    val clearMessage: () -> Unit = {},
)

@Composable
fun DevScreen(state: DevState, imageFile: (String) -> File, actions: DevActions) {
    ScreenScaffold(title = stringResource(R.string.dev_title), onBack = actions.back) {
        if (!state.enabled) {
            InfoCard(title = stringResource(R.string.dev_off_title), body = stringResource(R.string.dev_off_body), tone = ChipTone.WARNING)
            return@ScreenScaffold
        }
        Text(stringResource(R.string.dev_intro), style = MaterialTheme.typography.bodyMedium)
        state.message?.let { msg ->
            InfoCard(title = stringResource(devMessageRes(msg)), body = null, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                TextAction(stringResource(R.string.dev_ok), actions.clearMessage)
            }
        }

        SectionHeader(stringResource(R.string.dev_recorder))
        Text(stringResource(R.string.dev_recorder_body), style = MaterialTheme.typography.bodyMedium)
        ToggleRow(stringResource(R.string.dev_recorder_switch), state.recorderOn, actions.setRecorderOn)
        Text(
            pluralCount(R.plurals.dev_recorder_recent, state.recent.size),
            style = MaterialTheme.typography.labelMedium,
        )
        state.recent.take(RECENT_SHOWN).forEach { RecorderRow(it) }
        SecondaryButton(stringResource(R.string.dev_recorder_export), actions.exportRecorder, icon = Icons.Filled.IosShare)
        TextAction(stringResource(R.string.dev_recorder_clear), actions.clearRecorder)

        SectionHeader(stringResource(R.string.dev_capture_test))
        Text(stringResource(R.string.dev_capture_test_body), style = MaterialTheme.typography.bodyMedium)
        CaptureTestResultView(state.captureTest)
        SecondaryButton(
            stringResource(R.string.dev_capture_test_run),
            actions.runCaptureTest,
            enabled = state.captureTest != CaptureTestStatus.Running,
            icon = Icons.Filled.CameraAlt,
        )

        SectionHeader(stringResource(R.string.dev_golden))
        Text(stringResource(R.string.dev_golden_body), style = MaterialTheme.typography.bodyMedium)
        SecondaryButton(stringResource(R.string.dev_golden_capture), actions.captureGolden, icon = Icons.Filled.CameraAlt)
        state.golden.forEachIndexed { index, screen ->
            GoldenEditor(index + 1, screen, imageFile(screen.sha256), actions.saveGolden, actions.removeGolden)
        }
        val complete = state.golden.count(GoldenValidator::isComplete)
        Text(stringResource(R.string.dev_golden_ready, complete, state.golden.size), style = MaterialTheme.typography.labelMedium)
        SecondaryButton(stringResource(R.string.dev_golden_export), actions.exportGolden, enabled = complete > 0, icon = Icons.Filled.IosShare)

        SectionHeader(stringResource(R.string.dev_budget))
        BudgetView(state.budget)
        SecondaryButton(stringResource(R.string.dev_budget_load), actions.loadBudget, enabled = state.budget != BudgetStatus.Loading)

        SectionHeader(stringResource(R.string.dev_owner_alerts))
        ToggleRow(
            title = stringResource(R.string.dev_owner_alerts_switch),
            checked = state.ownerAlertsOn,
            onCheckedChange = actions.setOwnerAlerts,
            summary = stringResource(R.string.dev_owner_alerts_body),
            enabled = state.ownerAlerts != OwnerAlertsStatus.Working,
        )
        OwnerAlertsText(state.ownerAlerts)

        SectionHeader(stringResource(R.string.dev_leave))
        TextAction(stringResource(R.string.dev_disable), actions.disable)
    }
}

private const val RECENT_SHOWN = 20

@Composable
private fun pluralCount(res: Int, count: Int): String = androidx.compose.ui.res.pluralStringResource(res, count, count)

fun devMessageRes(message: DevMessage): Int = when (message) {
    DevMessage.RECORDER_EMPTY -> R.string.dev_msg_recorder_empty
    DevMessage.RECORDER_CLEARED -> R.string.dev_msg_recorder_cleared
    DevMessage.GOLDEN_SAVED -> R.string.dev_msg_golden_saved
    DevMessage.GOLDEN_NOTHING_TO_EXPORT -> R.string.dev_msg_golden_nothing
    DevMessage.GOLDEN_DENIED -> R.string.dev_msg_golden_denied
    DevMessage.GOLDEN_BLACK_FRAME -> R.string.dev_msg_golden_black
    DevMessage.GOLDEN_UNAVAILABLE -> R.string.dev_msg_golden_unavailable
    DevMessage.GOLDEN_CAPTURED -> R.string.dev_msg_golden_captured
    DevMessage.EXPORT_FAILED -> R.string.dev_msg_export_failed
}

@Composable
private fun RecorderRow(row: RecorderPostEntity) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        val event = if (row.removalReason == null) stringResource(R.string.dev_recorder_posted) else
            stringResource(R.string.dev_recorder_removed, RecorderExport.reasonName(row.removalReason).orEmpty())
        Text("${UiFormat.timeIstWithSeconds(row.atUtc)} · ${row.packageName} · $event", style = MaterialTheme.typography.labelMedium)
        Text(
            stringResource(
                R.string.dev_recorder_meta,
                row.channelId.orEmpty(),
                row.category.orEmpty(),
                row.isOngoing.toString(),
                row.notifId,
                row.redacted.toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        listOfNotNull(row.titleMasked, row.textMasked).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CaptureTestResultView(status: CaptureTestStatus) {
    when (status) {
        CaptureTestStatus.Idle -> Unit
        CaptureTestStatus.Running -> Text(stringResource(R.string.dev_capture_test_running), style = MaterialTheme.typography.bodyMedium)
        is CaptureTestStatus.Finished -> when (val r = status.result) {
            is CaptureTestResult.Done -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val yes = stringResource(R.string.dev_yes)
                val no = stringResource(R.string.dev_no)
                StatusChip(
                    text = if (r.blackFrame) yes else no,
                    tone = if (r.blackFrame) ChipTone.BAD else ChipTone.GOOD,
                    label = stringResource(R.string.dev_capture_black_frame),
                )
                StatusChip(
                    text = if (r.bubbleVisible) yes else no,
                    tone = if (r.bubbleVisible) ChipTone.WARNING else ChipTone.GOOD,
                    label = stringResource(R.string.dev_capture_bubble_visible),
                )
            }
            CaptureTestResult.Denied -> InfoCard(stringResource(R.string.dev_capture_denied), null, tone = ChipTone.WARNING)
            CaptureTestResult.Unavailable -> InfoCard(stringResource(R.string.dev_capture_unavailable), null, tone = ChipTone.WARNING)
        }
    }
}

/** The owner budget block: AI and compute spend against their budgets, today's spend vs the pacing cap, horizon. */
object BudgetLines {
    fun lines(context: Context, check: ConfigCheckResponse): List<Pair<String, String>> {
        val budget = check.budget ?: return emptyList()
        fun spend(spent: Double?, total: Double?): String {
            if (spent == null || total == null) return context.getString(R.string.dev_budget_unknown)
            val pct = UiFormat.percent(spent, total)
            return context.getString(R.string.dev_budget_of, UiFormat.usd(spent), UiFormat.usd(total), pct ?: 0)
        }
        return buildList {
            add(context.getString(R.string.dev_budget_ai) to spend(budget.aiSpentUsd, budget.aiBudgetUsd))
            add(context.getString(R.string.dev_budget_compute) to spend(budget.computeSpentUsd, budget.computeBudgetUsd))
            add(context.getString(R.string.dev_budget_today) to spend(budget.daySpentUsd, budget.dayCapUsd))
            add(
                context.getString(R.string.dev_budget_horizon) to
                    (UiFormat.isoDate(budget.horizonEnd) ?: context.getString(R.string.dev_budget_unknown)),
            )
            add(context.getString(R.string.dev_budget_profile) to (check.capProfile ?: context.getString(R.string.dev_budget_unknown)))
            add(
                context.getString(R.string.dev_budget_ai_state) to
                    if (check.aiEnabled) context.getString(R.string.dev_budget_ai_on) else (check.reason ?: context.getString(R.string.dev_budget_ai_off)),
            )
        }
    }
}

@Composable
private fun BudgetView(status: BudgetStatus) {
    val context = LocalContext.current
    when (status) {
        BudgetStatus.Idle -> Text(stringResource(R.string.dev_budget_idle), style = MaterialTheme.typography.bodyMedium)
        BudgetStatus.Loading -> Text(stringResource(R.string.dev_budget_loading), style = MaterialTheme.typography.bodyMedium)
        is BudgetStatus.Failed -> InfoCard(
            title = stringResource(R.string.dev_budget_failed),
            body = ApiMessages.text(context, status.result),
            tone = ChipTone.WARNING,
        )
        is BudgetStatus.Loaded -> {
            val lines = BudgetLines.lines(context, status.check)
            if (lines.isEmpty()) {
                InfoCard(stringResource(R.string.dev_budget_not_owner), null, tone = ChipTone.WARNING)
            } else {
                lines.forEach { (label, value) -> KeyValueRow(label, value) }
            }
        }
    }
}

@Composable
private fun OwnerAlertsText(status: OwnerAlertsStatus) {
    val context = LocalContext.current
    val text = when (status) {
        OwnerAlertsStatus.Idle -> null
        OwnerAlertsStatus.Working -> stringResource(R.string.dev_owner_alerts_working)
        OwnerAlertsStatus.Registered -> stringResource(R.string.dev_owner_alerts_registered)
        OwnerAlertsStatus.Off -> stringResource(R.string.dev_owner_alerts_off)
        OwnerAlertsStatus.NoToken -> stringResource(R.string.dev_owner_alerts_no_token)
        is OwnerAlertsStatus.Failed -> ApiMessages.text(context, status.result)
    }
    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** A single-choice field: shows the current value; tapping opens a list of radio options. */
@Composable
private fun <T> ChoiceField(label: String, options: List<T>, selected: T?, name: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = selected?.let { name(it) } ?: stringResource(R.string.dev_choose)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(role = Role.Button) { open = true }
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(current, style = MaterialTheme.typography.bodyLarge)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        RadioRow(name(option), option == selected, {
                            onSelect(option)
                            open = false
                        })
                    }
                }
            },
            confirmButton = { TextButton({ open = false }, Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.dev_close)) } },
        )
    }
}

private class RowDraft(row: GoldenRow?) {
    var vehicleClass by mutableStateOf(row?.vehicleClass?.let(VehicleClass::fromWireId))
    var label by mutableStateOf(row?.label.orEmpty())
    var priceMin by mutableStateOf(row?.priceMin?.toString().orEmpty())
    var priceMax by mutableStateOf(row?.priceMax?.toString().orEmpty())
    var eta by mutableStateOf(row?.etaMin?.toString().orEmpty())
    var struck by mutableStateOf(row?.struckPrice?.toString().orEmpty())

    fun toRow(): GoldenRow = GoldenRow(
        vehicleClass = vehicleClass?.wireId.orEmpty(),
        label = label.trim(),
        priceMin = priceMin.trim().toIntOrNull(),
        priceMax = (priceMax.trim().ifEmpty { priceMin.trim() }).toIntOrNull(),
        etaMin = eta.trim().toIntOrNull(),
        struckPrice = struck.trim().toIntOrNull(),
    )
}

/** The labelling form for one masked capture: app, screen type, and the true price + ETA of every row. */
@Composable
private fun GoldenEditor(number: Int, screen: GoldenScreen, image: File, onSave: (GoldenScreen) -> Unit, onRemove: (String) -> Unit) {
    var app by remember(screen.sha256) { mutableStateOf(GoldenWire.APPS.firstOrNull { it.wireId == screen.app }) }
    var type by remember(screen.sha256) { mutableStateOf(GoldenWire.screenTypeOf(screen.screenType)) }
    var tripKm by remember(screen.sha256) { mutableStateOf(screen.tripKm?.toString().orEmpty()) }
    var driverEta by remember(screen.sha256) { mutableStateOf(screen.driverEtaMin?.toString().orEmpty()) }
    var driverDistance by remember(screen.sha256) { mutableStateOf(screen.driverDistanceText.orEmpty()) }
    var tags by remember(screen.sha256) { mutableStateOf(screen.tags.joinToString(", ")) }
    val rows = remember(screen.sha256) { mutableStateListOf<RowDraft>().apply { screen.rows.forEach { add(RowDraft(it)) } } }

    fun build() = screen.copy(
        app = app?.wireId.orEmpty(),
        screenType = type?.let(GoldenWire::screenType).orEmpty(),
        tripKm = tripKm.trim().toDoubleOrNull(),
        rows = rows.map { it.toRow() },
        driverEtaMin = driverEta.trim().toIntOrNull(),
        driverDistanceText = driverDistance.trim().ifEmpty { null },
        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dev_golden_item, number), style = MaterialTheme.typography.titleSmall)
            Thumbnail(image, stringResource(R.string.dev_golden_image_cd, number))
            ChoiceField(stringResource(R.string.dev_golden_app), GoldenWire.APPS, app, { it.displayName }) { app = it }
            ChoiceField(stringResource(R.string.dev_golden_screen_type), ScreenType.entries, type, { GoldenWire.screenType(it) }) { type = it }
            NumberField(stringResource(R.string.dev_golden_trip_km), tripKm, decimal = true) { tripKm = it }
            rows.forEachIndexed { i, row ->
                Text(stringResource(R.string.dev_golden_row, i + 1), style = MaterialTheme.typography.labelLarge)
                ChoiceField(stringResource(R.string.dev_golden_vehicle), VehicleClass.entries, row.vehicleClass, { it.wireId }) { row.vehicleClass = it }
                OutlinedTextField(
                    value = row.label,
                    onValueChange = { row.label = it },
                    label = { Text(stringResource(R.string.dev_golden_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.dev_golden_price_min), row.priceMin, Modifier.weight(1f)) { row.priceMin = it }
                    NumberField(stringResource(R.string.dev_golden_price_max), row.priceMax, Modifier.weight(1f)) { row.priceMax = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.dev_golden_eta), row.eta, Modifier.weight(1f)) { row.eta = it }
                    NumberField(stringResource(R.string.dev_golden_struck), row.struck, Modifier.weight(1f)) { row.struck = it }
                }
                TextAction(stringResource(R.string.dev_golden_remove_row, i + 1), { rows.removeAt(i) })
            }
            SecondaryButton(stringResource(R.string.dev_golden_add_row), { rows.add(RowDraft(null)) })
            if (type == ScreenType.DRIVER_ASSIGNED) {
                NumberField(stringResource(R.string.dev_golden_driver_eta), driverEta) { driverEta = it }
                OutlinedTextField(
                    value = driverDistance,
                    onValueChange = { driverDistance = it },
                    label = { Text(stringResource(R.string.dev_golden_driver_distance)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text(stringResource(R.string.dev_golden_tags)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            val problems = GoldenValidator.problems(build())
            if (problems.isEmpty()) {
                StatusChip(stringResource(R.string.dev_golden_complete), ChipTone.GOOD)
            } else {
                val texts = problems.map { problemText(it) }
                StatusChip(
                    texts.joinToString(", "),
                    ChipTone.WARNING,
                    label = stringResource(R.string.dev_golden_incomplete),
                )
            }
            PrimaryButton(stringResource(R.string.dev_golden_save), { onSave(build()) })
            TextAction(stringResource(R.string.dev_golden_delete), { onRemove(screen.sha256) })
        }
    }
}

@Composable
private fun problemText(problem: GoldenProblem): String = stringResource(
    when (problem) {
        GoldenProblem.NO_APP -> R.string.dev_golden_p_app
        GoldenProblem.NO_SCREEN_TYPE -> R.string.dev_golden_p_type
        GoldenProblem.NO_ROWS -> R.string.dev_golden_p_rows
        GoldenProblem.ROW_LABEL_MISSING -> R.string.dev_golden_p_label
        GoldenProblem.ROW_PRICE_MISSING -> R.string.dev_golden_p_price
        GoldenProblem.ROW_PRICE_ORDER -> R.string.dev_golden_p_order
        GoldenProblem.ROW_BAD_CLASS -> R.string.dev_golden_p_class
        GoldenProblem.BAD_NUMBER -> R.string.dev_golden_p_number
    },
)

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || (decimal && it == '.') }) },
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
private fun Thumbnail(file: File, description: String) {
    val bitmap = remember(file.path, file.lastModified()) {
        runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dev_golden_no_image), style = MaterialTheme.typography.bodySmall)
        }
    }
}
