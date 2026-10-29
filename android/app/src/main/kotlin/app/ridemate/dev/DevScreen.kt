package app.ridemate.dev

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.UiFormat
import app.ridemate.ui.components.BigPrimaryButton
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
import app.ridemate.ui.components.RmRadioRow
import app.ridemate.ui.components.RmScaffold
import app.ridemate.ui.components.RmSectionTitle
import app.ridemate.ui.components.RmToggleRow
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.WhyButton
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

/** Which section's explanation sheet is open (the long text lives there, not on the screen). */
private enum class DevInfo(@StringRes val title: Int, @StringRes val body: Int) {
    INTRO(R.string.dev_title, R.string.dev_intro),
    RECORDER(R.string.dev_recorder, R.string.dev_recorder_body),
    CAPTURE(R.string.dev_capture_test, R.string.dev_capture_test_body),
    GOLDEN(R.string.dev_golden, R.string.dev_golden_body),
}

/** A section title with an ⓘ that opens its explanation. */
@Composable
private fun DevSection(@StringRes title: Int, @StringRes why: Int, onWhy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RmSectionTitle(stringResource(title), Modifier.weight(1f))
        WhyButton(onClick = onWhy, contentDescription = stringResource(why))
    }
}

/** A titled card in one tone (warnings, failures, "only for the owner"). */
@Composable
private fun NoticeCard(title: String, body: String?, tone: RmTone, modifier: Modifier = Modifier) {
    RmCard(modifier = modifier, tone = tone) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun DevScreen(state: DevState, imageFile: (String) -> File, actions: DevActions) {
    var info by rememberSaveable { mutableStateOf<DevInfo?>(null) }
    RmScaffold(
        title = stringResource(R.string.dev_title),
        onBack = actions.back,
        actions = {
            if (state.enabled) WhyButton(onClick = { info = DevInfo.INTRO }, contentDescription = stringResource(R.string.dev_about))
        },
    ) {
        if (!state.enabled) {
            RmCard(tone = RmTone.WARN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconWell(Icons.Rounded.DeveloperMode, tone = RmTone.WARN)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.dev_off_title), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.dev_off_body), style = MaterialTheme.typography.bodyMedium)
            }
            return@RmScaffold
        }
        state.message?.let { msg ->
            InlineBanner(
                text = stringResource(devMessageRes(msg)),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                actionLabel = stringResource(R.string.dev_ok),
                onAction = actions.clearMessage,
            )
        }

        DevSection(R.string.dev_recorder, R.string.dev_recorder_why) { info = DevInfo.RECORDER }
        RmListGroup {
            RmToggleRow(
                stringResource(R.string.dev_recorder_switch),
                state.recorderOn,
                actions.setRecorderOn,
                icon = Icons.Rounded.FiberManualRecord,
                summary = pluralCount(R.plurals.dev_recorder_recent, state.recent.size),
            )
            state.recent.take(RECENT_SHOWN).forEach {
                RmListDivider()
                RecorderRow(it)
            }
        }
        QuietButton(stringResource(R.string.dev_recorder_export), actions.exportRecorder, icon = Icons.Rounded.IosShare, fillWidth = true)
        QuietButton(
            stringResource(R.string.dev_recorder_clear),
            actions.clearRecorder,
            icon = Icons.Rounded.Delete,
            fillWidth = true,
            style = QuietStyle.Text,
        )

        DevSection(R.string.dev_capture_test, R.string.dev_capture_why) { info = DevInfo.CAPTURE }
        CaptureTestResultView(state.captureTest)
        QuietButton(
            stringResource(R.string.dev_capture_test_run),
            actions.runCaptureTest,
            enabled = state.captureTest != CaptureTestStatus.Running,
            icon = Icons.Rounded.CameraAlt,
            fillWidth = true,
        )

        DevSection(R.string.dev_golden, R.string.dev_golden_why) { info = DevInfo.GOLDEN }
        QuietButton(stringResource(R.string.dev_golden_capture), actions.captureGolden, icon = Icons.Rounded.CameraAlt, fillWidth = true)
        state.golden.forEachIndexed { index, screen ->
            GoldenEditor(index + 1, screen, imageFile(screen.sha256), actions.saveGolden, actions.removeGolden)
        }
        val complete = state.golden.count(GoldenValidator::isComplete)
        RmPill(
            stringResource(R.string.dev_golden_ready, complete, state.golden.size),
            tone = if (complete > 0) RmTone.GOOD else RmTone.NEUTRAL,
        )
        QuietButton(
            stringResource(R.string.dev_golden_export),
            actions.exportGolden,
            enabled = complete > 0,
            icon = Icons.Rounded.IosShare,
            fillWidth = true,
        )

        RmSectionTitle(stringResource(R.string.dev_budget))
        BudgetView(state.budget)
        QuietButton(
            stringResource(R.string.dev_budget_load),
            actions.loadBudget,
            enabled = state.budget != BudgetStatus.Loading,
            icon = Icons.Rounded.Refresh,
            fillWidth = true,
        )

        RmSectionTitle(stringResource(R.string.dev_owner_alerts))
        RmListGroup {
            RmToggleRow(
                title = stringResource(R.string.dev_owner_alerts_switch),
                checked = state.ownerAlertsOn,
                onCheckedChange = actions.setOwnerAlerts,
                icon = if (state.ownerAlertsOn) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                summary = stringResource(R.string.dev_owner_alerts_body),
                enabled = state.ownerAlerts != OwnerAlertsStatus.Working,
            )
        }
        OwnerAlertsText(state.ownerAlerts)

        RmSectionTitle(stringResource(R.string.dev_leave))
        RmListGroup {
            RmListRow(
                stringResource(R.string.dev_disable),
                icon = Icons.Rounded.PowerSettingsNew,
                iconTone = RmTone.DANGER,
                destructive = true,
                showChevron = false,
                onClick = actions.disable,
            )
        }
        Spacer(Modifier.size(16.dp))
    }
    info?.let { open ->
        InfoSheet(onDismiss = { info = null }, title = stringResource(open.title), body = stringResource(open.body))
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
    DevMessage.STORAGE_FAILED -> R.string.dev_msg_storage_failed
}

