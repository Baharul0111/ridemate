package app.ridemate.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ridemate.AppGraph
import app.ridemate.R
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.launch.InstalledApps
import app.ridemate.map.MapConfig
import app.ridemate.map.PinTarget
import app.ridemate.net.ApiResult
import app.ridemate.precheck.FixAction
import app.ridemate.precheck.PreSessionCheck
import app.ridemate.precheck.PrecheckReport
import app.ridemate.precheck.PrecheckRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Everything the trip screen needs, injected so tests use fakes. */
class TripDeps(
    val geocoder: PlaceGeocoder,
    val location: CurrentLocationSource,
    val speech: SpeechInput,
    val placePicker: VoicePlacePicker,
    val recentPlaces: RecentPlacesStore,
    val installedApps: InstalledApps,
    /** Remote Config `ondc_enabled`. */
    val ondcEnabled: StateFlow<Boolean>,
    val precheck: PreSessionCheck,
    /** The pickup label while the address of the current position is unknown ("Current location"). */
    val currentLocationLabel: String,
    val defaultVoiceLanguage: VoiceLanguage,
    /** Remote Config `map_enabled` + `map_style_url`; off by default so a fake-built screen shows no map. */
    val mapConfig: StateFlow<MapConfig> = MutableStateFlow(MapConfig.DISABLED),
    val io: CoroutineDispatcher = Dispatchers.IO,
    val debounceMs: Long = PlaceSearchController.DEBOUNCE_MS,
    val voiceTimeoutMs: Long = VOICE_TIMEOUT_MS,
) {
    companion object {
        /** No speech for this long ends listening with "didn't catch that". */
        const val VOICE_TIMEOUT_MS: Long = 15_000

        fun from(graph: AppGraph): TripDeps {
            val context = graph.context
            val geocoder = AndroidPlaceGeocoder(context)
            return TripDeps(
                geocoder = geocoder,
                location = FusedCurrentLocationSource(context),
                speech = AndroidSpeechInput(context),
                placePicker = VoicePlacePicker(geocoder, graph.apiClient),
                recentPlaces = graph.recentPlaces,
                installedApps = graph.installedApps,
                ondcEnabled = graph.configRepository.ondcEnabled,
                precheck = graph.preSessionCheck,
                currentLocationLabel = context.getString(R.string.trip_current_location),
                defaultVoiceLanguage = VoiceLanguage.defaultFor(Locale.getDefault()),
                mapConfig = graph.configRepository.mapConfig,
            )
        }
    }
}

/** The pickup (goal 1: defaults to where you are). */
sealed interface PickupState {
    data object Locating : PickupState

    /** Location permission not decided yet: the screen asks for while-in-use location. */
    data object NeedsPermission : PickupState

    data class Current(val place: TripPlace) : PickupState

    data class Typed(val place: TripPlace) : PickupState

    /** The user types the pickup. */
    data class Typing(val reason: TypingReason) : PickupState
}

enum class TypingReason { LOCATION_DENIED, LOCATION_UNAVAILABLE, USER_CHANGED }

enum class DropSource { SEARCH, RECENT, VOICE, TYPED, MAP }

/**
 * The pin picker is open for [target], starting at [start] (the place's own pin, else the pickup's). [nonce] is new
 * for every opening, so each opening gets a fresh picker.
 */
data class PinRequest(val target: PinTarget, val start: LatLng?, val startLabel: String?, val nonce: Int)

/** An address waiting for the user's confirmation. Nothing happens until it is confirmed. */
data class PendingDrop(val place: TripPlace, val source: DropSource)

enum class VoicePhase { IDLE, LISTENING, MATCHING }

data class VoiceUi(
    val available: Boolean = false,
    val languages: List<VoiceLanguage> = VoiceLanguage.entries,
    val language: VoiceLanguage = VoiceLanguage.ENGLISH,
    val phase: VoicePhase = VoicePhase.IDLE,
    /** What the recognizer heard so far (shown back to the user). */
    val heard: String? = null,
)

/** A ride app on the trip screen: only installed apps can be selected (goal 2). */
data class AppChoice(val provider: Provider, val installed: Boolean, val selected: Boolean)

