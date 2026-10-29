package app.ridemate.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ridemate.core.model.LatLng
import app.ridemate.trip.CurrentLocationSource
import app.ridemate.trip.LocationFix
import app.ridemate.trip.PlaceGeocoder
import app.ridemate.trip.TripPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which end of the trip is being pinned. */
enum class PinTarget { PICKUP, DROP }

/** What the pin picker needs, behind interfaces so tests use fakes. */
class PinPickerDeps(
    val geocoder: PlaceGeocoder,
    val location: CurrentLocationSource,
    /** The label for a pin whose address is unknown, e.g. "Pinned location (12.97160, 77.59460)". */
    val pinnedLabel: (LatLng) -> String,
    val debounceMs: Long = DEBOUNCE_MS,
) {
    companion object {
        /** The map must rest this long before the address under the pin is looked up. */
        const val DEBOUNCE_MS: Long = 600
    }
}

enum class PinNotice { LOCATION_DENIED, LOCATION_UNAVAILABLE }

data class PinPickerState(
    val target: PinTarget,
    /** The exact point under the centre pin (what Confirm returns); null until the camera has a position. */
    val center: LatLng?,
    /** The address under the pin, when the Geocoder found one. */
    val address: String? = null,
    /** A lookup is waiting (debounce) or running. */
    val resolving: Boolean = false,
    /** The last lookup finished without an address: Confirm still works, with the coordinates as the label. */
    val noAddress: Boolean = false,
    val locating: Boolean = false,
    val notice: PinNotice? = null,
) {
    val canConfirm: Boolean get() = center != null
}

sealed interface PinEvent {
    /** Move the camera to [point] (my location). */
    data class MoveTo(val point: LatLng) : PinEvent

    data object RequestLocationPermission : PinEvent
}

/**
 * The pin picker's logic (architecture §4.2 TripInput): the map moves under a fixed centre pin; once the camera
 * rests for [PinPickerDeps.debounceMs] the point is reverse-geocoded through the existing [PlaceGeocoder]. A newer
 * camera position cancels any older lookup, so a late answer never labels the wrong point. [confirm] returns the
 * exact pin coordinates, with the address as the label (or "Pinned location (lat, lng)" when none is known).
 */
class PinPickerViewModel(
    private val deps: PinPickerDeps,
    target: PinTarget,
    start: LatLng?,
    startLabel: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(PinPickerState(target = target, center = start, address = startLabel))
    val state: StateFlow<PinPickerState> = _state.asStateFlow()

    private val _events = Channel<PinEvent>(Channel.BUFFERED)
    val events: Flow<PinEvent> = _events.receiveAsFlow()

    private var lookupJob: Job? = null
    private var locateJob: Job? = null

    init {
        if (start == null && deps.location.hasPermission()) myLocation()
    }

    /** The user started dragging the map: the old address no longer applies. */
    fun onCameraMoveStarted() {
        lookupJob?.cancel()
        _state.update { it.copy(resolving = true, noAddress = false) }
    }

    /** The camera came to rest at [center]: remember the exact point and look its address up after the debounce. */
    fun onCameraIdle(center: LatLng) {
        val previous = _state.value
        if (previous.center == center && !previous.resolving && (previous.address != null || previous.noAddress)) return
        lookupJob?.cancel()
        _state.update { it.copy(center = center, address = null, resolving = true, noAddress = false) }
        lookupJob = viewModelScope.launch {
            delay(deps.debounceMs)
            val address = try {
                deps.geocoder.reverse(center)?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { s ->
                if (s.center != center) s else s.copy(address = address, resolving = false, noAddress = address == null)
            }
        }
    }

    /** "My location": centre on the phone's position (while-in-use permission only; asks if not granted). */
    fun myLocation() {
        if (!deps.location.hasPermission()) {
            _events.trySend(PinEvent.RequestLocationPermission)
            return
        }
        locateJob?.cancel()
        _state.update { it.copy(locating = true, notice = null) }
        locateJob = viewModelScope.launch {
            val fix = deps.location.current()
            _state.update { it.copy(locating = false) }
            when (fix) {
                is LocationFix.Found -> {
                    _events.trySend(PinEvent.MoveTo(fix.point))
                    onCameraIdle(fix.point)
                }
                LocationFix.PermissionDenied -> _state.update { it.copy(notice = PinNotice.LOCATION_DENIED) }
                LocationFix.Unavailable -> _state.update { it.copy(notice = PinNotice.LOCATION_UNAVAILABLE) }
            }
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) myLocation() else _state.update { it.copy(notice = PinNotice.LOCATION_DENIED) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** The place under the pin: exact coordinates, the address if known, else the coordinates as the label. */
    fun confirm(): TripPlace? {
        val s = _state.value
        val center = s.center ?: return null
        val label = s.address?.takeIf { it.isNotBlank() } ?: deps.pinnedLabel(center)
        return TripPlace(label, center)
    }
}