/** One Recorder entry: time · package · event, then the masked metadata (never unmasked text). */
@Composable
private fun RecorderRow(row: RecorderPostEntity) {
    val colors = RideMateTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val event = if (row.removalReason == null) stringResource(R.string.dev_recorder_posted) else
            stringResource(R.string.dev_recorder_removed, RecorderExport.reasonName(row.removalReason).orEmpty())
        Text("${UiFormat.timeIstWithSeconds(row.atUtc)} · ${row.packageName} · $event", style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(
                R.string.dev_recorder_meta,
                row.channelId.orEmpty(),
                row.category.orEmpty(),
                row.isOngoing.toString(),
                row.notifId,
                row.redacted.toString(),
            ),
            style = RideMateTheme.mono,
            color = colors.muted,
        )
        listOfNotNull(row.titleMasked, row.textMasked).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CaptureTestResultView(status: CaptureTestStatus) {
    when (status) {
        CaptureTestStatus.Idle -> Unit
        CaptureTestStatus.Running -> RmPill(stringResource(R.string.dev_capture_test_running), tone = RmTone.BRAND)
        is CaptureTestStatus.Finished -> when (val r = status.result) {
            is CaptureTestResult.Done -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val yes = stringResource(R.string.dev_yes)
                val no = stringResource(R.string.dev_no)
                val black = stringResource(R.string.dev_capture_black_frame) + ": " + (if (r.blackFrame) yes else no)
                val bubble = stringResource(R.string.dev_capture_bubble_visible) + ": " + (if (r.bubbleVisible) yes else no)
                RmPill(black, tone = if (r.blackFrame) RmTone.DANGER else RmTone.GOOD)
                RmPill(bubble, tone = if (r.bubbleVisible) RmTone.WARN else RmTone.GOOD)
            }
            CaptureTestResult.Denied -> InlineBanner(stringResource(R.string.dev_capture_denied), tone = RmTone.WARN)
            CaptureTestResult.Unavailable -> InlineBanner(stringResource(R.string.dev_capture_unavailable), tone = RmTone.WARN)
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
            add(context.getString(R.string.dev_budget_profile) to profileText(context, check.capProfile))
            add(context.getString(R.string.dev_budget_ai_state) to aiStateText(context, check))
        }
    }

    /** The server's `cap_profile` in words; an unknown value is shown as sent (it is a server enum, never model text). */
    fun profileText(context: Context, profile: String?): String = when (profile) {
        null -> context.getString(R.string.dev_budget_unknown)
        "normal" -> context.getString(R.string.dev_profile_normal)
        "judging" -> context.getString(R.string.dev_profile_judging)
        else -> profile
    }

    /** "On", or "Off: <why>" from the server's `reason` (contracts §1.3). */
    fun aiStateText(context: Context, check: ConfigCheckResponse): String {
        if (check.aiEnabled) return context.getString(R.string.dev_budget_ai_on)
        val reason = check.reason ?: return context.getString(R.string.dev_budget_ai_off)
        val res = reasonRes(reason) ?: return context.getString(R.string.dev_budget_ai_off_code, reason)
        return context.getString(R.string.dev_budget_ai_off_because, context.getString(res))
    }

    @StringRes
    fun reasonRes(reason: String): Int? = when (reason) {
        "signed_out" -> R.string.dev_reason_signed_out
        "anonymous" -> R.string.dev_reason_anonymous
        "kill_switch" -> R.string.dev_reason_kill_switch
        "budget" -> R.string.dev_reason_budget
        "day_cap" -> R.string.dev_reason_day_cap
        "tier_pause" -> R.string.dev_reason_tier_pause
        "quota" -> R.string.dev_reason_quota
        "breaker" -> R.string.dev_reason_breaker
        "meter" -> R.string.dev_reason_meter
        "model_missing" -> R.string.dev_reason_model_missing
        else -> null
    }
}

