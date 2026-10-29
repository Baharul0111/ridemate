package app.ridemate.trip

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ridemate.R
import app.ridemate.RideMateApp
import app.ridemate.precheck.FixAction
import app.ridemate.precheck.FixIntents
import app.ridemate.precheck.PrecheckCard
import app.ridemate.map.MapTags
import app.ridemate.map.PinPickerScreen
import app.ridemate.map.PinPickerViewModel
import app.ridemate.map.PinTarget
import app.ridemate.map.RoutePreviewMap
import app.ridemate.map.coordsText
import app.ridemate.ui.components.InlineBanner
import app.ridemate.ui.components.RmTone
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.key
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Test tags / stable ids for UI tests. */
object TripTags {
    const val DROP_FIELD = "trip_drop_field"
    const val PICKUP_FIELD = "trip_pickup_field"
    const val COMPARE = "trip_compare"
    const val CONFIRM_YES = "trip_confirm_yes"
}

/**
 * Enter a trip once (goal 1–2): pickup (current location, else typed), drop (suggestions, recent places or voice,
 * always confirmed), the installed apps, vehicle types and optional ONDC, then Compare (pre-session check first).
 * Every control has a spoken label, touch targets are at least 48 dp, and state is never shown by colour alone.
 */
@Composable
fun TripInputScreen(
    viewModel: TripViewModel,
    onStartComparison: (TripPlan) -> Unit,
    onTryDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onLocationPermissionResult(result.values.any { it })
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoice() else viewModel.onMicPermissionDenied()
    }
    val requestLocation = {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    var askedLocation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.pickup) {
        if (state.pickup == PickupState.NeedsPermission && !askedLocation) {
            askedLocation = true
            requestLocation()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TripEvent.StartComparison -> onStartComparison(event.plan)
                TripEvent.TryDemo -> onTryDemo()
                TripEvent.RequestLocationPermission -> requestLocation()
                is TripEvent.ShowOnMap -> if (!startSafely(context, MapIntents.viewOnMap(event.place))) viewModel.onNoMapsApp()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    val onFix: (FixAction) -> Unit = { action ->
        val intent = if (action == FixAction.REQUEST_LOCATION) null else FixIntents.forAction(context, action)
        when {
            intent == null -> viewModel.onFix(action)
            startSafely(context, intent) -> Unit
            action == FixAction.OPEN_NOTIFICATION_ACCESS && startSafely(context, FixIntents.notificationAccessList()) -> Unit
            else -> Toast.makeText(context, R.string.precheck_settings_missing, Toast.LENGTH_LONG).show()
        }
    }
    val startVoice = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    state.pinRequest?.let { request ->
        PinPickerHost(request, state, viewModel, modifier)
        return
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.trip_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            PickupSection(state, viewModel, requestLocation)
            HorizontalDivider()
            DropSection(state, viewModel, startVoice)
            state.notice?.let { NoticeRow(it, viewModel) }
            HorizontalDivider()
            AppsSection(state, viewModel)
            VehiclesSection(state, viewModel)
            CompareSection(state, viewModel, onFix)
        }
    }
}

/** The full-screen pin picker over the trip screen; a fresh picker for every opening ([PinRequest.nonce]). */
@Composable
private fun PinPickerHost(request: PinRequest, state: TripUiState, vm: TripViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val pinVm: PinPickerViewModel = viewModel(
        key = "pin-${request.target.name}-${request.nonce}",
        factory = viewModelFactory {
            initializer {
                PinPickerViewModel(
                    deps = vm.pinPickerDeps { point -> context.getString(R.string.map_pinned_label, coordsText(point)) },
                    target = request.target,
                    start = request.start,
                    startLabel = request.startLabel,
                )
            }
        },
    )
    PinPickerScreen(
        viewModel = pinVm,
        config = state.map,
        onConfirm = { place -> vm.onPinConfirmed(request.target, place) },
        onCancel = vm::closePinPicker,
        onUnavailable = vm::onMapUnavailable,
        modifier = modifier,
    )
}

@Composable
private fun SetOnMapButton(target: PinTarget, vm: TripViewModel) {
    val description = stringResource(if (target == PinTarget.PICKUP) R.string.map_set_pickup_description else R.string.map_set_drop_description)
    TextButton(
        onClick = { vm.openPinPicker(target) },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(if (target == PinTarget.PICKUP) MapTags.SET_PICKUP else MapTags.SET_DROP)
            .semantics { contentDescription = description },
    ) {
        Icon(Icons.Filled.PushPin, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.map_set_on_map))
    }
}