/** One-line messages on the trip screen; each says what to do next. */
sealed interface TripNotice {
    data class Voice(val error: SpeechError, val language: VoiceLanguage) : TripNotice
    data class NothingFoundFor(val text: String) : TripNotice
    data class PickFromList(val reason: ChooseReason, val failure: ApiResult<*>?) : TripNotice
    data object LookupFailed : TripNotice
    data object NoMapsApp : TripNotice
    data object MicDenied : TripNotice
}

/** One-shot instructions for the screen. */
sealed interface TripEvent {
    /** The pre-session check passed (or the user continued past warnings): start the comparison. */
    data class StartComparison(val plan: TripPlan) : TripEvent
    data object TryDemo : TripEvent
    data object RequestLocationPermission : TripEvent
    data class ShowOnMap(val place: TripPlace) : TripEvent
}

data class TripUiState(
    val pickup: PickupState = PickupState.Locating,
    val pickupQuery: String = "",
    val pickupSearch: PlaceSearchState = PlaceSearchState.Idle,
    val dropQuery: String = "",
    val dropSearch: PlaceSearchState = PlaceSearchState.Idle,
    /** Candidates from voice when the user has to pick one. */
    val choices: List<PlaceSuggestion> = emptyList(),
    val pending: PendingDrop? = null,
    val drop: TripPlace? = null,
    val recent: List<TripPlace> = emptyList(),
    val voice: VoiceUi = VoiceUi(),
    val apps: List<AppChoice> = emptyList(),
    val vehicles: Set<VehicleChoice> = VehicleChoice.entries.toSet(),
    val ondcAvailable: Boolean = false,
    val includeOndc: Boolean = false,
    val geocoderAvailable: Boolean = true,
    val checking: Boolean = false,
    val report: PrecheckReport? = null,
    val notice: TripNotice? = null,
    /** The in-app map's settings (Remote Config). */
    val map: MapConfig = MapConfig.DISABLED,
    /** The map failed to load this session: entry points stay hidden and "Map unavailable" shows instead. */
    val mapFailed: Boolean = false,
    val pinRequest: PinRequest? = null,
) {
    /** "Set on map" is offered: the map is switched on and has not failed. */
    val mapAvailable: Boolean get() = map.enabled && !mapFailed

    val pickupPlace: TripPlace?
        get() = when (val p = pickup) {
            is PickupState.Current -> p.place
            is PickupState.Typed -> p.place
            else -> null
        }

    val selectedApps: List<Provider> get() = apps.filter { it.installed && it.selected }.map { it.provider }

    val anyAppInstalled: Boolean get() = apps.any { it.installed }

    /** ONDC needs Remote Config's switch and coordinates for both ends. */
    val ondcSelectable: Boolean get() = ondcAvailable && pickupPlace?.hasPoint == true && drop?.hasPoint == true

    val canCompare: Boolean
        get() = !checking && pickupPlace != null && drop != null && pending == null && vehicles.isNotEmpty() &&
            (selectedApps.isNotEmpty() || (includeOndc && ondcSelectable))
}

/**
 * The trip screen's logic (goal items 1–2, architecture §4.2 TripInput):
 * - pickup = the current position (while-in-use location), else typed with suggestions;
 * - drop = Geocoder suggestions debounced 400 ms, recent places, or voice → place-pick; every drop is shown for
 *   confirmation first ([pending]) and only a confirmed one counts; "View on map" opens the phone's maps app;
 * - installed apps only, bike/auto/cab, optional ONDC (Remote Config `ondc_enabled`);
 * - Compare runs the [PreSessionCheck]; blocking results show fix-it cards, warnings can be passed with
 *   "Continue anyway"; a clean check emits [TripEvent.StartComparison].
 */
class TripViewModel(private val deps: TripDeps) : ViewModel() {

    private val _state = MutableStateFlow(
        TripUiState(
            geocoderAvailable = deps.geocoder.isAvailable,
            voice = VoiceUi(available = deps.speech.isAvailable(), language = deps.defaultVoiceLanguage),
        ),
    )
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    private val _events = Channel<TripEvent>(Channel.BUFFERED)
    val events: Flow<TripEvent> = _events.receiveAsFlow()

    private val dropSearch = PlaceSearchController(viewModelScope, deps.geocoder, { pickupPoint() }, deps.debounceMs)
    private val pickupSearch = PlaceSearchController(viewModelScope, deps.geocoder, { null }, deps.debounceMs)

    private var locateJob: Job? = null
    private var voiceJob: Job? = null
    private var checkJob: Job? = null

