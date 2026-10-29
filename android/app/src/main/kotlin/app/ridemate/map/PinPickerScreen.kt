package app.ridemate.map

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ridemate.R
import app.ridemate.core.model.LatLng
import app.ridemate.trip.TripPlace
import app.ridemate.ui.components.InlineBanner
import app.ridemate.ui.components.RmTone
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.geometry.LatLng as MlLatLng

/** Test tags for UI tests. */
object MapTags {
    const val PIN_PICKER = "map_pin_picker"
    const val CONFIRM = "map_pin_confirm"
    const val MY_LOCATION = "map_my_location"
    const val SET_PICKUP = "map_set_pickup"
    const val SET_DROP = "map_set_drop"
    const val ROUTE_PREVIEW = "map_route_preview"
    const val UNAVAILABLE = "map_unavailable"
}

/** Street-level zoom for a known point; a whole-India view when nothing is known yet. */
internal const val PIN_ZOOM = 17.0
internal const val OVERVIEW_ZOOM = 4.2
internal val INDIA_CENTER = LatLng(22.5, 79.0)

internal fun LatLng.toMl(): MlLatLng = MlLatLng(lat, lng)

/**
 * Full-screen pin picker for the pickup or the drop (like other ride apps): the map moves under a fixed centre
 * pin, the address under it is looked up after the map rests, "My location" recentres (while-in-use permission),
 * and "Confirm pickup / drop" returns a [TripPlace] with the exact pin coordinates. If the map cannot load (switched
 * off, init failure, style not loaded within 8 s) it shows "Map unavailable · type the address" and the trip goes on.
 */
@Composable
fun PinPickerScreen(
    viewModel: PinPickerViewModel,
    config: MapConfig,
    onConfirm: (TripPlace) -> Unit,
    onCancel: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var pendingMove by remember { mutableStateOf<LatLng?>(null) }
    var unavailable by remember { mutableStateOf(!config.enabled || MapLibreInit.isBroken) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onLocationPermissionResult(result.values.any { it })
    }

    BackHandler(onBack = onCancel)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PinEvent.MoveTo -> {
                    val m = map
                    if (m != null) m.animateCamera(CameraUpdateFactory.newLatLngZoom(event.point.toMl(), PIN_ZOOM)) else pendingMove = event.point
                }
                PinEvent.RequestLocationPermission ->
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
    val pickup = state.target == PinTarget.PICKUP
    Surface(modifier = modifier.fillMaxSize().testTag(MapTags.PIN_PICKER)) {
        Box(Modifier.fillMaxSize()) {
            if (unavailable) {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PickerTopBar(pickup, onCancel)
                    Box(Modifier.testTag(MapTags.UNAVAILABLE)) {
                        InlineBanner(
                            text = stringResource(R.string.map_unavailable),
                            tone = RmTone.WARN,
                            actionLabel = stringResource(R.string.map_type_address),
                            onAction = onUnavailable,
                        )
                    }
                }
            } else {
                val mapDescription = stringResource(R.string.map_view_description)
                MapLibreView(
                    styleUrl = config.styleUrl,
                    configure = { m ->
                        val start = state.center
                        m.cameraPosition = CameraPosition.Builder()
                            .target((start ?: INDIA_CENTER).toMl())
                            .zoom(if (start != null) PIN_ZOOM else OVERVIEW_ZOOM)
                            .build()
                        m.uiSettings.isRotateGesturesEnabled = false
                        m.uiSettings.isTiltGesturesEnabled = false
                        m.uiSettings.isAttributionEnabled = true
                        m.uiSettings.isLogoEnabled = true
                        m.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) viewModel.onCameraMoveStarted()
                        }
                        m.addOnCameraIdleListener {
                            m.cameraPosition.target?.let { viewModel.onCameraIdle(LatLng(it.latitude, it.longitude)) }
                        }
                    },
                    onReady = { m, _ ->
                        map = m
                        pendingMove?.let { m.moveCamera(CameraUpdateFactory.newLatLngZoom(it.toMl(), PIN_ZOOM)) }
                        pendingMove = null
                    },
                    onUnavailable = { unavailable = true },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
                )
                // The fixed centre pin: its tip sits on the exact centre of the map.
                val pinDescription = stringResource(R.string.map_pin_description)
                Icon(
                    Icons.Filled.Place,
                    contentDescription = pinDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center).size(48.dp).offset(y = (-24).dp),
                )
                Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
                        Box(Modifier.padding(horizontal = 4.dp)) { PickerTopBar(pickup, onCancel) }
                    }
                }
                FilledIconButton(
                    onClick = viewModel::myLocation,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 196.dp)
                        .size(56.dp)
                        .testTag(MapTags.MY_LOCATION),
                ) {
                    if (state.locating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.map_my_location))
                    }
                }
                BottomSheet(state, viewModel, pickup, onConfirm, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun PickerTopBar(pickup: Boolean, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = onCancel) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.map_back))
        }
        Text(
            stringResource(if (pickup) R.string.map_title_pickup else R.string.map_title_drop),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(end = 12.dp).semantics { heading() },
        )
    }
}

@Composable
private fun BottomSheet(
    state: PinPickerState,
    viewModel: PinPickerViewModel,
    pickup: Boolean,
    onConfirm: (TripPlace) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.map_hint), style = MaterialTheme.typography.bodySmall)
            val addressText = when {
                state.resolving -> stringResource(R.string.map_resolving)
                state.address != null -> state.address
                state.noAddress -> stringResource(R.string.map_no_address)
                else -> stringResource(R.string.map_resolving)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.resolving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    addressText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.notice?.let { notice ->
                InlineBanner(
                    text = stringResource(
                        if (notice == PinNotice.LOCATION_DENIED) R.string.map_location_denied else R.string.map_location_unavailable,
                    ),
                    tone = RmTone.NEUTRAL,
                    onDismiss = viewModel::dismissNotice,
                )
            }
            val label = state.address ?: state.center?.let { coordsText(it) }.orEmpty()
            val confirmDescription = stringResource(
                if (pickup) R.string.map_confirm_pickup_description else R.string.map_confirm_drop_description,
                label,
            )
            Button(
                onClick = { viewModel.confirm()?.let(onConfirm) },
                enabled = state.canConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(MapTags.CONFIRM)
                    .semantics { contentDescription = confirmDescription },
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (pickup) R.string.map_confirm_pickup else R.string.map_confirm_drop))
            }
        }
    }
}

/** "12.97160, 77.59460": five decimals is about a metre, enough to find the exact spot. */
fun coordsText(point: LatLng): String = String.format(java.util.Locale.ROOT, "%.5f, %.5f", point.lat, point.lng)