@Composable
private fun MapUnavailableBanner() {
    Box(Modifier.testTag(MapTags.UNAVAILABLE)) {
        InlineBanner(stringResource(R.string.map_unavailable), tone = RmTone.WARN)
    }
}

/** [TripInputScreen] with its view model from the process graph: what navigation hosts. */
@Composable
fun TripRoute(onStartComparison: (TripPlan) -> Unit, onTryDemo: () -> Unit, modifier: Modifier = Modifier) {
    val graph = RideMateApp.graph(LocalContext.current)
    val viewModel: TripViewModel = viewModel(factory = TripViewModel.factory(graph))
    TripInputScreen(viewModel, onStartComparison, onTryDemo, modifier)
}

private fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

// ---------------------------------------------------------------------------------------------------------------
// Pickup
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun PickupSection(state: TripUiState, vm: TripViewModel, requestLocation: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(R.string.trip_pickup_heading))
        when (val pickup = state.pickup) {
            PickupState.Locating -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.trip_pickup_locating), modifier = Modifier.weight(1f))
                TextButton(onClick = vm::changePickup) { Text(stringResource(R.string.trip_pickup_type)) }
            }
            PickupState.NeedsPermission -> {
                Text(stringResource(R.string.trip_pickup_permission))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = requestLocation) { Text(stringResource(R.string.trip_pickup_allow)) }
                    TextButton(onClick = vm::changePickup) { Text(stringResource(R.string.trip_pickup_type)) }
                }
            }
            is PickupState.Current -> {
                ChosenPlaceRow(
                icon = { Icon(Icons.Filled.MyLocation, contentDescription = null) },
                text = if (pickup.place.label == stringResource(R.string.trip_current_location)) {
                    pickup.place.label
                } else {
                    stringResource(R.string.trip_pickup_current_label, pickup.place.label)
                },
                description = stringResource(R.string.trip_pickup_summary_description, pickup.place.label),
                actionLabel = stringResource(R.string.trip_pickup_change),
                actionDescription = stringResource(R.string.trip_pickup_change_description),
                onAction = vm::changePickup,
            )
                if (state.mapAvailable) SetOnMapButton(PinTarget.PICKUP, vm)
            }
            is PickupState.Typed -> {
                ChosenPlaceRow(
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    text = pickup.place.label,
                    description = stringResource(R.string.trip_pickup_summary_description, pickup.place.label),
                    actionLabel = stringResource(R.string.trip_pickup_change),
                    actionDescription = stringResource(R.string.trip_pickup_change_description),
                    onAction = vm::changePickup,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::useCurrentLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.trip_pickup_use_location))
                    }
                    if (state.mapAvailable) SetOnMapButton(PinTarget.PICKUP, vm)
                }
            }
            is PickupState.Typing -> {
                when (pickup.reason) {
                    TypingReason.LOCATION_DENIED -> Text(stringResource(R.string.trip_pickup_denied))
                    TypingReason.LOCATION_UNAVAILABLE -> Text(stringResource(R.string.trip_pickup_unavailable))
                    TypingReason.USER_CHANGED -> Unit
                }
                OutlinedTextField(
                    value = state.pickupQuery,
                    onValueChange = vm::onPickupQuery,
                    label = { Text(stringResource(R.string.trip_pickup_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TripTags.PICKUP_FIELD),
                )
                SearchResults(
                    search = state.pickupSearch,
                    onPick = vm::selectPickup,
                    onRetry = vm::retryPickupSearch,
                    onUseTyped = vm::usePickupAsTyped,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::useCurrentLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.trip_pickup_use_location))
                    }
                    if (state.mapAvailable) SetOnMapButton(PinTarget.PICKUP, vm)
                }
            }
        }
    }
}

