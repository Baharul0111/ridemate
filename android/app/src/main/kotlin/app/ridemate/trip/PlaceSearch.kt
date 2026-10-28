package app.ridemate.trip

import app.ridemate.core.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach

/** What the address suggestions list shows. */
sealed interface PlaceSearchState {
    /** Nothing typed. */
    data object Idle : PlaceSearchState

    /** Looking up [query]. */
    data class Searching(val query: String) : PlaceSearchState

    data class Results(val query: String, val suggestions: List<PlaceSuggestion>) : PlaceSearchState

    /** The Geocoder found nothing for [query]. */
    data class NoResults(val query: String) : PlaceSearchState

    /** No Geocoder on this phone: the typed text is used as the address (plain launch). */
    data class Unavailable(val query: String) : PlaceSearchState

    /** The lookup failed (usually offline): retry, or use the typed text. */
    data class Failed(val query: String) : PlaceSearchState
}

/**
 * Address suggestions as the user types (goal 1): the Geocoder is asked only after [debounceMs] (400 ms) without a
 * keystroke, an older lookup is abandoned when a newer query arrives, and at most [maxResults] (5) suggestions are
 * kept, with ids `c1`…`c5` so the same list can go to place-pick. Clearing the text shows [PlaceSearchState.Idle] at
 * once.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PlaceSearchController(
    scope: CoroutineScope,
    private val geocoder: PlaceGeocoder,
    private val near: () -> LatLng?,
    debounceMs: Long = DEBOUNCE_MS,
    private val maxResults: Int = MAX_RESULTS,
) {
    private data class Query(val text: String, val attempt: Int)

    private val queries = MutableStateFlow(Query("", 0))
    private val _state = MutableStateFlow<PlaceSearchState>(PlaceSearchState.Idle)
    val state: StateFlow<PlaceSearchState> = _state.asStateFlow()

    init {
        queries
            .debounce { if (it.text.isBlank()) 0L else debounceMs }
            .distinctUntilChanged()
            .mapLatest { query -> lookUp(query.text.trim()) }
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    /** The user typed; the lookup runs once typing pauses. */
    fun onQueryChange(text: String) {
        queries.value = Query(text, queries.value.attempt)
    }

    /** Runs the current query again (after a failure). */
    fun retry() {
        queries.value = queries.value.copy(attempt = queries.value.attempt + 1)
    }

    fun clear() {
        queries.value = Query("", queries.value.attempt)
    }

    private suspend fun lookUp(text: String): PlaceSearchState {
        if (text.isEmpty()) return PlaceSearchState.Idle
        _state.value = PlaceSearchState.Searching(text)
        return when (val result = geocoder.search(text, maxResults, near())) {
            is GeocodeResult.Found -> {
                val suggestions = suggestionsOf(result)
                if (suggestions.isEmpty()) PlaceSearchState.NoResults(text) else PlaceSearchState.Results(text, suggestions)
            }
            GeocodeResult.Unavailable -> PlaceSearchState.Unavailable(text)
            GeocodeResult.Failed -> PlaceSearchState.Failed(text)
        }
    }

    private fun suggestionsOf(result: GeocodeResult.Found): List<PlaceSuggestion> = result.places
        .take(maxResults)
        .mapIndexed { i, place -> PlaceSuggestion("c${i + 1}", place.label, place.point) }

    companion object {
        const val DEBOUNCE_MS: Long = 400
        const val MAX_RESULTS: Int = 5
    }
}