@Composable
private fun BudgetView(status: BudgetStatus) {
    val context = LocalContext.current
    when (status) {
        BudgetStatus.Idle -> InlineBanner(stringResource(R.string.dev_budget_idle), icon = Icons.Rounded.Savings)
        BudgetStatus.Loading -> RmPill(stringResource(R.string.dev_budget_loading), tone = RmTone.BRAND)
        is BudgetStatus.Failed -> NoticeCard(
            title = stringResource(R.string.dev_budget_failed),
            body = ApiMessages.text(context, status.result),
            tone = RmTone.WARN,
        )
        is BudgetStatus.Loaded -> {
            val lines = BudgetLines.lines(context, status.check)
            if (lines.isEmpty()) {
                NoticeCard(stringResource(R.string.dev_budget_not_owner), null, tone = RmTone.WARN)
            } else {
                RmListGroup {
                    lines.forEachIndexed { i, (label, value) ->
                        if (i > 0) RmListDivider()
                        RmListRow(label, summary = value)
                    }
                }
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
    val tone = when (status) {
        OwnerAlertsStatus.Registered -> RmTone.GOOD
        OwnerAlertsStatus.NoToken, is OwnerAlertsStatus.Failed -> RmTone.WARN
        else -> RmTone.NEUTRAL
    }
    if (text != null) InlineBanner(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, tone = tone)
}

/** A single-choice field: shows the current value; tapping opens a sheet of radio options. */
@Composable
private fun <T> ChoiceField(label: String, options: List<T>, selected: T?, name: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = selected?.let { name(it) } ?: stringResource(R.string.dev_choose)
    RmListGroup {
        RmListRow(label, summary = current, onClick = { open = true })
    }
    if (open) {
        InfoSheet(onDismiss = { open = false }, title = label, icon = null) {
            RmListGroup {
                options.forEachIndexed { i, option ->
                    if (i > 0) RmListDivider()
                    RmRadioRow(name(option), option == selected, {
                        onSelect(option)
                        open = false
                    })
                }
            }
        }
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

    RmCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(Icons.Rounded.CameraAlt, tone = RmTone.BRAND, size = 32.dp)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.dev_golden_item, number), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
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
                QuietButton(
                    stringResource(R.string.dev_golden_remove_row, i + 1),
                    { rows.removeAt(i) },
                    icon = Icons.Rounded.Delete,
                    style = QuietStyle.Text,
                )
            }
            QuietButton(stringResource(R.string.dev_golden_add_row), { rows.add(RowDraft(null)) }, icon = Icons.Rounded.Add, fillWidth = true)
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
                RmPill(stringResource(R.string.dev_golden_complete), tone = RmTone.GOOD)
            } else {
                val texts = problems.map { problemText(it) }.joinToString(", ")
                RmPill(
                    texts,
                    tone = RmTone.WARN,
                    contentDescription = stringResource(R.string.dev_golden_incomplete) + ": " + texts,
                )
            }
            BigPrimaryButton(stringResource(R.string.dev_golden_save), { onSave(build()) }, icon = Icons.Rounded.Save)
            QuietButton(
                stringResource(R.string.dev_golden_delete),
                { onRemove(screen.sha256) },
                icon = Icons.Rounded.Delete,
                fillWidth = true,
                style = QuietStyle.Text,
            )
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