@Composable
private fun ChosenPlaceRow(
    icon: @Composable () -> Unit,
    text: String,
    description: String,
    actionLabel: String,
    actionDescription: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = description },
        )
        TextButton(
            onClick = onAction,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = actionDescription },
        ) { Text(actionLabel) }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Drop
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun DropSection(state: TripUiState, vm: TripViewModel, startVoice: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(R.string.trip_drop_heading))
        val drop = state.drop
        val pending = state.pending
        when {
            drop != null -> {
                ChosenPlaceRow(
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    text = drop.label,
                    description = stringResource(R.string.trip_drop_summary_description, drop.label),
                    actionLabel = stringResource(R.string.trip_drop_change),
                    actionDescription = stringResource(R.string.trip_drop_change_description),
                    onAction = vm::changeDrop,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ViewOnMapButton(drop, vm)
                    if (state.mapAvailable) SetOnMapButton(PinTarget.DROP, vm)
                }
                if (!drop.hasPoint) Text(stringResource(R.string.trip_drop_no_pin), style = MaterialTheme.typography.bodySmall)
                val from = state.pickupPlace
                val a = from?.point
                val b = drop.point
                if (a != null && b != null) {
                    if (state.mapAvailable) {
                        key(a, b) {
                            RoutePreviewMap(
                                config = state.map,
                                pickup = a,
                                drop = b,
                                pickupLabel = from.label,
                                dropLabel = drop.label,
                                onAdjust = { vm.openPinPicker(PinTarget.DROP) },
                            )
                        }
                    } else {
                        MapUnavailableBanner()
                    }
                }
            }
            pending != null -> ConfirmCard(pending, vm)
            else -> {
                if (state.mapFailed) MapUnavailableBanner()
                DropSearchArea(state, vm, startVoice)
                if (state.mapAvailable) SetOnMapButton(PinTarget.DROP, vm)
            }
        }
    }
}

@Composable
private fun ViewOnMapButton(place: TripPlace, vm: TripViewModel) {
    val description = stringResource(R.string.trip_view_on_map_description, place.label)
    OutlinedButton(
        onClick = { vm.viewOnMap(place) },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(Icons.Filled.Map, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.trip_view_on_map))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmCard(pending: PendingDrop, vm: TripViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.trip_confirm_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(pending.place.label, style = MaterialTheme.typography.bodyLarge)
            if (!pending.place.hasPoint) Text(stringResource(R.string.trip_confirm_typed_note), style = MaterialTheme.typography.bodySmall)
            val yesDescription = stringResource(R.string.trip_confirm_yes_description, pending.place.label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::confirmDrop,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(TripTags.CONFIRM_YES)
                        .semantics { contentDescription = yesDescription },
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.trip_confirm_yes))
                }
                OutlinedButton(onClick = vm::rejectPending, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.trip_confirm_other))
                }
                ViewOnMapButton(pending.place, vm)
            }
        }
    }
}

@Composable
private fun DropSearchArea(state: TripUiState, vm: TripViewModel, startVoice: () -> Unit) {
    val voice = state.voice
    val listening = voice.phase == VoicePhase.LISTENING
    OutlinedTextField(
        value = state.dropQuery,
        onValueChange = vm::onDropQuery,
        label = { Text(stringResource(R.string.trip_drop_field)) },
        placeholder = { Text(stringResource(R.string.trip_drop_hint)) },
        singleLine = true,
        trailingIcon = if (voice.available) {
            {
                IconButton(onClick = { if (listening) vm.cancelVoice() else startVoice() }) {
                    Icon(
                        if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = stringResource(if (listening) R.string.trip_voice_stop else R.string.trip_voice_start),
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().testTag(TripTags.DROP_FIELD),
    )
    if (voice.available && voice.languages.size > 1) VoiceLanguagePicker(voice, vm)
    when (voice.phase) {
        VoicePhase.LISTENING -> StatusLine(
            stringResource(R.string.trip_voice_listening, stringResource(voice.language.labelRes)) +
                (voice.heard?.let { "\n" + stringResource(R.string.trip_voice_heard, it) } ?: ""),
        )
        VoicePhase.MATCHING -> StatusLine(stringResource(R.string.trip_voice_matching, voice.heard.orEmpty()))
        VoicePhase.IDLE -> Unit
    }
    when {
        state.choices.isNotEmpty() -> {
            Text(stringResource(R.string.trip_choices_heading), style = MaterialTheme.typography.labelLarge)
            state.choices.forEach { SuggestionRow(it.label, Icons.Filled.Place) { vm.selectSuggestion(it) } }
        }
        state.notice == TripNotice.LookupFailed -> UseTypedButton(state.dropQuery, vm::useDropAsTyped)
        state.dropSearch == PlaceSearchState.Idle && state.dropQuery.isBlank() && state.recent.isNotEmpty() -> {
            Text(stringResource(R.string.trip_recent_heading), style = MaterialTheme.typography.labelLarge)
            state.recent.forEach { place -> SuggestionRow(place.label, Icons.Filled.History) { vm.selectRecent(place) } }
        }
        else -> SearchResults(
            search = state.dropSearch,
            onPick = vm::selectSuggestion,
            onRetry = vm::retryDropSearch,
            onUseTyped = vm::useDropAsTyped,
        )
    }
}

@Composable
private fun VoiceLanguagePicker(voice: VoiceUi, vm: TripViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = stringResource(voice.language.labelRes)
    val description = stringResource(R.string.trip_voice_language_description, current)
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = description },
        ) { Text(stringResource(R.string.trip_voice_language, current)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            voice.languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(stringResource(language.labelRes)) },
                    leadingIcon = if (language == voice.language) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        vm.selectVoiceLanguage(language)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    search: PlaceSearchState,
    onPick: (PlaceSuggestion) -> Unit,
    onRetry: () -> Unit,
    onUseTyped: () -> Unit,
) {
    when (search) {
        PlaceSearchState.Idle -> Unit
        is PlaceSearchState.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            StatusLine(stringResource(R.string.trip_searching))
        }
        is PlaceSearchState.Results -> search.suggestions.forEach { suggestion ->
            SuggestionRow(suggestion.label, Icons.Filled.Place) { onPick(suggestion) }
        }
        is PlaceSearchState.NoResults -> {
            StatusLine(stringResource(R.string.trip_no_results, search.query))
            UseTypedButton(search.query, onUseTyped)
        }
        is PlaceSearchState.Unavailable -> {
            StatusLine(stringResource(R.string.trip_geocoder_unavailable))
            UseTypedButton(search.query, onUseTyped)
        }
        is PlaceSearchState.Failed -> {
            StatusLine(stringResource(R.string.trip_search_failed))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.trip_retry)) }
                UseTypedButton(search.query, onUseTyped)
            }
        }
    }
}