    init {
        dropSearch.state.onEach { s -> _state.update { it.copy(dropSearch = s) } }.launchIn(viewModelScope)
        pickupSearch.state.onEach { s -> _state.update { it.copy(pickupSearch = s) } }.launchIn(viewModelScope)
        deps.mapConfig.onEach { config ->
            _state.update { it.copy(map = config, pinRequest = if (config.enabled) it.pinRequest else null) }
        }.launchIn(viewModelScope)
        deps.ondcEnabled.onEach { on ->
            _state.update { it.copy(ondcAvailable = on, includeOndc = it.includeOndc && on) }
        }.launchIn(viewModelScope)
        refreshApps()
        viewModelScope.launch {
            val recent = withContext(deps.io) { deps.recentPlaces.recent() }
            _state.update { it.copy(recent = recent) }
        }
        if (_state.value.voice.available) {
            viewModelScope.launch {
                val supported = deps.speech.supportedLanguages() ?: return@launch
                _state.update { s ->
                    val languages = VoiceLanguage.entries.filter { it in supported }
                    val language = if (s.voice.language in languages) s.voice.language else languages.firstOrNull() ?: s.voice.language
                    s.copy(voice = s.voice.copy(available = languages.isNotEmpty(), languages = languages, language = language))
                }
            }
        }
        if (deps.location.hasPermission()) locate() else _state.update { it.copy(pickup = PickupState.NeedsPermission) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pickup
    // ---------------------------------------------------------------------------------------------------------

    /** The screen's permission request finished. */
    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) locate() else _state.update { it.copy(pickup = PickupState.Typing(TypingReason.LOCATION_DENIED)) }
    }

    /** "Use my location": locate if allowed, else ask for permission. */
    fun useCurrentLocation() {
        if (deps.location.hasPermission()) locate() else _events.trySend(TripEvent.RequestLocationPermission)
    }

    fun changePickup() {
        locateJob?.cancel()
        pickupSearch.clear()
        _state.update { it.copy(pickup = PickupState.Typing(TypingReason.USER_CHANGED), pickupQuery = "") }
    }

    fun onPickupQuery(text: String) {
        _state.update { it.copy(pickupQuery = text) }
        pickupSearch.onQueryChange(text)
    }

    fun selectPickup(suggestion: PlaceSuggestion) {
        pickupSearch.clear()
        _state.update { it.copy(pickup = PickupState.Typed(suggestion.toTripPlace()), pickupQuery = "") }
    }

    /** Geocoder unavailable or no match: the typed text becomes the pickup (the app opens without a route). */
    fun usePickupAsTyped() {
        val text = _state.value.pickupQuery.trim().takeIf { it.isNotEmpty() } ?: return
        pickupSearch.clear()
        _state.update { it.copy(pickup = PickupState.Typed(TripPlace(text, null)), pickupQuery = "") }
    }

    fun retryPickupSearch() = pickupSearch.retry()

    private fun locate() {
        locateJob?.cancel()
        _state.update { it.copy(pickup = PickupState.Locating) }
        locateJob = viewModelScope.launch {
            when (val fix = deps.location.current()) {
                is LocationFix.Found -> {
                    _state.update { it.copy(pickup = PickupState.Current(TripPlace(deps.currentLocationLabel, fix.point))) }
                    val address = deps.geocoder.reverse(fix.point)?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
                    _state.update { s ->
                        val current = s.pickup as? PickupState.Current
                        if (current?.place?.point == fix.point) s.copy(pickup = PickupState.Current(TripPlace(address, fix.point))) else s
                    }
                }
                LocationFix.PermissionDenied -> _state.update { it.copy(pickup = PickupState.Typing(TypingReason.LOCATION_DENIED)) }
                LocationFix.Unavailable -> _state.update { it.copy(pickup = PickupState.Typing(TypingReason.LOCATION_UNAVAILABLE)) }
            }
        }
    }

    private fun pickupPoint(): LatLng? = _state.value.pickupPlace?.point

    // ---------------------------------------------------------------------------------------------------------
    // Drop
    // ---------------------------------------------------------------------------------------------------------

    fun onDropQuery(text: String) {
        stopVoice()
        _state.update { it.copy(dropQuery = text, choices = emptyList(), notice = null) }
        dropSearch.onQueryChange(text)
    }

    fun retryDropSearch() = dropSearch.retry()