@Composable
private fun UseTypedButton(text: String, onClick: () -> Unit) {
    if (text.isBlank()) return
    val description = stringResource(R.string.trip_use_as_typed_description, text)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    ) { Text(stringResource(R.string.trip_use_as_typed, text)) }
}

@Composable
private fun SuggestionRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val description = stringResource(R.string.trip_suggestion_description, label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun NoticeRow(notice: TripNotice, vm: TripViewModel) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Text(
                TripMessages.noticeText(context, notice),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = vm::dismissNotice) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.trip_notice_dismiss))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Apps, vehicles, ONDC
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun AppsSection(state: TripUiState, vm: TripViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(stringResource(R.string.trip_apps_heading))
        state.apps.forEach { choice ->
            val name = choice.provider.displayName
            val label = if (choice.installed) name else stringResource(R.string.trip_app_not_installed, name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = choice.selected,
                        enabled = choice.installed,
                        role = Role.Checkbox,
                        onValueChange = { vm.toggleApp(choice.provider) },
                    ),
            ) {
                Checkbox(checked = choice.selected, onCheckedChange = null, enabled = choice.installed)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (state.apps.isNotEmpty() && !state.anyAppInstalled) {
            Text(stringResource(R.string.trip_no_apps))
            Button(onClick = vm::tryDemo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.trip_try_demo)) }
        }
        if (state.ondcAvailable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = state.includeOndc,
                        enabled = state.ondcSelectable,
                        role = Role.Switch,
                        onValueChange = vm::setIncludeOndc,
                    ),
            ) {
                Text(stringResource(R.string.trip_ondc), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = state.includeOndc, onCheckedChange = null, enabled = state.ondcSelectable)
            }
            if (!state.ondcSelectable && state.drop != null) {
                Text(stringResource(R.string.trip_ondc_needs_pins), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehiclesSection(state: TripUiState, vm: TripViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(stringResource(R.string.trip_vehicles_heading))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleChoice.entries.forEach { vehicle ->
                val selected = vehicle in state.vehicles
                FilterChip(
                    selected = selected,
                    onClick = { vm.toggleVehicle(vehicle) },
                    label = { Text(stringResource(TripMessages.vehicleLabel(vehicle))) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Compare
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun CompareSection(state: TripUiState, vm: TripViewModel, onFix: (FixAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.report?.let { report ->
            report.issues.forEach { PrecheckCard(it, onFix) }
            if (report.canProceed) {
                OutlinedButton(onClick = vm::continueAnyway, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.trip_continue_anyway))
                }
            } else {
                StatusLine(stringResource(R.string.trip_fix_first))
            }
        }
        if (state.checking) StatusLine(stringResource(R.string.trip_checking))
        TripMessages.missingHint(state)?.let { StatusLine(stringResource(it)) }
        Button(
            onClick = vm::compare,
            enabled = state.canCompare,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(TripTags.COMPARE),
        ) {
            Text(stringResource(if (state.report?.canProceed == false) R.string.trip_check_again else R.string.trip_compare))
        }
        TextButton(onClick = vm::tryDemo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.trip_try_demo)) }
    }
}