    /** A suggestion (typed search or voice candidate) was tapped: ask for confirmation. */
    fun selectSuggestion(suggestion: PlaceSuggestion) {
        stopVoice()
        val source = if (suggestion in _state.value.choices) DropSource.VOICE else DropSource.SEARCH
        _state.update { it.copy(pending = PendingDrop(suggestion.toTripPlace(), source), notice = null) }
    }

    fun selectRecent(place: TripPlace) {
        stopVoice()
        _state.update { it.copy(pending = PendingDrop(place, DropSource.RECENT), notice = null) }
    }

    /** No Geocoder, no match or lookup failed: the typed text itself, confirmed like any other address. */
    fun useDropAsTyped() {
        val text = _state.value.dropQuery.trim().takeIf { it.isNotEmpty() } ?: return
        stopVoice()
        _state.update { it.copy(pending = PendingDrop(TripPlace(text, null), DropSource.TYPED), notice = null) }
    }

    /** The user confirmed the address text (goal 1): only now is it the drop. */
    fun confirmDrop() {
        val pending = _state.value.pending ?: return
        stopVoice()
        dropSearch.clear()
        _state.update {
            it.copy(
                drop = pending.place,
                pending = null,
                choices = emptyList(),
                dropQuery = "",
                notice = null,
                includeOndc = it.includeOndc && pending.place.hasPoint,
            )
        }
        viewModelScope.launch {
            val recent = withContext(deps.io) {
                deps.recentPlaces.add(pending.place)
                deps.recentPlaces.recent()
            }
            _state.update { it.copy(recent = recent) }
        }
    }

    /** "Pick another": back to the list, nothing chosen. */
    fun rejectPending() {
        _state.update { it.copy(pending = null) }
    }

    fun changeDrop() {
        stopVoice()
        _state.update { it.copy(drop = null, pending = null, report = null) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pin on the map
    // ---------------------------------------------------------------------------------------------------------

    private var pinNonce = 0

    /** "Set on map" on the pickup or drop row: open the full-screen pin picker at that place (or the pickup). */
    fun openPinPicker(target: PinTarget) {
        val s = _state.value
        if (!s.mapAvailable) return
        stopVoice()
        val own = if (target == PinTarget.PICKUP) s.pickupPlace else (s.drop ?: s.pending?.place)
        val start = own?.point ?: s.pickupPlace?.point
        val startLabel = own?.takeIf { it.point != null && it.label != deps.currentLocationLabel }?.label
        _state.update { it.copy(pinRequest = PinRequest(target, start, startLabel, ++pinNonce)) }
    }

    /** The pin picker's dependencies: the same Geocoder and while-in-use location as the trip screen. */
    fun pinPickerDeps(pinnedLabel: (LatLng) -> String): app.ridemate.map.PinPickerDeps =
        app.ridemate.map.PinPickerDeps(deps.geocoder, deps.location, pinnedLabel)

    fun closePinPicker() {
        _state.update { it.copy(pinRequest = null) }
    }

    /** The map could not load (init failure, style failure, 8 s timeout): back to typing, with a banner. */
    fun onMapUnavailable() {
        _state.update { it.copy(mapFailed = true, pinRequest = null) }
    }

    /**
     * "Confirm pickup / drop" on the pin picker: the exact pinned point becomes that end of the trip. Confirming on
     * the map is the user's confirmation, so a pinned drop needs no second confirmation card.
     */
    fun onPinConfirmed(target: PinTarget, place: TripPlace) {
        val point = place.point ?: return
        val pinned = TripPlace(place.label, point)
        when (target) {
            PinTarget.PICKUP -> {
                locateJob?.cancel()
                pickupSearch.clear()
                _state.update { it.copy(pickup = PickupState.Typed(pinned), pickupQuery = "", pinRequest = null, report = null) }
            }
            PinTarget.DROP -> {
                stopVoice()
                dropSearch.clear()
                _state.update {
                    it.copy(drop = pinned, pending = null, choices = emptyList(), dropQuery = "", notice = null, pinRequest = null, report = null)
                }
                viewModelScope.launch {
                    val recent = withContext(deps.io) {
                        deps.recentPlaces.add(pinned)
                        deps.recentPlaces.recent()
                    }
                    _state.update { it.copy(recent = recent) }
                }
            }
        }
    }

    fun viewOnMap(place: TripPlace) {
        _events.trySend(TripEvent.ShowOnMap(place))
    }

    fun onNoMapsApp() {
        _state.update { it.copy(notice = TripNotice.NoMapsApp) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Voice
    // ---------------------------------------------------------------------------------------------------------

    fun selectVoiceLanguage(language: VoiceLanguage) {
        _state.update { s -> if (language in s.voice.languages) s.copy(voice = s.voice.copy(language = language)) else s }
    }

    fun onMicPermissionDenied() {
        _state.update { it.copy(notice = TripNotice.MicDenied) }
    }

    /** Listens once (the screen has RECORD_AUDIO), then matches the transcript to Geocoder candidates. */
    fun startVoice() {
        val voice = _state.value.voice
        if (!voice.available) return
        voiceJob?.cancel()
        val language = voice.language
        _state.update {
            it.copy(voice = it.voice.copy(phase = VoicePhase.LISTENING, heard = null), notice = null, choices = emptyList(), pending = null)
        }
        voiceJob = viewModelScope.launch {
            var transcript: String? = null
            var error: SpeechError? = null
            withTimeoutOrNull(deps.voiceTimeoutMs) {
                deps.speech.listen(language).collect { event ->
                    when (event) {
                        is SpeechEvent.Partial -> _state.update { it.copy(voice = it.voice.copy(heard = event.text)) }
                        is SpeechEvent.Final -> transcript = event.text
                        is SpeechEvent.Failed -> error = event.error
                        SpeechEvent.Ready -> Unit
                    }
                }
            }
            val text = transcript?.trim()?.takeIf { it.isNotEmpty() }
            if (text == null) {
                voiceFailed(error ?: SpeechError.NO_MATCH, language)
                return@launch
            }
            dropSearch.clear()
            _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.MATCHING, heard = text), dropQuery = text) }
            val outcome = deps.placePicker.pick(text, language, pickupPoint())
            applyPick(text, outcome)
        }
    }

    fun cancelVoice() {
        voiceJob?.cancel()
        _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.IDLE)) }
    }

    /**
     * Any other choice on the drop (typing, a suggestion, a recent place, the typed text, confirming) supersedes a
     * voice lookup still running, so its late result can never replace the address the user is looking at.
     */
    private fun stopVoice() {
        if (voiceJob?.isActive != true) return
        voiceJob?.cancel()
        voiceJob = null
        _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.IDLE)) }
    }

    private fun applyPick(text: String, outcome: PlacePickOutcome) {
        _state.update { s ->
            val idle = s.voice.copy(phase = VoicePhase.IDLE)
            when (outcome) {
                is PlacePickOutcome.Picked ->
                    s.copy(voice = idle, choices = outcome.candidates, pending = PendingDrop(outcome.pick.toTripPlace(), DropSource.VOICE))
                is PlacePickOutcome.Choose ->
                    if (outcome.reason == ChooseReason.SINGLE_CANDIDATE) {
                        s.copy(voice = idle, choices = outcome.candidates, pending = PendingDrop(outcome.candidates.first().toTripPlace(), DropSource.VOICE))
                    } else {
                        s.copy(voice = idle, choices = outcome.candidates, notice = TripNotice.PickFromList(outcome.reason, outcome.failure))
                    }
                PlacePickOutcome.NoCandidates -> s.copy(voice = idle, notice = TripNotice.NothingFoundFor(text))
                PlacePickOutcome.GeocoderUnavailable ->
                    s.copy(voice = idle, pending = PendingDrop(TripPlace(text, null), DropSource.TYPED))
                PlacePickOutcome.LookupFailed -> s.copy(voice = idle, notice = TripNotice.LookupFailed)
            }
        }
    }

    private fun voiceFailed(error: SpeechError, language: VoiceLanguage) {
        _state.update { s ->
            var voice = s.voice.copy(phase = VoicePhase.IDLE, heard = null)
            if (error == SpeechError.LANGUAGE_UNSUPPORTED) {
                val remaining = voice.languages - language
                voice = voice.copy(
                    languages = remaining,
                    language = if (VoiceLanguage.ENGLISH in remaining) VoiceLanguage.ENGLISH else remaining.firstOrNull() ?: language,
                    available = remaining.isNotEmpty(),
                )
            }
            if (error == SpeechError.UNAVAILABLE) voice = voice.copy(available = false)
            val notice = if (error == SpeechError.NO_PERMISSION) TripNotice.MicDenied else TripNotice.Voice(error, language)
            s.copy(voice = voice, notice = notice)
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Apps, vehicles, ONDC
    // ---------------------------------------------------------------------------------------------------------

    fun toggleApp(provider: Provider) {
        _state.update { s ->
            s.copy(apps = s.apps.map { if (it.provider == provider && it.installed) it.copy(selected = !it.selected) else it }, report = null)
        }
    }

    fun toggleVehicle(vehicle: VehicleChoice) {
        _state.update { s -> s.copy(vehicles = if (vehicle in s.vehicles) s.vehicles - vehicle else s.vehicles + vehicle) }
    }

    fun setIncludeOndc(include: Boolean) {
        _state.update { s -> s.copy(includeOndc = include && s.ondcSelectable, report = null) }
    }

    private fun refreshApps() {
        viewModelScope.launch {
            val installed = withContext(deps.io) { Provider.LIVE_APPS.associateWith { deps.installedApps.isInstalled(it) } }
            _state.update { s ->
                s.copy(
                    apps = Provider.LIVE_APPS.map { p ->
                        val wasSelected = s.apps.firstOrNull { it.provider == p && it.installed }?.selected
                        val isInstalled = installed.getValue(p)
                        AppChoice(p, isInstalled, selected = isInstalled && (wasSelected ?: true))
                    },
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Compare
    // ---------------------------------------------------------------------------------------------------------

    /** The plan for the current choices, or null while something is missing (pickup, confirmed drop, app, vehicle). */
    fun currentPlan(): TripPlan? {
        val s = _state.value
        if (!s.canCompare) return null
        return runCatching {
            TripPlan(s.pickupPlace!!, s.drop!!, s.selectedApps, s.vehicles, s.includeOndc && s.ondcSelectable)
        }.getOrNull()
    }

    /** Compare: the pre-session check, then start (or fix-it cards). */
    fun compare() {
        val plan = currentPlan() ?: return
        runCheck(plan, startWhenClean = true)
    }

    /** Past warnings only (never past a blocking result). */
    fun continueAnyway() {
        val report = _state.value.report ?: return
        if (!report.canProceed) return
        val plan = currentPlan() ?: return
        _state.update { it.copy(report = null) }
        _events.trySend(TripEvent.StartComparison(plan))
    }

    /** A fix-it card's in-app button (settings buttons are opened by the screen, then [onResume] re-checks). */
    fun onFix(action: FixAction) {
        when (action) {
            FixAction.TRY_DEMO -> _events.trySend(TripEvent.TryDemo)
            FixAction.CHOOSE_APPS -> _state.update { it.copy(report = null) }
            FixAction.REQUEST_LOCATION -> useCurrentLocation()
            FixAction.CONTINUE_WITHOUT_ONDC -> _state.update { it.copy(includeOndc = false, report = null) }
            else -> Unit
        }
    }

    fun tryDemo() {
        _events.trySend(TripEvent.TryDemo)
    }

    /** Off screen: the microphone is never left listening in the background. A lookup already running may finish. */
    fun onPause() {
        if (_state.value.voice.phase == VoicePhase.LISTENING) cancelVoice()
    }

    /** Back on screen (maybe from a settings page): refresh apps, location and any shown check. */
    fun onResume() {
        refreshApps()
        val s = _state.value
        val waitingForLocation = s.pickup == PickupState.NeedsPermission ||
            (s.pickup as? PickupState.Typing)?.reason == TypingReason.LOCATION_DENIED
        if (waitingForLocation && deps.location.hasPermission()) locate()
        if (s.report != null) currentPlan()?.let { runCheck(it, startWhenClean = false) }
    }

    private fun runCheck(plan: TripPlan, startWhenClean: Boolean) {
        if (checkJob?.isActive == true) return
        _state.update { it.copy(checking = true) }
        checkJob = viewModelScope.launch {
            try {
                val report = deps.precheck.run(PrecheckRequest(plan.apps.toSet(), plan.includeOndc))
                val clean = !report.needsAttention
                _state.update { it.copy(checking = false, report = if (clean) null else report) }
                if (clean && startWhenClean) _events.trySend(TripEvent.StartComparison(plan))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing starts without a finished check; Compare stays available to try again.
            } finally {
                // Never leave Compare disabled behind a check that ended early (goal 21: nothing hangs).
                _state.update { it.copy(checking = false) }
            }
        }
    }

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { TripViewModel(TripDeps.from(graph)) }
        }
    }
}
